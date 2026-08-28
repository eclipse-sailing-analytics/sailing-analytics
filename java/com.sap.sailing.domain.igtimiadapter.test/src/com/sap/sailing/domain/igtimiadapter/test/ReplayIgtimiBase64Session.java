package com.sap.sailing.domain.igtimiadapter.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.igtimi.IgtimiStream.Msg;

/**
 * Replays the bundled WindBot recording ({@code windbot_session_20250107.base64}) through a local
 * Riot TCP port without requiring physical hardware.
 *
 * <p>Usage: {@code ReplayIgtimiBase64Session <riot-port> [number-of-messages] [now|realtime]}
 * <ul>
 *   <li>{@code now} — shifts all message timestamps so the last message lands ~1 s in the past,
 *       useful for populating a Data Access Window that covers the current time.</li>
 *   <li>{@code realtime} — shifts timestamps so the first message starts ~1 s from now and then
 *       paces delivery to match the original inter-message timing, simulating a live WindBot.</li>
 * </ul>
 *
 * <p>Timestamp shifting works by recursively walking every protobuf {@code long} field named
 * {@code timestamp} and adding a constant offset, so it is format-agnostic within the Igtimi
 * proto schema.
 */
public class ReplayIgtimiBase64Session {
    private static final String RESOURCE = "/windbot_session_20250107.base64";
    private static final int DEFAULT_NUMBER_OF_MESSAGES = 150;
    private static final String SHIFT_TO_NOW_ARGUMENT = "now";
    private static final String REALTIME_ARGUMENT = "realtime";

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

    private static long getEarliestTimestamp(final Message message) {
        long earliestTimestamp = Long.MAX_VALUE;
        for (final Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            final FieldDescriptor descriptor = field.getKey();
            if (descriptor.getJavaType() == FieldDescriptor.JavaType.LONG && "timestamp".equals(descriptor.getName())) {
                if (descriptor.isRepeated()) {
                    for (final Object value : (List<?>) field.getValue()) {
                        earliestTimestamp = Math.min(earliestTimestamp, (Long) value);
                    }
                } else {
                    earliestTimestamp = Math.min(earliestTimestamp, (Long) field.getValue());
                }
            } else if (descriptor.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (descriptor.isRepeated()) {
                    for (final Object value : (List<?>) field.getValue()) {
                        earliestTimestamp = Math.min(earliestTimestamp, getEarliestTimestamp((Message) value));
                    }
                } else {
                    earliestTimestamp = Math.min(earliestTimestamp, getEarliestTimestamp((Message) field.getValue()));
                }
            }
        }
        return earliestTimestamp;
    }

    private static long getLatestTimestamp(final Message message) {
        long latestTimestamp = Long.MIN_VALUE;
        for (final Map.Entry<FieldDescriptor, Object> field : message.getAllFields().entrySet()) {
            final FieldDescriptor descriptor = field.getKey();
            if (descriptor.getJavaType() == FieldDescriptor.JavaType.LONG && "timestamp".equals(descriptor.getName())) {
                if (descriptor.isRepeated()) {
                    for (final Object value : (List<?>) field.getValue()) {
                        latestTimestamp = Math.max(latestTimestamp, (Long) value);
                    }
                } else {
                    latestTimestamp = Math.max(latestTimestamp, (Long) field.getValue());
                }
            } else if (descriptor.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (descriptor.isRepeated()) {
                    for (final Object value : (List<?>) field.getValue()) {
                        latestTimestamp = Math.max(latestTimestamp, getLatestTimestamp((Message) value));
                    }
                } else {
                    latestTimestamp = Math.max(latestTimestamp, getLatestTimestamp((Message) field.getValue()));
                }
            }
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

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 3) {
            System.out.println("Usage: ReplayIgtimiBase64Session <riot-port> [number-of-messages] [now]");
        }
        final int port = Integer.parseInt(args[0]);
        //final int port = 6000;
        final int numberOfMessages = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_NUMBER_OF_MESSAGES;
        final boolean shiftToNow = args.length == 3 && SHIFT_TO_NOW_ARGUMENT.equalsIgnoreCase(args[2]);
        final boolean replayInRealtime = args.length == 3 && REALTIME_ARGUMENT.equalsIgnoreCase(args[2]);
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
                final long timestampOffset = shiftToNow ? calculateTimestampOffsetToNow(messages) : replayInRealtime ? calculateTimestampOffsetForRealtimeReplay(messages) : 0;
                if (replayInRealtime) {
                    System.out.println("Replaying " + messages.size() + " messages in real time over approximately " + calculateReplayDurationInMilliseconds(messages) / 1000.0 + " seconds...");
                }
                try (final Socket socket = new Socket("localhost", port)) {
                    final OutputStream output = socket.getOutputStream();
                    for (final Msg message : messages) {
                        if (replayInRealtime) {
                            waitUntilMessageTimestamp(message, timestampOffset);
                        }
                        (shiftToNow || replayInRealtime ? shiftTimestamps(message, timestampOffset) : message).writeDelimitedTo(output);
                    }
                    output.flush();
                    System.out.println("Replayed " + messages.size() + " messages from " + RESOURCE + " to Riot on port " + port + (shiftToNow ? " with timestamps shifted to now" : ""));
                }
            }
        }
    }
}