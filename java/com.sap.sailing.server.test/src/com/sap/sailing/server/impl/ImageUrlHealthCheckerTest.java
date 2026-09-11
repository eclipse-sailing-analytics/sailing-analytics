package com.sap.sailing.server.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import com.sap.sailing.domain.base.Event;
import com.sap.sse.security.SecurityService;
import com.sap.sse.security.shared.OwnershipAnnotation;
import com.sap.sse.security.shared.QualifiedObjectIdentifier;
import com.sap.sse.security.shared.impl.Ownership;
import com.sap.sse.security.shared.impl.User;
import com.sap.sse.shared.media.ImageDescriptor;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sap.sailing.server.interfaces.RacingEventService;
import com.sap.sailing.server.operationaltransformation.UpdateEventImageHealth;
import com.sap.sse.common.Duration;
import com.sap.sse.common.mail.MailException;
import com.sap.sse.replication.ReplicationMasterDescriptor;

public class ImageUrlHealthCheckerTest {
    private static final String VALID_IMAGE_PATH = "/image";
    private static final String REDIRECT_PATH = "/redirect";
    private static final String HTTP_ERROR_PATH = "/not-found";
    private static final String HTML_PATH = "/html";
    private static final String SLOW_IMAGE_PATH = "/slow-image";

    private TestHttpServer server;
    private byte[] imageBytes;

    @BeforeEach
    public void setUp() throws Exception {
        imageBytes = createImageBytes();
        server = new TestHttpServer();
        server.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        server.stop();
    }

    @Test
    public void testValidImageIsAvailable() throws Exception {
        assertTrue(new ImageUrlHealthChecker().isImageAvailable(getUrl(VALID_IMAGE_PATH)));
    }

    @Test
    public void testRedirectToValidImageIsAvailable() throws Exception {
        assertTrue(new ImageUrlHealthChecker().isImageAvailable(getUrl(REDIRECT_PATH)));
    }

    @Test
    public void testHttpErrorIsNotAvailable() throws Exception {
        assertFalse(new ImageUrlHealthChecker().isImageAvailable(getUrl(HTTP_ERROR_PATH)));
    }

    @Test
    public void testHtmlResponseIsNotAvailable() throws Exception {
        assertFalse(new ImageUrlHealthChecker().isImageAvailable(getUrl(HTML_PATH)));
    }

    @Test
    public void testReadTimeoutIsNotAvailable() throws Exception {
        final ImageUrlHealthChecker checker = new ImageUrlHealthChecker(Duration.ONE_SECOND.divide(10));
        assertFalse(checker.isImageAvailable(getUrl(SLOW_IMAGE_PATH)));
    }

    @Test
    public void testConnectionFailureIsNotAvailable() throws Exception {
        final URL url = getUrl(VALID_IMAGE_PATH);
        server.stop();
        assertFalse(new ImageUrlHealthChecker(Duration.ONE_SECOND).isImageAvailable(url));
    }

