# PocketVR

Turn your Android phone into a mixed-reality headset. Put the phone in a VR Box / Cardboard-style viewer and use your bare hands. The camera shows your room, and windows float in it.

PocketVR is a native Android app (Java, OpenGL ES 2). It is built to run on low-end phones, so it keeps the picture smooth first and pretty second.

## What it does

**Mixed reality**
- The back camera shows your real surroundings, and windows float in the room.
- Head tracking uses the phone's rotation sensor, with a compass that slowly corrects sideways drift.
- Camera timewarp: the camera picture is turned by how far your head moved since it was taken, so the view stays steady.
- Cinema mode: a dark room where only the screens are shown.
- Experimental walk-around mode (camera based, no extra sensors).

**VR box support**
- Split screen (left / right eye) with lens correction (off / light / medium / strong).
- Field of view per eye, double-image fix and red / blue edge fix, so you can match your own lenses.

**Hands (camera hand tracking, no controller)**
- Pinch (thumb + index) to select, drag windows, and resize.
- Air touch: push a fingertip forward to press.
- Left palm menu: open your left palm to the camera and two buttons appear on it (Menu and Settings). Poke one with a finger of your right hand.
- Hand outline and skeleton drawn over the camera picture.
- The hand is followed between model results with optical flow, so it keeps up even when the model is slow.
- Option to keep only the user's hands and ignore hands of other people far away.
- Works with one or two hands.

**Windows and apps**
- Quest-style glass menu with a dock, categories and an app grid.
- New windows slide in next to the windows that are already open, never on top of each other and never behind you.
- Browser (up to 3 windows), keyboard, clock / stopwatch, system status, settings.
- Gallery: photos, videos and 360 pictures, with a cinema mode.
- Real Android apps in floating windows (needs Shizuku).
- Popular web apps as floating windows.

**Made for weak phones**
- The hand model can run on CPU or GPU.
- Heat guard: when the phone gets warm or the model slows down, hand detection rests between looks and drawing is capped.
- Automatic picture resolution to keep the frame rate up.

**Language**
- English by default. Thai can be selected in Settings > System > Language.

## Requirements
- An Android phone with a back camera and a rotation (gyroscope) sensor.
- A VR Box, Cardboard or similar viewer. A phone-in-viewer setup where the camera is not blocked is best.
- Camera permission. Photos and videos permission only for the gallery.
- Optional: Shizuku, to run real apps in floating windows.

## Build
The app is built by GitHub Actions. Push the sources to `app/src/main/java/com/pocketvr/app/` and the workflow in `.github/workflows/build.yml` produces `PocketVR.apk`. No computer is needed: files can be uploaded through the GitHub web page.

## Tips
- If the hand view lags or the phone gets hot, set hand detection to CPU and to one hand in Settings > Hands, or turn on a saver mode in Settings > Performance.
- If the picture looks doubled or curved, adjust Settings > VR (field of view, double-image fix, lens).
- Window positions are not saved. Settings are saved.

## Third-party parts
- MediaPipe Hand Landmarker (Google) for hand tracking.
- Shizuku (optional) for floating real apps.

## License
All rights reserved. This project is shared for people to try; please ask before reusing the code.
