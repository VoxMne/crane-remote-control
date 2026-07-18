package com.vukotic.crane.driver.serial;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * In-memory scripted {@link SerialLink}: the test (or a responder function playing
 * "firmware") pushes crane→host lines into a queue; host→crane lines are recorded
 * and optionally answered by the responder.
 */
final class FakeLink implements SerialLink {

    private final BlockingQueue<String> toHost = new LinkedBlockingQueue<>();
    final List<String> written = new CopyOnWriteArrayList<>();

    /** Plays the firmware: maps each written host line to reply lines (may be empty). */
    private volatile Function<String, List<String>> responder = line -> List.of();
    private volatile boolean open;

    void respondWith(Function<String, List<String>> responder) {
        this.responder = responder;
    }

    /** Queues a spontaneous crane→host line (telemetry push). */
    void pushToHost(String line) {
        toHost.add(line);
    }

    @Override
    public void open() {
        open = true;
    }

    @Override
    public void close() {
        open = false;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public String readLine(long timeoutMillis) {
        if (!open) {
            throw new SerialLinkException("fake link closed");
        }
        try {
            return toHost.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void writeLine(String line) {
        if (!open) {
            throw new SerialLinkException("fake link closed");
        }
        written.add(line);
        responder.apply(line).forEach(toHost::add);
    }
}
