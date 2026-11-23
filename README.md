# DimWall

A tiny Android app that turns a photo into a muted wallpaper: uniform blur, less saturation, less contrast, dimmer. Pick a photo (or share one to the app), frame it for your screen, drag the sliders, set it as wallpaper.

- Kotlin + Jetpack Compose, no network, one permission (`SET_WALLPAPER`).
- Photo Picker, so no access to the whole gallery.
- Output is rendered at the real screen resolution; the preview uses the same pipeline.

## Install and update

Releases are built by GitHub Actions and published as `DimWall-vX.Y.Z.apk`. Add this repository to [Obtainium](https://github.com/ImranR98/Obtainium) to install and update it.

## Build

Builds run in GitHub Actions (`.github/workflows`). A tag like `v1.2.3` produces a signed release APK. The signing key is kept in repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); for a local signed build copy `keystore.properties.example` to `keystore.properties`.
