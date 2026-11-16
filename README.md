# C12 Gimbal Control — preparation build

This is a starter Android app and GitHub Actions build pipeline.

## Important safety status
Version 0.1.0 is a UI/build smoke test only. It does **not** send UDP packets,
read UniRC channels, or move the gimbal. Do not treat it as a working controller.

Before enabling control, confirm from the exact C12 SDK/manual:
- the command packet format and checksum/encoding;
- UDP destination IP and port for this exact camera/firmware;
- pitch/zoom/center/down-90 command semantics and limits;
- how CH10–CH13 are exposed by UniRC 7 / Ground Station.

## Build
Push the project to the `main` branch or run Actions -> Build Android APK -> Run workflow.
Download the `C12-Gimbal-Control-debug` artifact from the successful workflow run.
