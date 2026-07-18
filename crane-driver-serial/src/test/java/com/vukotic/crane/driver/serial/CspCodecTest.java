package com.vukotic.crane.driver.serial;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CspCodecTest {

    // ---- the exact byte examples documented in docs/PROTOCOL.md ----

    @Test
    void encodesTheDocumentedHelloLine() {
        assertEquals("HELLO *62", CspCodec.encodeHello());
    }

    @Test
    void encodesTheDocumentedHiLine() {
        assertEquals("HI KB5 slew,boom,jib,extension,winch *7C",
                CspCodec.encodeHi("KB5", List.of("slew", "boom", "jib", "extension", "winch")));
    }

    @Test
    void encodesTheDocumentedDemandsLine() {
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("slew", 0.5);
        demands.put("boom", -0.25);
        demands.put("winch", 0.0);
        assertEquals("D 00042 slew:0.500 boom:-0.250 winch:0.000 *10",
                CspCodec.encodeDemands(42, demands));
    }

    @Test
    void encodesTheDocumentedTelemetryLine() {
        Map<String, CspCodec.AxisTelemetry> axes = new LinkedHashMap<>();
        axes.put("slew", new CspCodec.AxisTelemetry(12.5, 1.2));
        axes.put("boom", new CspCodec.AxisTelemetry(45.0, 0.0));
        assertEquals("T 00042 slew:12.50,1.20 boom:45.00,0.00 *64",
                CspCodec.encodeTelemetry(42, axes));
    }

    // ---- roundtrips ----

    @Test
    void demandsRoundtrip() {
        Map<String, Double> demands = new LinkedHashMap<>();
        demands.put("slew", -1.0);
        demands.put("boom", 0.125);
        String line = CspCodec.encodeDemands(99_999, demands);
        CspCodec.Demands parsed = CspCodec.parseDemands(line).orElseThrow();
        assertEquals(99_999, parsed.sequence());
        assertEquals(-1.0, parsed.axisDemands().get("slew"));
        assertEquals(0.125, parsed.axisDemands().get("boom"));
    }

    @Test
    void telemetryRoundtrip() {
        Map<String, CspCodec.AxisTelemetry> axes = new LinkedHashMap<>();
        axes.put("winch", new CspCodec.AxisTelemetry(-3.25, 0.75));
        CspCodec.Telemetry parsed =
                CspCodec.parseTelemetry(CspCodec.encodeTelemetry(7, axes)).orElseThrow();
        assertEquals(7, parsed.sequence());
        assertEquals(-3.25, parsed.axes().get("winch").position());
        assertEquals(0.75, parsed.axes().get("winch").velocity());
    }

    @Test
    void hiRoundtripToleratesCrLf() {
        String line = CspCodec.encodeHi("KB5", List.of("slew", "boom")) + "\r\n";
        CspCodec.Hi hi = CspCodec.parseHi(line).orElseThrow();
        assertEquals("KB5", hi.craneName());
        assertEquals(List.of("slew", "boom"), hi.axisIds());
    }

    @Test
    void sequenceWraps() {
        assertTrue(CspCodec.encodeDemands(100_042, Map.of("slew", 0.0)).startsWith("D 00042 "));
    }

    @Test
    void negativeZeroDemandNormalizes() {
        assertTrue(CspCodec.encodeDemands(0, Map.of("slew", -0.0001)).contains("slew:0.000"));
    }

    // ---- rejection of bad input ----

    @Test
    void rejectsCorruptedChecksum() {
        assertEquals(Optional.empty(), CspCodec.parseDemands("D 00042 slew:0.500 *FF"));
    }

    @Test
    void rejectsMissingChecksum() {
        assertEquals(Optional.empty(), CspCodec.parseDemands("D 00042 slew:0.500"));
    }

    @Test
    void rejectsOutOfRangeDemand() {
        String line = CspCodec.frame("D 00042 slew:1.500");
        assertEquals(Optional.empty(), CspCodec.parseDemands(line));
    }

    @Test
    void rejectsMalformedSequence() {
        assertEquals(Optional.empty(), CspCodec.parseDemands(CspCodec.frame("D 42 slew:0.500")));
    }

    @Test
    void rejectsOverlongLine() {
        StringBuilder body = new StringBuilder("T 00001");
        for (int i = 0; i < 40; i++) {
            body.append(" axis").append(i).append(":1.00,0.00");
        }
        String noChecksum = body + " *00"; // bypass frame()'s own length guard
        assertTrue(noChecksum.length() > CspCodec.MAX_LINE_LENGTH);
        assertEquals(Optional.empty(), CspCodec.parseTelemetry(noChecksum));
    }

    @Test
    void rejectsNonPrintableBytes() {
        assertEquals(Optional.empty(), CspCodec.unframe("D 00042 slew:0.500 *2A"));
    }

    @Test
    void rejectsEmptyAndNull() {
        assertEquals(Optional.empty(), CspCodec.unframe(""));
        assertEquals(Optional.empty(), CspCodec.unframe(null));
        assertEquals(Optional.empty(), CspCodec.parseTelemetry("garbage"));
    }
}
