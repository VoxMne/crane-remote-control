plugins {
    application
}

// The visualiser. A SEPARATE PROCESS on purpose: it renders and nothing else, it
// cannot reach the control path, and if the GPU driver takes it down the cockpit
// keeps running. See docs/ARCHITECTURE.md "Viewer process".
val jmeVersion = "3.6.1-stable"

dependencies {
    // crane-core only for CraneState/CraneProfile — the shared vocabulary. Note
    // what is NOT here: no crane-sim, no crane-driver-serial, no control loop.
    // The viewer has no way to command anything, by construction.
    implementation(project(":crane-core"))

    implementation("org.jmonkeyengine:jme3-core:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-desktop:$jmeVersion")
    implementation("org.jmonkeyengine:jme3-effects:$jmeVersion")
    runtimeOnly("org.jmonkeyengine:jme3-lwjgl3:$jmeVersion")
}

application {
    mainClass.set("com.vukotic.crane.viewer.ViewerMain")
}
