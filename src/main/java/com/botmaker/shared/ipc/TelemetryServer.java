package com.botmaker.shared.ipc;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Studio-side telemetry receiver. Binds an ephemeral loopback port <em>before</em> the bot is launched
 * (read via {@link #port()} to pass as {@link IpcEnv#PORT}), validates the handshake token, decodes
 * {@link TelemetryEvent} frames and hands each to a callback (typically republished on the Studio's
 * {@code EventBus}). One server per bot run; {@link #close()} on stop.
 *
 * <p><b>Resilient by design:</b> a single undecodable frame (e.g. an older-SDK bot speaking a different wire
 * version — a {@link TelemetryFrame.FrameFormatException}) is skipped and reported once via {@code onError}
 * rather than dropping the whole channel, and the accept loop re-accepts after a client disconnects so a
 * reconnecting bot resumes telemetry within the same run instead of going permanently "disconnected".
 */
public final class TelemetryServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final String token;
    private final Consumer<TelemetryEvent> onEvent;
    private final Consumer<String> onError;
    private final AtomicBoolean errorReported = new AtomicBoolean(false);
    private final Thread acceptThread;
    private volatile boolean closed;

    public TelemetryServer(String token, Consumer<TelemetryEvent> onEvent) throws IOException {
        this(token, onEvent, null);
    }

    /**
     * @param onError optional one-time sink for a human-readable reason the stream couldn't be fully decoded
     *                (e.g. wire-version skew from an old SDK). May be {@code null}.
     */
    public TelemetryServer(String token, Consumer<TelemetryEvent> onEvent, Consumer<String> onError) throws IOException {
        this.token = token;
        this.onEvent = onEvent;
        this.onError = onError;
        this.serverSocket = new ServerSocket();
        this.serverSocket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        this.acceptThread = new Thread(this::acceptLoop, "telemetry-server-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /** The ephemeral port the server is listening on — pass to the bot as {@link IpcEnv#PORT}. */
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        // Re-accept across client reconnects for the life of the run; close() shuts the ServerSocket, which
        // makes accept() throw and breaks us out.
        while (!closed) {
            try (Socket client = serverSocket.accept()) {
                DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
                String presented = in.readUTF(); // handshake
                if (!token.equals(presented)) continue; // ignore stray/unauthorized; keep serving
                while (!closed) {
                    try {
                        onEvent.accept(TelemetryFrame.read(in));
                    } catch (TelemetryFrame.FrameFormatException recoverable) {
                        // Framing stays aligned — skip this frame and keep reading. Report the cause once so
                        // a version-skewed (old-SDK) bot surfaces a clear notice instead of a silent blank.
                        reportErrorOnce(recoverable.getMessage());
                    }
                }
            } catch (IOException e) {
                // This client's stream ended/reset, or the server socket was closed. Loop to re-accept a
                // possible reconnect; if we were closed, the while condition exits us.
            }
        }
    }

    private void reportErrorOnce(String message) {
        if (onError != null && errorReported.compareAndSet(false, true)) {
            onError.accept(message);
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
