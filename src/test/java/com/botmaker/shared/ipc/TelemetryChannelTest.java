package com.botmaker.shared.ipc;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** End-to-end loopback tests for {@link TelemetryServer} ↔ {@link TelemetryClient}. */
class TelemetryChannelTest {

    private static final TelemetryEvent.Target TARGET =
            new TelemetryEvent.Target("Game", 0, 0, 640, 480);

    @Test
    void framesDeliveredOverLoopback() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        try (TelemetryServer server = new TelemetryServer("secret", received::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "secret")) {
                TelemetryEvent match = new TelemetryEvent.Match(
                        TARGET, null, new TelemetryEvent.Rect(1, 2, 3, 4), 0.8, true);
                TelemetryEvent click = new TelemetryEvent.Click(TARGET, 5, 6, 1);
                client.send(match);
                client.send(click);

                assertEquals(match, received.poll(3, TimeUnit.SECONDS));
                assertEquals(click, received.poll(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        BlockingQueue<TelemetryEvent> received = new ArrayBlockingQueue<>(8);
        try (TelemetryServer server = new TelemetryServer("expected", received::offer)) {
            try (TelemetryClient client = new TelemetryClient(server.port(), "wrong")) {
                client.send(new TelemetryEvent.Click(TARGET, 1, 1, 1));
                // Server closes the connection after the bad handshake; nothing should arrive.
                assertNull(received.poll(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sendNeverBlocksWhenNoServer() {
        // No server listening: the writer thread stalls on connect, so send() must drop, not block.
        try (TelemetryClient client = new TelemetryClient(1, "token", 2)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 10_000; i++) {
                    client.send(new TelemetryEvent.Click(TARGET, i, i, 1));
                }
            });
        }
    }
}
