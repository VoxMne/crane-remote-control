package com.vukotic.crane.viewer;

import com.vukotic.crane.core.model.CraneState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads crane state from the cockpit process over a loopback socket.
 *
 * <p><b>One direction only.</b> This class opens the socket, reads lines, and never
 * writes. That is the whole point of running the visualiser as its own process:
 * however the renderer misbehaves — a driver hang, a shader fault, an out-of-memory
 * — it has no channel through which to affect the machine. Compare the in-process
 * 3D view, where a stalled render thread had to be defended against with a UI
 * heartbeat because it shared a JVM with the command path.
 *
 * <p>The wire format is deliberately the telemetry CSV from
 * {@code TelemetryCsvLogger}: a header line naming the axes, then one row per
 * published state. Nothing new to specify, a recording and a live feed are the
 * same bytes, and you can watch it with {@code telnet localhost 7420}.
 *
 * <p>Connection is best-effort and self-healing. The viewer is not allowed to be a
 * reason the cockpit cannot start, so a missing server is simply "not connected
 * yet" and it keeps retrying.
 */
public final class StateFeed implements AutoCloseable {

    /** Loopback only. The feed must not be reachable from another machine. */
    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7420;

    private static final long RETRY_MILLIS = 1_000;
    private static final int CONNECT_TIMEOUT_MILLIS = 500;

    private final int port;
    private final AtomicReference<CraneState> latest = new AtomicReference<>();
    private final AtomicReference<List<String>> axisIds = new AtomicReference<>(List.of());
    private volatile boolean running = true;
    private volatile boolean connected;
    private Thread thread;

    public StateFeed(int port) {
        this.port = port;
    }

    public void start() {
        thread = new Thread(this::readLoop, "state-feed");
        thread.setDaemon(true);
        thread.start();
    }

    /** Most recent state, or null before the first frame arrives. */
    public CraneState latest() {
        return latest.get();
    }

    /** Axis ids named by the feed's header, in order. */
    public List<String> axisIds() {
        return axisIds.get();
    }

    public boolean isConnected() {
        return connected;
    }

    @Override
    public void close() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void readLoop() {
        while (running) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MILLIS);
                connected = true;
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                String header = reader.readLine();
                if (header == null) {
                    continue;
                }
                List<String> ids = parseHeader(header);
                axisIds.set(ids);
                String line;
                while (running && (line = reader.readLine()) != null) {
                    parseRow(line, ids).ifPresent(latest::set);
                }
            } catch (IOException e) {
                // Cockpit not up yet, or the socket died. Neither is exceptional.
            } finally {
                connected = false;
            }
            sleepBeforeRetry();
        }
    }

    private void sleepBeforeRetry() {
        if (!running) {
            return;
        }
        try {
            Thread.sleep(RETRY_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private static List<String> parseHeader(String header) {
        List<String> ids = new ArrayList<>();
        for (String column : header.split(",")) {
            if (column.startsWith("pos_")) {
                ids.add(column.substring("pos_".length()));
            }
        }
        return List.copyOf(ids);
    }

    /** Same row shape as a telemetry CSV; a malformed row is skipped, not fatal. */
    private static java.util.Optional<CraneState> parseRow(String line, List<String> axisIds) {
        String[] fields = line.split(",");
        if (fields.length < 1 + axisIds.size() * 2 + 4) {
            return java.util.Optional.empty();
        }
        try {
            long timestamp = Long.parseLong(fields[0].trim());
            Map<String, Double> positions = new LinkedHashMap<>();
            Map<String, Double> velocities = new LinkedHashMap<>();
            int index = 1;
            for (String axisId : axisIds) {
                positions.put(axisId, Double.parseDouble(fields[index++]));
                velocities.put(axisId, Double.parseDouble(fields[index++]));
            }
            boolean estop = Boolean.parseBoolean(fields[index++]);
            boolean deadman = Boolean.parseBoolean(fields[index++]);
            boolean watchdog = Boolean.parseBoolean(fields[index++]);
            String alarmField = fields[index].replace("\"", "").trim();
            List<String> alarms = alarmField.isEmpty()
                    ? List.of() : Arrays.asList(alarmField.split(";"));
            return java.util.Optional.of(new CraneState(timestamp, positions, velocities,
                    estop, deadman, watchdog, alarms));
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
    }
}
