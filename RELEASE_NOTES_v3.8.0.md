# Crane Remote Control v3.8.0

Desktop crane operator training software. Free, no account, Windows 10/11.

## Download

| File | Use this when |
|---|---|
| `CraneRemoteControl-3.8.0.msi` | Normal install. Adds a Start-menu entry. |
| `CraneRemoteControl-3.8.0-portable.zip` | Your PC blocks installers (common on school and work machines). Unzip anywhere, run `CraneRemoteControl.exe`. No admin rights. |

Both include their own Java runtime — nothing else to install.

**Windows will warn you.** The build is not code-signed yet, so you will see
*"Windows protected your PC"*. Click **More info → Run anyway**.

## What it does

- A full operator HMI with real safety semantics: latching emergency stop, hold-to-run
  deadman, command watchdog, per-axis position limits, interference protection.
- Records every session and plays it back, with a summary of what happened — emergency
  stops tripped, limits driven into, time spent moving, control smoothness.
- Cranes are JSON profiles, not code. Three are bundled; build your own in the app.
- A narrated demo that paces itself to whichever crane is selected.

## In this release

- HMI decisions moved into a tested class. Every HMI defect found by three external
  audits lived in the main window class, which had never had a direct test because it
  needs a JavaFX toolkit to instantiate. 181 automated tests now.
- The 3D view is labelled experimental in the app. The 2D schematic is dimensionally
  accurate and is the view to teach and measure from; 3D is for presentation and parts
  can still overlap in some poses.
- A licence, which the project did not have.
- A load can no longer be winched up into the jib.

## Known limits

- The serial link to physical hardware has never been connected to a real machine.
- 3D contact handling is approximate; see above.
- Not certified safety equipment, and not a substitute for supervised time on a real
  crane. On any physical rig the emergency stop must be a hardware circuit and the
  travel limits must be enforced by the machine — see `docs/PROTOCOL.md` §0.

## Feedback

I am looking for instructors to try this: vukotic.vojislav1@gmail.com
