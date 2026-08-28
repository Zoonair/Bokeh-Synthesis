# Bokeh Synthesis (Android Portrait Mode)

This project explores an alternative approach to synthesizing background blur ("bokeh") on Android phones (6.0+), using the camera's own focus system rather than relying on a second camera, a dedicated depth sensor, or a trained segmentation model.

## Common Methods
Most phones achieve background blur in one of two ways:

- **Multi-camera stereo depth.** Uses the physical offset between two lenses to find depth. Requires hardware that many phones may not have, and sacrifices a lens (generally the widest).
- **Single-image segmentation**. A trained model guesses which pixels belong to the subject vs. background. Doesn't measure real depth, and struggles with anything the model wasn't trained on.

Both approaches need specialized hardware or a pre-trained model, and neither is guaranteed to be available or reliable across the huge range of devices.

## Using Focus To Estimate Depth
Every camera already has a way to sense depth for free: focus. This project captures a short burst of frames while sweeping the lens through its focus range, then analyzes how sharpness shifts frame-to-frame to estimate relative depth without needing a second lens, a depth sensor, or a trained model. This method could be used on any device with variable focus.


## Getting Started

### Requirements

- Android Studio (a recent, stable release)
- A physical Android device running Android 6.0+ (API 23+) with a variable-focus camera, as the focus sweep relies on manual focus control, so an emulator won't produce meaningful results
- USB debugging enabled on the device

### Build & Install

1. Clone this repo (https://github.com/Zoonair/Bokeh-Synthesis.git). This can be done within Android Studio.
2. Open the project root in Android Studio and let Gradle sync.
3. Connect your device and select it as the run target.
4. Run the `app` module in Android Studio, or `./gradlew installDebug` from the command line.
5. Grant the camera permission when asked on first launch.

### Capturing a Depth Map

1. Open the app and point the camera at your subject.
2. Tap to focus on the subject, as you would normally.
3. Press the shutter. The app captures a short burst of frames while sweeping focus from near to far.
4. The sharp reference photo and a color-coded depth preview are saved automatically.

### Finding Your Output

Each capture produces a few files, some visible in the system Gallery and some app-private:

`original.jpg`:  Gallery (`Pictures/BokehSynthesis/`)
  The sharp reference photo from the tapped focus point

`depth_preview.jpg`:  Gallery (`Pictures/BokehSynthesis/`)
  Color-coded depth visualization (blue = far, red = near). Black marks unresolved pixels, though there should be none.

`depth_data.json`:  App-private storage
  Full-precision per-pixel diopter values

`metadata.json`:  App-private storage
  Device info, calibration data, and capture parameters for that sweep

All files from one capture share a common capture ID (visible in the filename and embedded in each JPEG's EXIF `UserComment`), so they can be matched back up even if moved or renamed.

### Tuning

A few constants affect depth-map quality and are still being empirically validated. These are currently in `DepthMapBuilder.kt` and `BokehRender`, with the resolution, number of sweeping frames, and focus settle delay in `MainViewModel`. If you're experimenting on your own device, start there.

## Known Weaknesses
As of August 24, 2026, the current algorithm is not fully refined and has many weaknesses. These are not limited to, but include:

- Letters/texts
- Very fine details (e.g. hairs)
- Constantly moving parts
- Areas with low contrast
- Similarly colored areas with different depths
- Noisy images
- Images taken in low light
- Blurring from far away
- Transparent or reflective surfaces
- Non-calibrated sensors

These weaknesses may lead to unexpected results in your depth map and final image.
