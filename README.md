# B7 Radio Player

An open-source Android internet radio player built with AndroidX Media3.

## Features
- Stream live internet radio stations (MP3, AAC, M3U, PLS)
- Search and add stations via Radio Browser
- Built-in equalizer (Bass, Mid, Treble)
- Favorites and custom station management
- Import and export station playlists (`stations.xml`)
- Background playback with lock screen and Android Auto controls
- Station details and connection status monitoring

## Build Instructions

### Command Line
```bash
./gradlew assembleRelease
```

### Android Studio
1. **File → Open** and select the `B7 Radio Player` folder.
2. Build with **Build → Make Project** or run directly on a device/emulator.

## Permissions
- `INTERNET` & `ACCESS_NETWORK_STATE`: Stream radio stations and search Radio Browser.
- `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: Keep audio playing in the background.
- `MODIFY_AUDIO_SETTINGS`: Support the built-in equalizer.

## License
Licensed under the [GNU General Public License v3.0](LICENSE) (`GPL-3.0-or-later`).
