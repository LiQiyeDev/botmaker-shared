package com.botmaker.shared.ipc;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Bot-side telemetry sender: connects to the Studio's {@link TelemetryServer} on {@code 127.0.0.1} and
 * streams {@link TelemetryEvent} frames. Fully best-effort and non-blocking so it never slows the bot:
 * {@link #send} enqueues onto a bounded queue (dropping on overflow) and a single background writer thread
 * does the socket I/O. Any {@link java.io.IOException} (e.g. the Studio went away) permanently disables the
 * channel for the rest of the run.
 *
 * <p>Use {@link #fromEnvironment()} at bot startup: it returns {@code null} when {@link IpcEnv#PORT} is
 * unset, so a bot launched outside the Studio opens no socket at all.
 */
public final class TelemetryClient implements AutoCloseable {

    private static final int DEFAULT_CAPACITY = 1024;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final TelemetryEvent POISON =
            new TelemetryEvent.Region(new TelemetryEvent.Target(null, 0, 0, 0, 0), null);

    private final int port;
    private final String token;
    private final BlockingQueue<TelemetryEvent> queue;
    private final Thread writer;
    private volatile boolean disabled;

    public TelemetryClient(int port, String token) {
        this(port, token, DEFAULT_CAPACITY);
    }

    public TelemetryClient(int port, String token, int capacity) {
        this.port = port;
        this.token = token;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.writer = new Thread(this::run, "telemetry-client-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    /**
     * Builds a client from {@link IpcEnv} environment variables, or {@code null} when telemetry is not
     * enabled (no {@link IpcEnv#PORT}) — the zero-overhead path for normal published bots.
     */
    public static TelemetryClient fromEnvironment() {
        String portStr = System.getenv(IpcEnv.PORT);
        String token = System.getenv(IpcEnv.TOKEN);
        if (portStr == null || token == null) return null;
        try {
            return new TelemetryClient(Integer.parseInt(portStr.trim()), token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Enqueues an event for delivery; silently drops it if the channel is disabled or the queue is full. */
    public void send(TelemetryEvent event) {
        if (disabled || event == null) return;
        queue.offer(event); // non-blocking: drop on overflow rather than stall the bot
    }

    private void run() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            out.writeUTF(token); // handshake
            out.flush();
            while (!disabled) {
                TelemetryEvent event = queue.take();
                if (event == POISON) break;
                TelemetryFrame.write(out, event);
            }
        } catch (Exception e) {
            // Studio absent / gone / interrupted: disable for the rest of the run.
        } finally {
            disabled = true;
        }
    }

    @Override
    public void close() {
        disabled = true;
        queue.offer(POISON);
        writer.interrupt();
    }
}
