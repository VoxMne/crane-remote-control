package com.vukotic.crane.driver.serial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Pure encoder/decoder for the Crane Serial Protocol v1 (CSP/1), docs/PROTOCOL.md.
 *
 * <p>Every line is {@code <body> *XX} where {@code XX} is the two-hex-digit XOR of all
 * characters before the {@code *} (including the separating space). All {@code parse*}
 * methods are total: any malformed, corrupted, overlong, or out-of-range input yields
 * {@link Optional#empty()} — nothing here ever throws on received data.
 */
public final class CspCodec {

    /** Maximum line length in characters, excluding CR/LF (PROTOCOL.md §1). */
    public static final int MAX_LINE_LENGTH = 240;

    /** Sequence numbers occupy 5 decimal digits: 00000–99999, then wrap. */
    public static final int SEQUENCE_MODULUS = 100_000;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");

    private CspCodec() {
    }

    /** A parsed {@code D} line: sequence number + normalized demand per axis id. */
    public record Demands(int sequence, Map<String, Double> axisDemands) {
        public Demands {
            axisDemands = Map.copyOf(axisDemands);
        }
    }

    /** One axis sample from a {@code T} line, in the profile's physical units. */
    public record AxisTelemetry(double position, double velocity) {
    }

    /** A parsed {@code T} line: echoed sequence number + samples per axis id. */
    public record Telemetry(int sequence, Map<String, AxisTelemetry> axes) {
        public Telemetry {
            axes = Map.copyOf(axes);
        }
    }

    /** The travel a crane declares for one axis, in that axis's physical unit. */
    public record AxisLimits(double minPosition, double maxPosition) {
        public AxisLimits {
            if (!Double.isFinite(minPosition) || !Double.isFinite(maxPosition)
                    || minPosition >= maxPosition) {
                throw new IllegalArgumentException(
                        "limits must be finite with min < max: " + minPosition + ".." + maxPosition);
            }
        }

        /** True when {@code [min, max]} lies inside this declared travel. */
        public boolean contains(double min, double max) {
            return min >= minPosition - 1e-9 && max <= maxPosition + 1e-9;
        }
    }

    /**
     * A parsed {@code HI} line: crane name, the axis ids the crane can drive, and
     * — since CSP/1.1 — the travel it declares for each of them.
     *
     * <p>The limits matter because CSP puts position-limit enforcement on the host.
     * A crane that only names its axes cannot tell the host whether the profile it
     * loaded describes this machine or a bigger one, and the bundled Demo and Heavy
     * profiles expose exactly the same five axis ids with very different travel.
     * {@code limits} empty means a CSP/1.0 crane that did not declare any.
     */
    public record Hi(String craneName, List<String> axisIds, Map<String, AxisLimits> limits) {
        public Hi {
            axisIds = List.copyOf(axisIds);
            limits = Map.copyOf(limits);
        }

        /** A CSP/1.0 reply: axis names only, no declared travel. */
        public Hi(String craneName, List<String> axisIds) {
            this(craneName, axisIds, Map.of());
        }

        public boolean declaresLimits() {
            return !limits.isEmpty();
        }
    }

    // ---------------------------------------------------------------- checksum/frame

    /** A limit value on the wire: two decimals, Locale.ROOT so the point is a point. */
    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /** XOR of every character in {@code payload}, as two uppercase hex digits. */
    public static String checksum(String payload) {
        int x = 0;
        for (int i = 0; i < payload.length(); i++) {
            x ^= payload.charAt(i);
        }
        return String.format(Locale.ROOT, "%02X", x);
    }

    /**
     * Frames a message body as a full CSP/1 line: {@code <body> *XX}.
     *
     * @throws IllegalArgumentException if the framed line would exceed {@link #MAX_LINE_LENGTH}
     */
    public static String frame(String body) {
        String payload = body + " ";
        String line = payload + "*" + checksum(payload);
        if (line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException(
                    "CSP/1 line exceeds " + MAX_LINE_LENGTH + " chars: " + line.length());
        }
        return line;
    }

    /**
     * Validates framing + checksum of a received line and returns the message body
     * (checksum and its separating space stripped), or empty if the line is null,
     * empty, overlong, non-printable, unterminated by {@code *XX}, or corrupt.
     */
    public static Optional<String> unframe(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        String line = rawLine;
        while (line.endsWith("\n") || line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty() || line.length() > MAX_LINE_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return Optional.empty();
            }
        }
        int star = line.lastIndexOf('*');
        if (star < 1 || star != line.length() - 3) {
            return Optional.empty();
        }
        String payload = line.substring(0, star);
        String declared = line.substring(star + 1);
        if (!declared.equalsIgnoreCase(checksum(payload))) {
            return Optional.empty();
        }
        String body = payload.endsWith(" ") ? payload.substring(0, payload.length() - 1) : payload;
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(body);
    }

    // ------------------------------------------------------------------------ encode

    /** The session-open line the host sends: {@code HELLO *62}. */
    public static String encodeHello() {
        return frame("HELLO");
    }

    /** A CSP/1.0 session-accept line: axis names only, no declared travel. */
    public static String encodeHi(String craneName, List<String> axisIds) {
        requireToken(craneName, "crane name");
        if (axisIds == null || axisIds.isEmpty()) {
            throw new IllegalArgumentException("axisIds must not be empty");
        }
        axisIds.forEach(id -> requireToken(id, "axis id"));
        return frame("HI " + craneName + " " + String.join(",", axisIds));
    }

    /**
     * A CSP/1.1 session-accept line: each axis with the travel the crane declares.
     * Used by tests and crane emulators; real firmware sends this so the host can
     * prove the loaded profile describes the machine actually on the other end.
     */
    public static String encodeHi(String craneName, Map<String, AxisLimits> limits) {
        requireToken(craneName, "crane name");
        if (limits == null || limits.isEmpty()) {
            throw new IllegalArgumentException("limits must not be empty");
        }
        StringBuilder body = new StringBuilder("HI ").append(craneName).append(' ');
        boolean first = true;
        for (Map.Entry<String, AxisLimits> entry : limits.entrySet()) {
            requireToken(entry.getKey(), "axis id");
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append(entry.getKey())
                    .append(':').append(decimal(entry.getValue().minPosition()))
                    .append(':').append(decimal(entry.getValue().maxPosition()));
        }
        return frame(body.toString());
    }

    /**
     * Encodes a {@code D} demands line. The sequence is reduced modulo
     * {@link #SEQUENCE_MODULUS}; demands are defensively sanitized (non-finite becomes
     * 0.0, everything clamped to [-1, +1]) and formatted with exactly 3 decimals. Axis
     * pairs appear in the iteration order of {@code axisDemands}.
     */
    public static String encodeDemands(int sequence, Map<String, Double> axisDemands) {
        Objects.requireNonNull(axisDemands, "axisDemands");
        StringBuilder body = new StringBuilder("D ").append(formatSequence(sequence));
        for (Map.Entry<String, Double> entry : axisDemands.entrySet()) {
            requireToken(entry.getKey(), "axis id");
            double demand = entry.getValue() == null ? 0.0 : entry.getValue();
            if (!Double.isFinite(demand)) {
                demand = 0.0;
            }
            demand = Math.clamp(demand, -1.0, 1.0);
            String formatted = String.format(Locale.ROOT, "%.3f", demand);
            if (formatted.equals("-0.000")) {
                formatted = "0.000";
            }
            body.append(' ').append(entry.getKey()).append(':').append(formatted);
        }
        return frame(body.toString());
    }

    /**
     * Encodes a {@code T} telemetry line (used by tests and crane emulators; real
     * telemetry comes from firmware). Two decimals, physical units, iteration order.
     */
    public static String encodeTelemetry(int sequence, Map<String, AxisTelemetry> axes) {
        Objects.requireNonNull(axes, "axes");
        StringBuilder body = new StringBuilder("T ").append(formatSequence(sequence));
        for (Map.Entry<String, AxisTelemetry> entry : axes.entrySet()) {
            requireToken(entry.getKey(), "axis id");
            body.append(' ').append(entry.getKey()).append(':')
                    .append(String.format(Locale.ROOT, "%.2f", entry.getValue().position()))
                    .append(',')
                    .append(String.format(Locale.ROOT, "%.2f", entry.getValue().velocity()));
        }
        return frame(body.toString());
    }

    // ------------------------------------------------------------------------- parse

    /** Parses a {@code D} line; empty on any framing, checksum, or field violation. */
    public static Optional<Demands> parseDemands(String line) {
        String[] tokens = tokens(line, "D");
        if (tokens == null) {
            return Optional.empty();
        }
        Optional<Integer> sequence = parseSequence(tokens[1]);
        if (sequence.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Double> demands = new LinkedHashMap<>();
        for (int i = 2; i < tokens.length; i++) {
            String[] pair = splitPair(tokens[i]);
            if (pair == null || !DECIMAL.matcher(pair[1]).matches()) {
                return Optional.empty();
            }
            double demand = Double.parseDouble(pair[1]);
            if (demand < -1.0 || demand > 1.0) {
                return Optional.empty();
            }
            demands.put(pair[0], demand);
        }
        return Optional.of(new Demands(sequence.get(), demands));
    }

    /** Parses a {@code T} line; empty on any framing, checksum, or field violation. */
    public static Optional<Telemetry> parseTelemetry(String line) {
        String[] tokens = tokens(line, "T");
        if (tokens == null) {
            return Optional.empty();
        }
        Optional<Integer> sequence = parseSequence(tokens[1]);
        if (sequence.isEmpty()) {
            return Optional.empty();
        }
        Map<String, AxisTelemetry> axes = new LinkedHashMap<>();
        for (int i = 2; i < tokens.length; i++) {
            String[] pair = splitPair(tokens[i]);
            if (pair == null) {
                return Optional.empty();
            }
            String[] values = pair[1].split(",", -1);
            if (values.length != 2
                    || !DECIMAL.matcher(values[0]).matches()
                    || !DECIMAL.matcher(values[1]).matches()) {
                return Optional.empty();
            }
            axes.put(pair[0], new AxisTelemetry(
                    Double.parseDouble(values[0]), Double.parseDouble(values[1])));
        }
        return Optional.of(new Telemetry(sequence.get(), axes));
    }

    /**
     * Parses a {@code HI} line; empty on any framing, checksum, or field violation.
     *
     * <p>Accepts both forms. CSP/1.0: {@code HI <name> slew,boom,winch}. CSP/1.1:
     * {@code HI <name> slew:-180:180,boom:-5:75,winch:0:20} — the same list with
     * each axis's declared travel appended. Mixing the two forms in one line is
     * rejected rather than guessed at.
     */
    public static Optional<Hi> parseHi(String line) {
        Optional<String> body = unframe(line);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        String[] tokens = body.get().split(" ", -1);
        if (tokens.length != 3 || !tokens[0].equals("HI") || !TOKEN.matcher(tokens[1]).matches()) {
            return Optional.empty();
        }
        String[] entries = tokens[2].split(",", -1);
        List<String> ids = new java.util.ArrayList<>(entries.length);
        Map<String, AxisLimits> limits = new java.util.LinkedHashMap<>();
        for (String entry : entries) {
            String[] parts = entry.split(":", -1);
            if (!TOKEN.matcher(parts[0]).matches()) {
                return Optional.empty();
            }
            ids.add(parts[0]);
            if (parts.length == 1) {
                continue;                       // CSP/1.0 form: no declared travel
            }
            if (parts.length != 3
                    || !DECIMAL.matcher(parts[1]).matches()
                    || !DECIMAL.matcher(parts[2]).matches()) {
                return Optional.empty();
            }
            try {
                limits.put(parts[0], new AxisLimits(
                        Double.parseDouble(parts[1]), Double.parseDouble(parts[2])));
            } catch (IllegalArgumentException e) {
                return Optional.empty();        // min >= max, or non-finite
            }
        }
        if (!limits.isEmpty() && limits.size() != ids.size()) {
            return Optional.empty();            // half-declared travel is not a contract
        }
        return Optional.of(new Hi(tokens[1], ids, limits));
    }

    // ----------------------------------------------------------------------- helpers

    /** Unframes + splits; returns null unless the first token matches {@code type}. */
    private static String[] tokens(String line, String type) {
        Optional<String> body = unframe(line);
        if (body.isEmpty()) {
            return null;
        }
        String[] tokens = body.get().split(" ", -1);
        if (tokens.length < 2 || !tokens[0].equals(type)) {
            return null;
        }
        for (String token : tokens) {
            if (token.isEmpty()) { // double space or trailing space in body
                return null;
            }
        }
        return tokens;
    }

    private static Optional<Integer> parseSequence(String token) {
        if (token.length() != 5) {
            return Optional.empty();
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
        }
        return Optional.of(Integer.parseInt(token));
    }

    /** Splits {@code axis:value}; null if malformed or the axis id is not a token. */
    private static String[] splitPair(String token) {
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return null;
        }
        String axis = token.substring(0, colon);
        if (!TOKEN.matcher(axis).matches()) {
            return null;
        }
        return new String[] {axis, token.substring(colon + 1)};
    }

    private static String formatSequence(int sequence) {
        return String.format(Locale.ROOT, "%05d", Math.floorMod(sequence, SEQUENCE_MODULUS));
    }

    private static void requireToken(String value, String what) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " must match [A-Za-z0-9_-]+: '" + value + "'");
        }
    }
}
