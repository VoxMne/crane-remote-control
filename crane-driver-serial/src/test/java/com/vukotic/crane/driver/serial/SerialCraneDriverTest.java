package com.vukotic.crane.driver.serial;

import com.vukotic.crane.core.driver.DriverState;
import com.vukotic.crane.core.model.CraneProfile;
import com.vukotic.crane.core.model.CraneProfiles;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full CSP/1 conversations against a scripted in-memory fake crane. */
class SerialCraneDriverTest {

    private static final List<String> ALL_AXES =
            List.of("slew", "boom", "jib", "extension", "winch");

    private final CraneProfile profile = CraneProfiles.demoKnuckleBoom();

    /** A CSP/1.1 crane declaring generous travel on each of the named axes. */
    private static FakeLink linkAnsweringHello(List<String> axes) {
        Map<String, CspCodec.AxisLimits> limits = new LinkedHashMap<>();
        axes.forEach(id -> limits.put(id, new CspCodec.AxisLimits(-1_000, 1_000)));
        return linkAnsweringHello(limits);
    }

    private static FakeLink linkAnsweringHello(Map<String, CspCodec.AxisLimits> limits) {
        FakeLink link = new FakeLink();
        link.respondWith(line -> CspCodec.unframe(line).map(body -> body.equals("HELLO")
                ? List.of(CspCodec.encodeHi("KB5", limits))
                : List.<String>of()).orElse(List.of()));
        return link;
    }

    /** A CSP/1.0 crane: names its axes, declares no travel. */
    private static FakeLink linkAnsweringHelloWithoutLimits(List<String> axes) {
        FakeLink link = new FakeLink();
        link.respondWith(line -> CspCodec.unframe(line).map(body -> body.equals("HELLO")
                ? List.of(CspCodec.encodeHi("KB5", axes))
                : List.<String>of()).orElse(List.of()));
        return link;
    }

    /** A complete telemetry frame: every profile axis, as the driver now requires. */
    private String completeFrame(int sequence, double slew) {
        Map<String, CspCodec.AxisTelemetry> axes = new LinkedHashMap<>();
        for (String id : ALL_AXES) {
            axes.put(id, new CspCodec.AxisTelemetry(id.equals("slew") ? slew : 0.0, 0.0));
        }
        return CspCodec.encodeTelemetry(sequence, axes);
    }

    /** Polls a condition for up to two seconds (reader thread is asynchronous). */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void connectsAfterHandshakeAndReportsPortName() {
        SerialCraneDriver driver = new SerialCraneDriver(linkAnsweringHello(ALL_AXES), "Serial (COM7)");
        driver.connect(profile);
        assertTrue(driver.isConnected());
        assertEquals("Serial (COM7)", driver.name());
        driver.disconnect();
    }

    @Test
    void connectFailsWhenCraneLacksAProfileAxis() {
        FakeLink link = linkAnsweringHello(List.of("slew", "boom", "jib", "extension")); // no winch
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        SerialLinkException e = assertThrows(SerialLinkException.class,
                () -> driver.connect(profile));
        assertTrue(e.getMessage().contains("winch"), e.getMessage());
        assertFalse(link.isOpen(), "port must be closed after a failed connect");
    }

    @Test
    void connectFailsWithoutAnyHiReply() {
        SerialCraneDriver driver = new SerialCraneDriver(new FakeLink(), "Serial (COM7)");
        assertThrows(SerialLinkException.class, () -> driver.connect(profile));
    }

