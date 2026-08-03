package com.vukotic.crane.ui.sound;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneState;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.Random;

/**
 * Fully synthesized cockpit audio over {@code javax.sound.sampled} — no audio
 * asset files, no external libraries. A background daemon thread renders
 * 44.1 kHz mono 16-bit PCM into a {@link SourceDataLine} in small blocks (the
 * blocking {@code write} paces the loop). Three layers are mixed:
 *
 * <ul>
 *   <li><b>Hydraulic hum</b> — sawtooth/noise blend through a one-pole low-pass;
 *       volume, oscillator pitch and filter cutoff all scale with the total
 *       absolute axis demand. The control signal is itself low-pass smoothed
 *       (~120 ms) so demand steps swell instead of zippering.</li>
 *   <li><b>Motion beeper</b> — a repeating short beep (reversing-vehicle style)
 *       while any axis demand magnitude exceeds {@link #MOTION_THRESHOLD}.</li>
 *   <li><b>Alarm buzzer</b> — an alternating two-tone whenever the E-STOP is
 *       latched or the watchdog has tripped. While it sounds, hum and beeper
 *       are gated out so the alarm is unmistakable.</li>
 * </ul>
 *
 * <p>The FX thread only writes volatile targets via {@link #update}; all DSP
 * state lives on the audio thread. If no audio device is available the engine
 * constructs silently disabled — it never throws out of the constructor and
 * never crashes the app. All on/off transitions are click-free (short gate
 * ramps, phase-continuous frequency switches, smoothed mute).
 */
public final class SoundEngine {

    /** Axis demand magnitude above which the motion beeper sounds. */
    public static final double MOTION_THRESHOLD = 0.05;

    private static final float SAMPLE_RATE = 44_100f;
    private static final int BLOCK_FRAMES = 512;           // ~11.6 ms per block
    private static final double MASTER_GAIN = 0.55;        // modest overall volume

    // control-signal smoothing time constants
    private static final double DEMAND_TAU_S = 0.12;       // hum swell (anti-zipper)
    private static final double GATE_TAU_S = 0.015;        // beeper/alarm on-off ramps
    private static final double MASTER_TAU_S = 0.03;       // mute/unmute ramp

    // hydraulic hum
    private static final double HUM_BASE_HZ = 46.0;        // idle-adjacent pitch
    private static final double HUM_SPAN_HZ = 42.0;        // extra pitch at full demand
    private static final double HUM_GAIN = 0.42;
    private static final double HUM_NOISE_MIX = 0.35;      // sawtooth 65% / noise 35%
    private static final double HUM_LP_BASE_HZ = 260.0;
    private static final double HUM_LP_SPAN_HZ = 900.0;    // filter opens with demand

    // motion beeper
    private static final double BEEP_HZ = 950.0;
    private static final double BEEP_PERIOD_S = 0.75;
    private static final double BEEP_ON_S = 0.14;
    private static final double BEEP_EDGE_S = 0.003;       // attack/release ramp
    private static final double BEEP_GAIN = 0.16;

    // alarm buzzer
    private static final double ALARM_HZ_A = 800.0;
    private static final double ALARM_HZ_B = 620.0;
    private static final double ALARM_TONE_S = 0.30;       // per-tone dwell
    private static final double ALARM_GAIN = 0.30;

    private final SourceDataLine line;                     // null = audio unavailable
    private final Thread audioThread;

    // ---- targets: written by the FX thread, read by the audio thread ----
    private volatile double humTarget;                     // 0..1
    private volatile boolean beeperActive;
    private volatile boolean alarmActive;
    private volatile boolean muted;
    private volatile boolean running = true;

