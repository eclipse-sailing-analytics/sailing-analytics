package com.sap.sailing.domain.igtimiadapter.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.igtimi.IgtimiStream.Msg;
import com.sap.sailing.domain.igtimiadapter.FixFactory;
import com.sap.sailing.domain.igtimiadapter.datatypes.Fix;

/**
 * Replays the bundled WindBot recording ({@code windbot_session_20250107.base64}) through a local
 * Riot TCP port without requiring physical hardware.
 *
 * <p>Single-device usage: {@code ReplayIgtimiBase64Session <riot-port> [number-of-messages] [now|realtime]}
 * <ul>
 *   <li>{@code now} — shifts all message timestamps so the last message lands ~1 s in the past,
 *       useful for populating a Data Access Window that covers the current time.</li>
 *   <li>{@code realtime} — shifts timestamps so the first message starts ~1 s from now and then
 *       paces delivery to match the original inter-message timing, simulating a live WindBot.</li>
 * </ul>
 *
 * <p>Dual-device usage: {@code ReplayIgtimiBase64Session <riot-port> dual [now|realtime]}
 * replays two different 20-minute slices from the bundled recording as {@code DC-GD-AAED} and
 * {@code DC-MM-AACN}. The default dual-device mode is {@code realtime}; both WindBots start together
 * and replay concurrently for approximately 20 minutes. Use {@code now} to send both 20-minute
 * datasets immediately with their latest timestamps shifted to the current time.
 *
 * <p>Replay timing is derived from the fixes created by {@link FixFactory}. Timestamp shifting itself works by
 * recursively walking every protobuf {@code long} field named {@code timestamp} and adding a constant offset, so it
 * is format-agnostic within the Igtimi proto schema.
 */
public class ReplayIgtimiBase64Session {
    private static final String RESOURCE = "/windbot_session_20250107.base64";
    private static final int DEFAULT_NUMBER_OF_MESSAGES = 150;
    private static final String SHIFT_TO_NOW_ARGUMENT = "now";
    private static final String REALTIME_ARGUMENT = "realtime";
    private static final String DUAL_ARGUMENT = "dual";
    private static final String FIRST_DUAL_DEVICE_SERIAL_NUMBER = "DC-GD-AAED";
    private static final String SECOND_DUAL_DEVICE_SERIAL_NUMBER = "DC-MM-AACN";
    private static final long DUAL_REPLAY_DURATION_IN_MILLISECONDS = 20L * 60L * 1000L;
    private static final FixFactory FIX_FACTORY = new FixFactory();

    private static long calculateTimestampOffsetToNow(final Iterable<Msg> messages) {
        return System.currentTimeMillis() - 1000L - getLatestTimestamp(messages);
    }

    private static long calculateTimestampOffsetForRealtimeReplay(final Iterable<Msg> messages) {
        final long earliestTimestamp = getEarliestTimestamp(messages);
        return System.currentTimeMillis() + 1000L - earliestTimestamp;
    }

    private static long calculateReplayDurationInMilliseconds(final Iterable<Msg> messages) {
        return getLatestTimestamp(messages) - getEarliestTimestamp(messages);
    }

