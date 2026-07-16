package com.vukotic.crane.ui.backend;

import com.vukotic.crane.core.model.CraneCommand;
import com.vukotic.crane.core.model.CraneState;

/**
 * The single seam between the HMI and whatever runs the crane.
 *
 * <p>The UI pushes one {@link CraneCommand} per frame into {@link #submitCommand}
 * and polls {@link #latestState()} to render. At integration (M2b) the stub
 * implementation is replaced by an adapter over the real ControlLoop +
 * SimulatedCraneDriver; nothing else in crane-ui changes.
 *
 * <p>Implementations must be safe to call from the FX application thread while
 * producing state on their own thread.
 */
public interface CraneBackend {

    /** Latest operator command; called once per UI frame. */
    void submitCommand(CraneCommand command);

    /** Most recent published state; never null after construction. */
    CraneState latestState();
}