    @Test
    public void testBrokenEventImageNotifiesOwnerAndPersistsState() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, event, "owner");
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(securityService, times(1)).sendMail("owner", "Broken image for event Event",
                "The image http://example.com/image.jpg configured for event \"Event\" is no longer available. "
                        + "Please update or replace the image.");
        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ true, /* notificationSent */ true);
    }

    @Test
    public void testAlreadyMissingAndNotifiedImageDoesNotNotifyAgain() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ true, /* notificationSent */ true);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, event, "owner");
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(securityService, never()).sendMail(anyString(), anyString(), anyString());
        verify(eventService, never()).apply(any(UpdateEventImageHealth.class));
    }

    @Test
    public void testMissingImageWithoutNotificationRetriesNotification() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ true, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, event, "owner");
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(securityService, times(1)).sendMail("owner", "Broken image for event Event",
                "The image http://example.com/image.jpg configured for event \"Event\" is no longer available. "
                        + "Please update or replace the image.");
        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ true, /* notificationSent */ true);
    }

    @Test
    public void testRecoveredEventImageClearsPersistedState() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ true, /* notificationSent */ true);
        final SecurityService securityService = mock(SecurityService.class);
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(true);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(securityService, never()).sendMail(anyString(), anyString(), anyString());
        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ false, /* notificationSent */ false);
    }

    @Test
    public void testHealthyEventImageWithCleanStateIsNotUpdated() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(true);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(eventService, never()).apply(any(UpdateEventImageHealth.class));
    }

    @Test
    public void testSameImageUrlIsCheckedOncePerRunButNotifiesBothOwners() throws Exception {
        final URL imageUrl = new URL("http://example.com/shared.jpg");
        final Event firstEvent = mockEvent("First Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final Event secondEvent = mockEvent("Second Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, firstEvent, "first-owner");
        mockOwnership(securityService, secondEvent, "second-owner");
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Arrays.asList(firstEvent, secondEvent),
                securityService, imageUrlHealthChecker, eventService);

        verify(imageUrlHealthChecker, times(1)).isImageAvailable(imageUrl);
        verify(securityService, times(1)).sendMail("first-owner", "Broken image for event First Event",
                "The image http://example.com/shared.jpg configured for event \"First Event\" is no longer available. "
                        + "Please update or replace the image.");
        verify(securityService, times(1)).sendMail("second-owner", "Broken image for event Second Event",
                "The image http://example.com/shared.jpg configured for event \"Second Event\" is no longer available. "
                        + "Please update or replace the image.");
        verify(eventService, times(2)).apply(any(UpdateEventImageHealth.class));
    }

    @Test
    public void testFailedMailLeavesNotificationUnsentForRetry() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, event, "owner");
        doThrow(new MailException("test failure")).when(securityService).sendMail(anyString(), anyString(), anyString());
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(true).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ true, /* notificationSent */ false);
    }

    @Test
    public void testBrokenEventImageDoesNotNotifyOwnerWhenOwnerNotificationsAreDisabled() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        mockOwnership(securityService, event, "owner");
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        createActivatorWithOwnerNotificationEnabled(false).checkEventImages(Collections.singleton(event),
                securityService, imageUrlHealthChecker, eventService);

        verify(securityService, never()).sendMail(anyString(), anyString(), anyString());
        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ true, /* notificationSent */ false);
    }
    
    @Test
    public void testEventImagesAreNotCheckedOnReplica() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        when(securityService.getMasterDescriptor()).thenReturn(mock(ReplicationMasterDescriptor.class));
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        final RacingEventService eventService = mock(RacingEventService.class);

        new Activator().checkEventImages(Collections.singleton(event), securityService, imageUrlHealthChecker,
                eventService);

        verify(imageUrlHealthChecker, never()).isImageAvailable(imageUrl);
        verify(securityService, never()).sendMail(anyString(), anyString(), anyString());
        verify(eventService, never()).apply(any(UpdateEventImageHealth.class));
    }
    
    @Test
    public void testBrokenEventImageWithoutOwnerDoesNotSendMail() throws Exception {
        final URL imageUrl = new URL("http://example.com/image.jpg");
        final Event event = mockEvent("Event", imageUrl, /* missing */ false, /* notificationSent */ false);
        final SecurityService securityService = mock(SecurityService.class);
        final ImageUrlHealthChecker imageUrlHealthChecker = mock(ImageUrlHealthChecker.class);
        when(imageUrlHealthChecker.isImageAvailable(imageUrl)).thenReturn(false);
        final RacingEventService eventService = mock(RacingEventService.class);

        new Activator().checkEventImages(Collections.singleton(event), securityService, imageUrlHealthChecker,
                eventService);

        verify(securityService, never()).sendMail(anyString(), anyString(), anyString());
        verifyImageHealthUpdate(eventService, event, imageUrl, /* missing */ true, /* notificationSent */ false);
    }

    private Event mockEvent(String name, URL imageUrl, boolean missing, boolean notificationSent) {
        final Event event = mock(Event.class);
        final ImageDescriptor image = mock(ImageDescriptor.class);
        when(event.getId()).thenReturn(UUID.randomUUID());
        when(event.getIdentifier()).thenReturn(mock(QualifiedObjectIdentifier.class));
        when(event.getName()).thenReturn(name);
        when(event.getImages()).thenReturn(Collections.singleton(image));
        when(image.getURL()).thenReturn(imageUrl);
        when(image.isMissing()).thenReturn(missing);
        when(image.isMissingMailNotificationSent()).thenReturn(notificationSent);
        return event;
    }

    private void verifyImageHealthUpdate(RacingEventService eventService, Event event, URL imageUrl,
            boolean missing, boolean notificationSent) {
        final ArgumentCaptor<UpdateEventImageHealth> operationCaptor =
                ArgumentCaptor.forClass(UpdateEventImageHealth.class);
        verify(eventService, times(1)).apply(operationCaptor.capture());
        final RacingEventService target = mock(RacingEventService.class);
        operationCaptor.getValue().internalApplyTo(target);
        verify(target, times(1)).updateEventImageHealth(event.getId(), imageUrl.toString(), missing, notificationSent);
    }

    private void mockOwnership(SecurityService securityService, Event event, String username) {
        final OwnershipAnnotation ownership = mock(OwnershipAnnotation.class);
        final Ownership ownershipValue = mock(Ownership.class);
        final User owner = mock(User.class);
        when(securityService.getOwnership(event.getIdentifier())).thenReturn(ownership);
        when(ownership.getAnnotation()).thenReturn(ownershipValue);
        when(ownershipValue.getUserOwner()).thenReturn(owner);
        when(owner.getName()).thenReturn(username);
    }
    
    private URL getUrl(String path) throws Exception {
        return new URL("http://127.0.0.1:" + server.getPort() + path);
    }

    private byte[] createImageBytes() throws IOException {
        final BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", outputStream);
        return outputStream.toByteArray();
    }

    private class TestHttpServer implements Runnable {
        private final ServerSocket serverSocket;
        private final Thread serverThread;

        private TestHttpServer() throws IOException {
            serverSocket = new ServerSocket(0);
            serverThread = new Thread(this, ImageUrlHealthCheckerTest.class.getSimpleName() + " HTTP server");
            serverThread.setDaemon(true);
        }
        private void start() {
            serverThread.start();
        }
        private int getPort() {
            return serverSocket.getLocalPort();
        }
        private void stop() throws InterruptedException, IOException {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
            serverThread.join();
        }
        @Override
        public void run() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    handle(socket);
                } catch (SocketException e) {
                    if (!serverSocket.isClosed()) {
                        throw new RuntimeException(e);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            final String requestLine = reader.readLine();
            if (requestLine == null) {
                return;
            }
            final String[] requestLineParts = requestLine.split(" ");
            final String path = requestLineParts[1];
            final OutputStream outputStream = socket.getOutputStream();
            switch (path) {
            case VALID_IMAGE_PATH:
                writeImage(outputStream);
                break;
            case REDIRECT_PATH:
                writeResponse(outputStream, "302 Found", "Location: " + VALID_IMAGE_PATH + "\r\n", new byte[0]);
                break;
            case HTTP_ERROR_PATH:
                writeResponse(outputStream, "404 Not Found", "", new byte[0]);
                break;
            case HTML_PATH:
                writeResponse(outputStream, "200 OK", "Content-Type: text/html\r\n",
                        "<html><body>Not an image</body></html>".getBytes(StandardCharsets.US_ASCII));
                break;
            case SLOW_IMAGE_PATH:
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                writeImage(outputStream);
                break;
            default:
                writeResponse(outputStream, "404 Not Found", "", new byte[0]);
                break;
            }
        }

        private void writeImage(OutputStream outputStream) throws IOException {
            writeResponse(outputStream, "200 OK", "Content-Type: image/jpeg\r\n", imageBytes);
        }

        private void writeResponse(OutputStream outputStream, String status, String headers, byte[] body)
                throws IOException {
            outputStream.write(("HTTP/1.1 " + status + "\r\n" + headers + "Content-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            outputStream.write(body);
            outputStream.flush();
        }
    }
    private Activator createActivatorWithOwnerNotificationEnabled(boolean enabled) {
        final String propertyName = Activator.EVENT_IMAGE_OWNER_NOTIFICATION_ENABLED_PROPERTY_NAME;
        final String previousValue = System.getProperty(propertyName);
        System.setProperty(propertyName, Boolean.toString(enabled));
        try {
            return new Activator();
        } finally {
            if (previousValue == null) {
                System.clearProperty(propertyName);
            } else {
                System.setProperty(propertyName, previousValue);
            }
        }
    }
}
