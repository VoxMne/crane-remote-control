package com.vukotic.crane.ui.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The winch used to know about the ground and nothing else, so paying out rope
 * over the truck ran the rope and the hook straight through the deck, through
 * the cab roof and through a container standing on the bed.
 *
 * <p>Vehicle coordinates, Y measured downwards: the ground is 0 and anything
 * above it is negative.
 */
class HookClearanceTest {

    private static final double DECK_Y = -1.1;
    private static final double CAB_ROOF_Y = -2.3;
    /** A jib tip 7.43 m above the ground — boom 60°, jib straight out. */
    private static final double TIP_Y = -7.43;

    @Test
    void theDeckIsASurface() {
        assertEquals(DECK_Y, Crane3DView.surfaceHeightLocal(3.0, 0.0),
                "a point over the middle of the bed stands on the deck");
    }

    @Test
    void theCabRoofIsASurface() {
        assertEquals(CAB_ROOF_Y, Crane3DView.surfaceHeightLocal(-1.75, 0.0),
                "the cab was missing entirely, so loads were lowered through its roof");
    }

    @Test
    void besideTheTruckIsGround() {
        assertEquals(0.0, Crane3DView.surfaceHeightLocal(3.0, 4.0));
        assertEquals(0.0, Crane3DView.surfaceHeightLocal(20.0, 0.0));
    }

    @Test
    void ropeStopsShorterOverTheDeckThanOverTheGround() {
        double overGround = Crane3DView.ropeToSurface(TIP_Y, 0.0);
        double overDeck = Crane3DView.ropeToSurface(TIP_Y, DECK_Y);

        assertEquals(6.58, overGround, 1e-9);
        assertEquals(1.1, overGround - overDeck, 1e-9,
                "the deck sits 1.1 m up, so exactly that much less rope may run out");
    }

    @Test
    void ropeStopsAboveALoadStandingOnTheDeck() {
        // A 1.4 m container on the deck: its top face is 2.5 m above the ground.
        double containerTop = DECK_Y - 1.4;
        double overContainer = Crane3DView.ropeToSurface(TIP_Y, containerTop);

        assertTrue(overContainer < Crane3DView.ropeToSurface(TIP_Y, DECK_Y),
                "the hook must stop on the container, not carry on through it");

        // The hook block hangs below the rope end; it has to end up close enough
        // to the load's top face that the load can still be picked back up.
        double hookAboveTop = (containerTop - (TIP_Y + overContainer)) - 0.42;
        assertTrue(hookAboveTop > 0, "the hook stays clear of the load: " + hookAboveTop);
        assertTrue(hookAboveTop < 0.7, "…but within pick-up reach: " + hookAboveTop);
    }

    @Test
    void aTipBelowItsSurfacePaysNoRope() {
        assertEquals(0.0, Crane3DView.ropeToSurface(-0.5, 0.0),
                "no negative rope when the jib tip is already at hook height");
    }

    // ---- where a load may stand on the deck ----

    /** Small boat: 4.2 m long, 1.5 m wide. */
    private static final double BOAT_LENGTH = 4.2;
    private static final double BOAT_WIDTH = 1.5;
    /** Front face of the headboard, and the mast keep-out radius. */
    private static final double DECK_REAR_LIMIT = 6.15 - 0.06;
    private static final double MAST_KEEP_OUT = 0.95;

    @Test
    void aSlewedLoadTakesUpLessRoomForeAndAft() {
        assertEquals(BOAT_LENGTH / 2,
                Crane3DView.rotatedHalfExtentX(BOAT_LENGTH, BOAT_WIDTH, 0), 1e-9);
        assertEquals(BOAT_WIDTH / 2,
                Crane3DView.rotatedHalfExtentX(BOAT_LENGTH, BOAT_WIDTH, 90), 1e-9,
                "lying across the truck a boat is 1.5 m long fore-and-aft, not 4.2");
    }

    @Test
    void aLoadCannotBeSetDownThroughTheHeadboard() {
        // The reported case: the hook was out at x = 5.84, so a 4.2 m boat centred
        // there ran from 3.74 to 7.94 — 1.8 m of it through the headboard and out
        // past the end of the truck.
        double halfX = Crane3DView.rotatedHalfExtentX(BOAT_LENGTH, BOAT_WIDTH, 0);
        double placed = Crane3DView.clampOntoDeckX(5.84, halfX);

        assertTrue(placed + halfX <= DECK_REAR_LIMIT + 1e-9,
                "the stern must stop at the headboard, not go through it");
        assertTrue(placed - halfX >= MAST_KEEP_OUT - 1e-9,
                "and the bow must stay clear of the mast");
    }

    @Test
    void aLoadAlreadyOnTheDeckIsLeftWhereItIs() {
        double halfX = Crane3DView.rotatedHalfExtentX(BOAT_LENGTH, BOAT_WIDTH, 0);
        assertEquals(3.5, Crane3DView.clampOntoDeckX(3.5, halfX), 1e-9,
                "a load that already fits must not be shoved around");
    }

    @Test
    void aLoadTooLongForTheDeckGoesToTheMiddle() {
        double placed = Crane3DView.clampOntoDeckX(5.0, 12.0);
        assertEquals((-0.85 + 6.15) / 2, placed, 1e-9);
    }
}