    public SoundEngine() {
        SourceDataLine opened = null;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            SourceDataLine candidate = (SourceDataLine) AudioSystem.getLine(info);
            candidate.open(format, BLOCK_FRAMES * 8 * 2);  // ~93 ms device buffer
            candidate.start();
            opened = candidate;
        } catch (Exception | LinkageError e) {
            opened = null; // no device / headless / restricted: run silently disabled
        }
        line = opened;
        if (line != null) {
            audioThread = new Thread(this::audioLoop, "crane-audio");
            audioThread.setDaemon(true);
            audioThread.start();
        } else {
            audioThread = null;
        }
    }

    /** True when an output device was opened; false = engine is silently disabled. */
    public boolean isAvailable() {
        return line != null;
    }

    /**
     * Feeds one frame of context. Cheap and allocation-free: only stores
     * targets for the audio thread. Call once per UI frame.
     */
    public void update(CraneCommand command, CraneState state) {
        if (line == null) {
            return;
        }
        double totalDemand = 0.0;
        boolean moving = false;
        for (double demand : command.axisDemands().values()) {
            double magnitude = Math.abs(demand);
            totalDemand += magnitude;
            if (magnitude > MOTION_THRESHOLD) {
                moving = true;
            }
        }
        boolean alarm = state.estopLatched() || state.watchdogTripped();
        humTarget = alarm ? 0.0 : Math.min(1.0, totalDemand);
        beeperActive = moving && !alarm;
        alarmActive = alarm;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public boolean isMuted() {
        return muted;
    }

    /** Stops the audio thread and releases the device. Safe to call twice. */
    public void close() {
        running = false;
        if (audioThread != null) {
            try {
                audioThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- audio thread ----

    private void audioLoop() {
        byte[] buffer = new byte[BLOCK_FRAMES * 2];
        double demandCoef = onePoleCoef(DEMAND_TAU_S);
        double gateCoef = onePoleCoef(GATE_TAU_S);
        double masterCoef = onePoleCoef(MASTER_TAU_S);
        long beepPeriod = (long) (BEEP_PERIOD_S * SAMPLE_RATE);
        long beepOn = (long) (BEEP_ON_S * SAMPLE_RATE);
        long alarmTone = (long) (ALARM_TONE_S * SAMPLE_RATE);
        Random random = new Random(12345);

        double hum = 0, humLp = 0, sawPhase = 0, beepPhase = 0, alarmPhase = 0;
        double beepGate = 0, alarmGate = 0, master = 0;
        long clock = 0;

        try {
            while (running) {
                double humT = humTarget;
                double beepT = beeperActive ? 1.0 : 0.0;
                double alarmT = alarmActive ? 1.0 : 0.0;
                double masterT = muted ? 0.0 : 1.0;

                for (int i = 0; i < BLOCK_FRAMES; i++) {
                    hum += (humT - hum) * demandCoef;
                    beepGate += (beepT - beepGate) * gateCoef;
                    alarmGate += (alarmT - alarmGate) * gateCoef;
                    master += (masterT - master) * masterCoef;

                    // hydraulic hum: pitched sawtooth + noise through a tracking low-pass
                    sawPhase += (HUM_BASE_HZ + HUM_SPAN_HZ * hum) / SAMPLE_RATE;
                    if (sawPhase >= 1.0) {
                        sawPhase -= 1.0;
                    }
                    double raw = (1.0 - HUM_NOISE_MIX) * (2.0 * sawPhase - 1.0)
                            + HUM_NOISE_MIX * (2.0 * random.nextDouble() - 1.0);
                    humLp += (raw - humLp) * cutoffCoef(HUM_LP_BASE_HZ + HUM_LP_SPAN_HZ * hum);
                    double sample = HUM_GAIN * hum * humLp;

                    // motion beeper: enveloped pulse train
                    double envelope = beepEnvelope(clock % beepPeriod, beepOn);
                    if (beepGate > 1e-4 && envelope > 0.0) {
                        beepPhase += BEEP_HZ / SAMPLE_RATE;
                        if (beepPhase >= 1.0) {
                            beepPhase -= 1.0;
                        }
                        sample += BEEP_GAIN * beepGate * envelope
                                * Math.sin(2.0 * Math.PI * beepPhase);
                    }

                    // alarm buzzer: phase-continuous alternating two-tone
                    double alarmHz = ((clock / alarmTone) % 2 == 0) ? ALARM_HZ_A : ALARM_HZ_B;
                    alarmPhase += alarmHz / SAMPLE_RATE;
                    if (alarmPhase >= 1.0) {
                        alarmPhase -= 1.0;
                    }
                    sample += ALARM_GAIN * alarmGate * Math.sin(2.0 * Math.PI * alarmPhase);

                    double value = Math.clamp(sample * master * MASTER_GAIN, -1.0, 1.0);
                    short pcm = (short) Math.round(value * 32_767.0);
                    buffer[2 * i] = (byte) (pcm & 0xff);
                    buffer[2 * i + 1] = (byte) ((pcm >> 8) & 0xff);
                    clock++;
                }
                line.write(buffer, 0, buffer.length);  // blocking write paces the loop
            }
        } catch (Throwable t) {
            // Audio must never take down the HMI — go silent instead.
        } finally {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) {
                // closing a dead device is best-effort
            }
        }
    }

    /** 3 ms attack/release trapezoid over the beep's on-window, 0 outside it. */
    private static double beepEnvelope(long tSamples, long onSamples) {
        if (tSamples >= onSamples) {
            return 0.0;
        }
        double edge = BEEP_EDGE_S * SAMPLE_RATE;
        double attack = tSamples / edge;
        double release = (onSamples - tSamples) / edge;
        return Math.min(1.0, Math.min(attack, release));
    }

    /** One-pole smoothing coefficient for a time constant in seconds. */
    private static double onePoleCoef(double tauSeconds) {
        return 1.0 - Math.exp(-1.0 / (tauSeconds * SAMPLE_RATE));
    }

    /** One-pole low-pass coefficient for a cutoff frequency in Hz. */
    private static double cutoffCoef(double cutoffHz) {
        return 1.0 - Math.exp(-2.0 * Math.PI * cutoffHz / SAMPLE_RATE);
    }
}
