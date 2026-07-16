package com.vukotic.crane.core.model;

import java.util.List;

/** Built-in crane profiles. JSON-file profiles arrive in M3; this factory stays for demos/tests. */
public final class CraneProfiles {

    private CraneProfiles() {
    }

    /**
     * A typical mid-size truck-mounted knuckle-boom loader (Palfinger/HIAB/Fassi class):
     * slew ring, main boom, articulated jib, hydraulic extension, and winch.
     */
    public static CraneProfile demoKnuckleBoom() {
        return new CraneProfile("demo-kb-5", "Demo Knuckle-Boom (5-axis)", List.of(
                new AxisSpec("slew", "Slew (rotation)", "deg", -180.0, 180.0, 15.0, 2.0),
                new AxisSpec("boom", "Main boom", "deg", -5.0, 75.0, 8.0, 2.0),
                new AxisSpec("jib", "Jib (knuckle)", "deg", 0.0, 150.0, 10.0, 2.0),
                new AxisSpec("extension", "Boom extension", "m", 0.0, 6.0, 0.5, 2.0),
                new AxisSpec("winch", "Winch (rope out)", "m", 0.0, 20.0, 1.0, 2.0)));
    }
}
