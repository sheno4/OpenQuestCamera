# OpenQuestCamera

Open-source stereoscopic camera for Meta Quest 3 and Quest 3S.

## Features

- Dual-camera SBS photos and videos
- GPU preview, stereo composition, and hardware encoding
- Automatic camera resolution detection
- 25, 30, 50, and 60 FPS when supported
- H.264/H.265 recording with 1–100 Mbps bitrate control
- Optional microphone audio and left/right original photos
- Convergence, vertical alignment, and eye swapping
- Level, rule-of-thirds, and center-crosshair guides
- Recording HUD for battery, storage, FPS, dropped frames, stereo sync, and roll
- Optional preview hiding and display dimming while recording
- 20 languages with an in-app selector; English is the default
- Local MediaStore output with no internet permission

Media is saved to `Pictures/OpenQuestCamera/` and `Movies/OpenQuestCamera/`.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

## License

MIT
