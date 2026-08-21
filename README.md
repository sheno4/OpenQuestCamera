# OpenQuestCamera

Open-source stereoscopic camera for Meta Quest 3 and Quest 3S. Version 0.1 captures side-by-side photos and videos entirely on the headset and does not request network access.

## Features

- Dual-camera SBS photo and video capture
- GPU Surface pipeline for preview, stereo composition, and hardware video encoding
- SBS photos by default, with optional left/right originals
- Dynamic camera resolution detection
- 25, 30, 50, and 60 FPS modes when supported by the cameras and encoder
- H.264 and H.265 video
- 1–100 Mbps bitrate control
- Optional AAC microphone audio at 48 kHz / 192 kbps
- Stereo convergence, vertical correction, and eye swapping
- Binocular level, rule-of-thirds grid, and center crosshair
- Recording HUD with battery, storage, remaining time, actual FPS, dropped frames, stereo sync delta, and roll
- Foreground camera service and optional display dimming while recording

## Languages

English is the default and fallback language. The app includes complete resources for:

English, Japanese, German, French, Spanish, Korean, Italian, Dutch, Polish, Portuguese (Brazil), Simplified Chinese, Traditional Chinese, Swedish, Norwegian Bokmål, Danish, Czech, Russian, Turkish, Thai, and Indonesian.

The app follows the headset language. On Android-based systems that expose per-app language settings, all 20 languages are also declared in the system language picker.

## Output

- Videos: `Movies/OpenQuestCamera/`
- Photos: `Pictures/OpenQuestCamera/`

Media is written through Android MediaStore. The app has no internet permission and does not upload recordings.

## Build

Requirements: JDK 17 and Android SDK 35 with Build Tools 35.0.0.

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Release builds are unsigned unless a local, ignored `keystore.properties` file supplies a signing configuration.

## Install

Enable Developer Mode on the Quest headset, connect it by USB, accept the debugging prompt, and sideload the release APK with Meta Quest Developer Hub, SideQuest, or ADB.

## License

MIT — see [LICENSE](LICENSE).
