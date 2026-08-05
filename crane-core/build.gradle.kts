// Pure Java module: domain model, safety layer, control loop, CraneDriver port.
// MUST NOT depend on JavaFX or any UI/hardware library (see CLAUDE.md).

dependencies {
    // 2.18.8+: 2.17.1 falls in the affected range for CVE-2026-54512. This project
    // never enables polymorphic typing, so it was not exploitable here, but pinning
    // a flagged version in something sold to manufacturers is not worth defending.
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.8")
}
