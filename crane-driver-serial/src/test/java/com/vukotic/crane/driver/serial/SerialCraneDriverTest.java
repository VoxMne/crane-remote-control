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

    private static FakeLink linkAnsweringHello(List<String> axes) {
        FakeLink link = new FakeLink();
        link.respondWith(line -> CspCodec.unframe(line).map(body -> body.equals("HELLO")
                ? List.of(CspCodec.encodeHi("KB5", axes))
                : List.<String>of()).orElse(List.of()));
        return link;
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
    void telemetryUpdatesStateAndMissingAxesKeepPrevious() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        Map<String, CspCodec.AxisTelemetry> first = new LinkedHashMap<>();
        first.put("slew", new CspCodec.AxisTelemetry(12.5, 1.2));
        first.put("boom", new CspCodec.AxisTelemetry(45.0, 0.0));
        link.pushToHost(CspCodec.encodeTelemetry(1, first));
        await("first telemetry", () -> driver.readState().axisPositions().get("slew") == 12.5);
        assertEquals(45.0, driver.readState().axisPositions().get("boom"));

        // Second line carries only slew: boom must keep its previous value.
        link.pushToHost(CspCodec.encodeTelemetry(2,
                Map.of("slew", new CspCodec.AxisTelemetry(20.0, 0.5))));
        await("second telemetry", () -> driver.readState().axisPositions().get("slew") == 20.0);
        DriverState state = driver.readState();
        assertEquals(45.0, state.axisPositions().get("boom"), "missing axis keeps previous");
        assertTrue(driver.millisSinceLastTelemetry() < 2_000);
        driver.disconnect();
    }

    @Test
    void corruptedTelemetryIsSkippedWithoutBreakingSubsequentLines() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        link.pushToHost("T 00003 slew:99.99,0.00 *FF"); // bad checksum
        link.pushToHost(CspCodec.encodeTelemetry(4,
                Map.of("slew", new CspCodec.AxisTelemetry(7.0, 0.0))));
        await("good line after corrupt one",
                () -> driver.readState().axisPositions().get("slew") == 7.0);
        assertEquals(1, driver.droppedLineCount());
        driver.disconnect();
    }

    @Test
    void unknownTelemetryAxesAreIgnored() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);

        Map<String, CspCodec.AxisTelemetry> axes = new LinkedHashMap<>();
        axes.put("slew", new CspCodec.AxisTelemetry(5.0, 0.0));
        axes.put("grappleRotator", new CspCodec.AxisTelemetry(1.0, 0.0));
        link.pushToHost(CspCodec.encodeTelemetry(5, axes));
        await("telemetry", () -> driver.readState().axisPositions().get("slew") == 5.0);
        assertFalse(driver.readState().axisPositions().containsKey("grappleRotator"));
        driver.disconnect();
    }

    @Test
    void sendDemandsWritesWellFormedSequencedLines() {
        FakeLink link = linkAnsweringHello(ALL_AXES);
        SerialCraneDriver driver = new SerialCraneDriver(link, "Serial (COM7)");
        driver.connect(profile);
        // Feedback first: without it the driver fails closed and sends zeros.
        link.pushToHost(CspCodec.encodeTelemetry(1,
                Map.of("slew", new CspCodec.AxisTelemetry(0.0, 0.0))));
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
        link.pushToHost(CspCodec.encodeTelemetry(1,
                Map.of("slew", new CspCodec.AxisTelemetry(0.0, 0.0))));
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
