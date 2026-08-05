package com.vukotic.crane.ui;

import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneState;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Publishes crane state to the out-of-process visualiser over loopback.
 *
 * <p>Write-only, and bound to {@code 127.0.0.1} so it is not reachable from another
 * machine. The viewer connects, reads, and has no way to send anything back: the
 * socket carries state outward and commands never travel the other way. That is
 * the property the separate-process design exists to get — a renderer cannot
 * become a control path, however badly it behaves.
 *
 * <p>The format is the telemetry CSV from {@code TelemetryCsvLogger}: a header
 * naming the axes, then a row per published state. Reusing it means a live feed and
 * a saved recording are the same bytes, so the viewer needs one parser, and the
 * feed can be inspected with any line-oriented tool.
 *
 * <p>Failure is never allowed to matter. A viewer that is not running, or one that
 * stops reading, drops its connection and the cockpit carries on; this class is a
 * {@link Consumer} of state that swallows its own IO errors on purpose.
 */
public final class ViewerFeedServer implements Consumer<CraneState>, AutoCloseable {

    /** Loopback-only port the viewer connects to. */
    public static final int DEFAULT_PORT = 7420;

    private final int port;
    private final CraneProfile profile;
    private final List<String> axisIds;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    private record Client(Socket socket, BufferedWriter writer) {
    }

    public ViewerFeedServer(CraneProfile profile) {
        this(profile, DEFAULT_PORT);
    }

    public ViewerFeedServer(CraneProfile profile, int port) {
        this.profile = profile;
        this.port = port;
        this.axisIds = profile.axisIds();
    }

    /** Starts listening. Returns false if the port is taken — never throws. */
    public boolean start() {
        try {
            serverSocket = new ServerSocket(port, 4, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            System.err.println("[viewer-feed] not listening on " + port + ": " + e.getMessage());
            return false;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "viewer-feed");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(), StandardCharsets.UTF_8));
                writer.write(header());
                writer.newLine();
                writer.flush();
                clients.add(new Client(socket, writer));
            } catch (IOException e) {
                if (running) {
                    // A refused or torn connection is routine; the viewer retries.
                    continue;
                }
                return;
            }
        }
    }

    private String header() {
        StringBuilder header = new StringBuilder("timestampMillis");
        for (String id : axisIds) {
            header.append(",pos_").append(id).append(",vel_").append(id);
        }
        return header.append(",estopLatched,deadmanHeld,watchdogTripped,alarms").toString();
    }

    /**
     * Called on the control-loop thread for every published state. Anything that
     * goes wrong here is the viewer's problem, never the crane's, so a failed write
     * simply drops that client.
     */
    @Override
    public void accept(CraneState state) {
        if (clients.isEmpty()) {
            return;
        }
        String row = row(state);
        for (Client client : clients) {
            try {
                client.writer().write(row);
                client.writer().newLine();
                client.writer().flush();
            } catch (IOException e) {
                clients.remove(client);
                closeQuietly(client.socket());
            }
        }
    }

    private String row(CraneState state) {
        StringBuilder row = new StringBuilder();
        row.append(state.timestampMillis());
        for (String id : axisIds) {
            row.append(',').append(num(state.position(id)))
               .append(',').append(num(state.velocity(id)));
        }
        row.append(',').append(state.estopLatched())
           .append(',').append(state.deadmanHeld())
           .append(',').append(state.watchdogTripped())
           .append(',').append('"').append(String.join(";", state.activeAlarms())).append('"');
        return row.toString();
    }

    private static String num(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** True while at least one viewer is attached — for the cockpit's status line. */
    public boolean hasViewer() {
        return !clients.isEmpty();
    }

    public CraneProfile profile() {
        return profile;
    }

    @Override
    public void close() {
        running = false;
        clients.forEach(client -> closeQuietly(client.socket()));
        clients.clear();
        closeQuietly(serverSocket);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception e) {
            // Shutting down; nothing useful left to do about it.
        }
    }
}
