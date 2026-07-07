package com.botmaker.shared.ipc;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Studio-side telemetry receiver. Binds an ephemeral loopback port <em>before</em> the bot is launched
 * (read via {@link #port()} to pass as {@link IpcEnv#PORT}), then accepts a single client connection per
 * run, validates the handshake token, decodes {@link TelemetryEvent} frames and hands each to a callback
 * (typically republished on the Studio's {@code EventBus}). One server per bot run; {@link #close()} on stop.
 */
public final class TelemetryServer implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final String token;
    private final Consumer<TelemetryEvent> onEvent;
    private final Thread acceptThread;
    private volatile boolean closed;

    public TelemetryServer(String token, Consumer<TelemetryEvent> onEvent) throws IOException {
        this.token = token;
        this.onEvent = onEvent;
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
        try (Socket client = serverSocket.accept()) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream()));
            String presented = in.readUTF(); // handshake
            if (!token.equals(presented)) return; // reject stray/unauthorized connection
            while (!closed) {
                onEvent.accept(TelemetryFrame.read(in));
            }
        } catch (IOException e) {
            // Client disconnected, frame end-of-stream, or server closed — end of this run's telemetry.
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