    @Test
    void aCompleteFrameUpdatesEveryAxis() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost(completeFrame(1, 12.5));
        await("first telemetry", () -> driver.readState().axisPositions().get("slew") == 12.5);
        assertTrue(driver.isTelemetryFresh());
        assertTrue(driver.millisSinceLastTelemetry() < 2_000);
        driver.disconnect();
    }

    @Test
    void aPartialFrameIsNotPositionFeedback() {
        // This used to refresh freshness and let the missing axes keep old values,
        // so motion was permitted while the position limits guarded stale numbers.
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost(completeFrame(1, 12.5));
        await("complete frame", driver::isTelemetryFresh);

        link.pushToHost(CspCodec.encodeTelemetry(2,
                Map.of("slew", new CspCodec.AxisTelemetry(20.0, 0.5))));
        await("partial frame counted as dropped", () -> driver.droppedLineCount() >= 1);
        assertEquals(12.5, driver.readState().axisPositions().get("slew"),
                "a partial frame must not move the reported position");
        driver.disconnect();
    }

    @Test
    void aRepeatedSequenceIsNotLiveness() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost(completeFrame(7, 1.0));
        await("first frame", driver::isTelemetryFresh);

        // Same sequence replayed forever: alive-looking, but says nothing new.
        link.pushToHost(completeFrame(7, 99.0));
        await("replayed frame rejected", () -> driver.droppedLineCount() >= 1);
        assertEquals(1.0, driver.readState().axisPositions().get("slew"));
        driver.disconnect();
    }

    @Test
    void corruptedTelemetryIsSkippedWithoutBreakingSubsequentLines() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost("T 00003 slew:99.99,0.00 *FF"); // bad checksum
        link.pushToHost(completeFrame(4, 7.0));
        await("good line after corrupt one",
                () -> driver.readState().axisPositions().get("slew") == 7.0);
        assertTrue(driver.droppedLineCount() >= 1);
        driver.disconnect();
    }

    @Test
    void connectRefusesACraneThatDeclaresNoTravel() {
        FakeLink link = linkAnsweringHelloWithoutLimits(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        SerialLinkException e = assertThrows(SerialLinkException.class,
                () -> driver.connect(profile));
        assertTrue(e.getMessage().contains("CSP/1.0"), e.getMessage());
        assertFalse(link.isOpen(), "port must be closed after a failed connect");
    }

    @Test
    void connectRefusesAProfileWiderThanTheCrane() {
        // The bundled Demo and Heavy profiles share all five axis ids; only the
        // declared travel can tell them apart, and getting it wrong drives a small
        // crane past its stops.
        Map<String, CspCodec.AxisLimits> small = new LinkedHashMap<>();
        ALL_AXES.forEach(id -> small.put(id, new CspCodec.AxisLimits(-1, 1)));
        SerialCraneDriver driver =
                new SerialCraneDriver(linkAnsweringHello(small), "Serial (COM7)");

        SerialLinkException e = assertThrows(SerialLinkException.class,
                () -> driver.connect(profile));
        assertTrue(e.getMessage().contains("wrong profile"), e.getMessage());
    }

    @Test
    void unknownTelemetryAxesAreIgnored() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        Map<String, CspCodec.AxisTelemetry> axes = new LinkedHashMap<>();
        for (String id : ALL_AXES) {
            axes.put(id, new CspCodec.AxisTelemetry(id.equals("slew") ? 5.0 : 0.0, 0.0));
        }
        axes.put("grappleRotator", new CspCodec.AxisTelemetry(1.0, 0.0));
        link.pushToHost(CspCodec.encodeTelemetry(5, axes));
        await("telemetry", () -> driver.readState().axisPositions().get("slew") == 5.0);
        assertFalse(driver.readState().axisPositions().containsKey("grappleRotator"));
        driver.disconnect();
    }

    @Test
    void aFrameOfOnlyUnknownAxesIsNotFeedback() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost(CspCodec.encodeTelemetry(9,
                Map.of("grappleRotator", new CspCodec.AxisTelemetry(1.0, 0.0))));
        await("rejected", () -> driver.droppedLineCount() >= 1);
        assertFalse(driver.isTelemetryFresh(),
                "axes this profile never heard of are not position feedback");
        assertFalse(driver.acceptsMotion());
        driver.disconnect();
    }

    @Test
    void sendDemandsWritesWellFormedSequencedLines() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);
        // Feedback first: without it the driver fails closed and sends zeros.
        link.pushToHost(completeFrame(1, 0.0));
        await("telemetry", driver::isTelemetryFresh);
        int before = link.written.size(); // HELLO line(s)

        driver.sendDemands(Map.of("slew", 0.5));
        driver.sendDemands(Map.of("slew", 0.25));

        List<String> demandLines = link.written.subList(before, link.written.size());
        assertEquals(2, demandLines.size());
        CspCodec.Demands first = CspCodec.parseDemands(demandLines.get(0)).orElseThrow();
        CspCodec.Demands second = CspCodec.parseDemands(demandLines.get(1)).orElseThrow();
        assertEquals(0.5, first.axisDemands().get("slew"));
        assertEquals(first.sequence() + 1, second.sequence());
        driver.disconnect();
    }

    @Test
    void withoutTelemetryTheDriverSendsZerosRatherThanTheRequestedDemand() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);          // handshake only: no T line has arrived
        int before = link.written.size();

        driver.sendDemands(Map.of("slew", 1.0));

        CspCodec.Demands sent = CspCodec.parseDemands(link.written.get(before)).orElseThrow();
        assertEquals(0.0, sent.axisDemands().get("slew"),
                "no position feedback means no motion: the host must fail closed");
        assertFalse(driver.isTelemetryFresh());
        driver.disconnect();
    }

    @Test
    void freshTelemetryRestoresNormalCommanding() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);
        link.pushToHost(completeFrame(1, 0.0));
        await("telemetry", driver::isTelemetryFresh);

        int before = link.written.size();
        driver.sendDemands(Map.of("slew", 1.0));
        CspCodec.Demands sent = CspCodec.parseDemands(link.written.get(before)).orElseThrow();
        assertEquals(1.0, sent.axisDemands().get("slew"));
        driver.disconnect();
    }

    @Test
    void disconnectStopsTheReaderThreadAndClosesTheLink() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);
        Thread reader = driver.readerThread();
        assertTrue(reader.isAlive());

        driver.disconnect();
        await("reader thread exit", () -> !reader.isAlive());
        assertFalse(link.isOpen());
        assertFalse(driver.isConnected());
        assertThrows(IllegalStateException.class, driver::readState);
    }
}