    private static void waitUntilMessageTimestamp(final Msg message, final long timestampOffset) {
        final long messageTimestamp = getLatestTimestamp(message);
        if (messageTimestamp != Long.MIN_VALUE) {
            final long waitInMilliseconds = messageTimestamp + timestampOffset - System.currentTimeMillis();
            if (waitInMilliseconds > 0) {
                try {
                    Thread.sleep(waitInMilliseconds);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static long getEarliestTimestamp(final Iterable<Msg> messages) {
        long earliestTimestamp = Long.MAX_VALUE;
        for (final Msg message : messages) {
            earliestTimestamp = Math.min(earliestTimestamp, getEarliestTimestamp(message));
        }
        if (earliestTimestamp == Long.MAX_VALUE) {
            throw new IllegalArgumentException("No timestamp found in replayed Igtimi messages");
        }
        return earliestTimestamp;
    }

    private static long getLatestTimestamp(final Iterable<Msg> messages) {
        long latestTimestamp = Long.MIN_VALUE;
        for (final Msg message : messages) {
            latestTimestamp = Math.max(latestTimestamp, getLatestTimestamp(message));
        }
        if (latestTimestamp == Long.MIN_VALUE) {
            throw new IllegalArgumentException("No timestamp found in replayed Igtimi messages");
        }
        return latestTimestamp;
    }

    private static long getEarliestTimestamp(final Msg message) {
        long earliestTimestamp = Long.MAX_VALUE;
        for (final Fix fix : FIX_FACTORY.createFixes(message)) {
            earliestTimestamp = Math.min(earliestTimestamp, fix.getTimePoint().asMillis());
        }
        return earliestTimestamp;
    }

    private static long getLatestTimestamp(final Msg message) {
        long latestTimestamp = Long.MIN_VALUE;
        for (final Fix fix : FIX_FACTORY.createFixes(message)) {
            latestTimestamp = Math.max(latestTimestamp, fix.getTimePoint().asMillis());
        }
        return latestTimestamp;
    }

    private static Msg shiftTimestamps(final Msg message, final long timestampOffset) {
        return (Msg) shiftTimestamps((Message) message, timestampOffset);
    }

    private static Message shiftTimestamps(final Message message, final long timestampOffset) {
        final Message.Builder builder = message.toBuilder();
        for (final Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            final FieldDescriptor descriptor = field.getKey();
            if (descriptor.getJavaType() == FieldDescriptor.JavaType.LONG && "timestamp".equals(descriptor.getName())) {
                if (descriptor.isRepeated()) {
                    final List<?> values = (List<?>) field.getValue();
                    for (int i = 0; i < values.size(); i++) {
                        builder.setRepeatedField(descriptor, i, (Long) values.get(i) + timestampOffset);
                    }
                } else {
                    builder.setField(descriptor, (Long) field.getValue() + timestampOffset);
                }
            } else if (descriptor.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (descriptor.isRepeated()) {
                    final List<?> values = (List<?>) field.getValue();
                    for (int i = 0; i < values.size(); i++) {
                        builder.setRepeatedField(descriptor, i, shiftTimestamps((Message) values.get(i), timestampOffset));
                    }
                } else {
                    builder.setField(descriptor, shiftTimestamps((Message) field.getValue(), timestampOffset));
                }
            }
        }
        return builder.build();
    }

    private static Msg setSerialNumber(final Msg message, final String serialNumber) {
        final Msg.Builder builder = message.toBuilder();
        if (builder.hasData()) {
            for (int i = 0; i < builder.getDataBuilder().getDataCount(); i++) {
                builder.getDataBuilder().getDataBuilder(i).setSerialNumber(serialNumber);
            }
        }
        return builder.build();
    }

    private static Msg prepareMessage(final Msg message, final long timestampOffset, final String serialNumber) {
        return setSerialNumber(shiftTimestamps(message, timestampOffset), serialNumber);
    }

    private static List<Msg> loadAllMessages() throws IOException {
        try (final InputStream input = ReplayIgtimiBase64Session.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalArgumentException("Resource not found: " + RESOURCE);
            }
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                final List<Msg> messages = new ArrayList<>();
                String base64;
                while ((base64 = reader.readLine()) != null) {
                    messages.add(Msg.parseFrom(Base64.getDecoder().decode(base64)));
                }
                return messages;
            }
        }
    }

    private static List<Msg> takeFirstDuration(final List<Msg> messages, final long durationInMilliseconds) {
        final List<Msg> result = new ArrayList<>();
        long earliestTimestamp = Long.MAX_VALUE;
        long latestTimestamp = Long.MIN_VALUE;
        for (final Msg message : messages) {
            result.add(message);
            earliestTimestamp = Math.min(earliestTimestamp, getEarliestTimestamp(message));
            latestTimestamp = Math.max(latestTimestamp, getLatestTimestamp(message));
            if (earliestTimestamp != Long.MAX_VALUE && latestTimestamp != Long.MIN_VALUE
                    && latestTimestamp - earliestTimestamp >= durationInMilliseconds) {
                break;
            }
        }
        ensureReplayDuration(result, durationInMilliseconds);
        return result;
    }

    private static List<Msg> takeLastDuration(final List<Msg> messages, final long durationInMilliseconds) {
        final List<Msg> result = new ArrayList<>();
        long earliestTimestamp = Long.MAX_VALUE;
        long latestTimestamp = Long.MIN_VALUE;
        for (int i = messages.size() - 1; i >= 0; i--) {
            final Msg message = messages.get(i);
            result.add(message);
            earliestTimestamp = Math.min(earliestTimestamp, getEarliestTimestamp(message));
            latestTimestamp = Math.max(latestTimestamp, getLatestTimestamp(message));
            if (earliestTimestamp != Long.MAX_VALUE && latestTimestamp != Long.MIN_VALUE
                    && latestTimestamp - earliestTimestamp >= durationInMilliseconds) {
                break;
            }
        }
        Collections.reverse(result);
        ensureReplayDuration(result, durationInMilliseconds);
        return result;
    }

    private static void ensureReplayDuration(final Iterable<Msg> messages, final long expectedDurationInMilliseconds) {
        final long replayDuration = calculateReplayDurationInMilliseconds(messages);
        if (replayDuration < expectedDurationInMilliseconds) {
            throw new IllegalArgumentException("The bundled recording contains only " + replayDuration / 1000.0
                    + " seconds for the requested slice; expected at least " + expectedDurationInMilliseconds / 1000.0
                    + " seconds");
        }
    }

    private static void replayMessages(final int port, final List<Msg> messages, final String serialNumber,
            final long timestampOffset, final boolean replayInRealtime) throws IOException {
        try (final Socket socket = new Socket("localhost", port)) {
            final OutputStream output = socket.getOutputStream();
            for (final Msg message : messages) {
                if (replayInRealtime) {
                    waitUntilMessageTimestamp(message, timestampOffset);
                }
                prepareMessage(message, timestampOffset, serialNumber).writeDelimitedTo(output);
            }
            output.flush();
        }
        System.out.println("Finished replay for " + serialNumber);
    }

    private static void replayTwoWindBots(final int port, final boolean shiftToNow, final boolean replayInRealtime)
            throws IOException {
        final List<Msg> recordedSession = loadAllMessages();
        final List<Msg> firstDataset = takeFirstDuration(recordedSession, DUAL_REPLAY_DURATION_IN_MILLISECONDS);
        final List<Msg> secondDataset = takeLastDuration(recordedSession, DUAL_REPLAY_DURATION_IN_MILLISECONDS);

        final long firstTimestampOffset;
        final long secondTimestampOffset;
        if (replayInRealtime) {
            final long replayStartTime = System.currentTimeMillis() + 1000L;
            firstTimestampOffset = replayStartTime - getEarliestTimestamp(firstDataset);
            secondTimestampOffset = replayStartTime - getEarliestTimestamp(secondDataset);
        } else {
            final long replayEndTime = System.currentTimeMillis() - 1000L;
            firstTimestampOffset = replayEndTime - getLatestTimestamp(firstDataset);
            secondTimestampOffset = replayEndTime - getLatestTimestamp(secondDataset);
        }

        System.out.println("Replaying two WindBots from " + RESOURCE + ":");
        System.out.println("  " + FIRST_DUAL_DEVICE_SERIAL_NUMBER + ": first 20-minute slice, " + firstDataset.size()
                + " messages");
        System.out.println("  " + SECOND_DUAL_DEVICE_SERIAL_NUMBER + ": last 20-minute slice, " + secondDataset.size()
                + " messages");
        System.out.println(replayInRealtime ? "Both streams start together and run in real time for approximately 20 minutes."
                : "Both 20-minute datasets are being sent immediately with timestamps shifted to now.");

        final AtomicReference<Throwable> replayFailure = new AtomicReference<>();
        final Thread firstReplay = new Thread(() -> {
            try {
                replayMessages(port, firstDataset, FIRST_DUAL_DEVICE_SERIAL_NUMBER, firstTimestampOffset,
                        replayInRealtime);
            } catch (final Throwable t) {
                replayFailure.compareAndSet(null, t);
            }
        }, "igtimi-replay-" + FIRST_DUAL_DEVICE_SERIAL_NUMBER);
        final Thread secondReplay = new Thread(() -> {
            try {
                replayMessages(port, secondDataset, SECOND_DUAL_DEVICE_SERIAL_NUMBER, secondTimestampOffset,
                        replayInRealtime);
            } catch (final Throwable t) {
                replayFailure.compareAndSet(null, t);
            }
        }, "igtimi-replay-" + SECOND_DUAL_DEVICE_SERIAL_NUMBER);

        firstReplay.start();
        secondReplay.start();
        try {
            firstReplay.join();
            secondReplay.join();
        } catch (final InterruptedException e) {
            firstReplay.interrupt();
            secondReplay.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        final Throwable failure = replayFailure.get();
        if (failure instanceof IOException) {
            throw (IOException) failure;
        } else if (failure != null) {
            throw new RuntimeException(failure);
        }
        System.out.println("Finished replaying both WindBots.");
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  ReplayIgtimiBase64Session <riot-port> [number-of-messages] [now|realtime]");
        System.out.println("  ReplayIgtimiBase64Session <riot-port> dual [now|realtime]");
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            return;
        }
        final int port = Integer.parseInt(args[0]);
        if (args.length >= 2 && DUAL_ARGUMENT.equalsIgnoreCase(args[1])) {
            if (args.length > 3) {
                printUsage();
                return;
            }
            final boolean shiftToNow = args.length == 3 && SHIFT_TO_NOW_ARGUMENT.equalsIgnoreCase(args[2]);
            final boolean replayInRealtime = args.length < 3 || REALTIME_ARGUMENT.equalsIgnoreCase(args[2]);
            if (args.length == 3 && !shiftToNow && !replayInRealtime) {
                printUsage();
                return;
            }
            replayTwoWindBots(port, shiftToNow, replayInRealtime);
            return;
        }
        if (args.length > 3) {
            printUsage();
            return;
        }
        final int numberOfMessages = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_NUMBER_OF_MESSAGES;
        final boolean shiftToNow = args.length == 3 && SHIFT_TO_NOW_ARGUMENT.equalsIgnoreCase(args[2]);
        final boolean replayInRealtime = args.length == 3 && REALTIME_ARGUMENT.equalsIgnoreCase(args[2]);
        if (args.length == 3 && !shiftToNow && !replayInRealtime) {
            printUsage();
            return;
        }
        try (final InputStream input = ReplayIgtimiBase64Session.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalArgumentException("Resource not found: " + RESOURCE);
            }
            try (final BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
                final List<Msg> messages = new ArrayList<>();
                String base64;
                while (messages.size() < numberOfMessages && (base64 = reader.readLine()) != null) {
                    messages.add(Msg.parseFrom(Base64.getDecoder().decode(base64)));
                }
                final long timestampOffset = shiftToNow ? calculateTimestampOffsetToNow(messages)
                        : replayInRealtime ? calculateTimestampOffsetForRealtimeReplay(messages) : 0;
                if (replayInRealtime) {
                    System.out.println("Replaying " + messages.size() + " messages in real time over approximately "
                            + calculateReplayDurationInMilliseconds(messages) / 1000.0 + " seconds...");
                }
                try (final Socket socket = new Socket("localhost", port)) {
                    final OutputStream output = socket.getOutputStream();
                    for (final Msg message : messages) {
                        if (replayInRealtime) {
                            waitUntilMessageTimestamp(message, timestampOffset);
                        }
                        (shiftToNow || replayInRealtime ? shiftTimestamps(message, timestampOffset) : message)
                                .writeDelimitedTo(output);
                    }
                    output.flush();
                    System.out.println("Replayed " + messages.size() + " messages from " + RESOURCE + " to Riot on port "
                            + port + (shiftToNow ? " with timestamps shifted to now" : ""));
                }
            }
        }
    }
}
