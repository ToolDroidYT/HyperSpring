# POCO Launcher port

## Target analyzed

- Package: `com.mi.android.globallauncher`
- Version: `RELEASE-7.50.06.2597-09231532`
- Version code: `750062597`

The adapter reuses shared Xiaomi launcher hooks. It does not modify or replace the POCO Launcher APK.

## Compatibility notes

| Feature | Static result | Limitation |
|---|---|---|
| Genie open/close | Required symbols found in analyzed APK | Runtime behavior needs device testing |
| Wallpaper zoom | Required symbols found | Runtime behavior needs device testing |
| Pressed icon | Required symbols found | Runtime behavior needs device testing |
| Bending recents | Required symbols found | Runtime shader behavior needs device testing |
| Glass folders | Required symbols found | Depends on device and OS support |
| Widget stack | Disabled | Kashi-specific WidgetStack class absent |
| Springy folder resize | Disabled | Kashi-specific ResizeHandle API absent |

Static DEX symbol checks do not prove method descriptor compatibility or runtime stability.

## Adapter design

- `LauncherAdapter` defines the compatibility contract.
- `HyperOSLauncherAdapter` preserves the existing Xiaomi launcher target.
- `PocoLauncherAdapter` targets `com.mi.android.globallauncher`.
- `LauncherAdapters` resolves the configured Android HOME launcher.
- Shared animation hooks remain in HyperSpring; Kashi-only integrations are gated by adapter capabilities.

System UI hooks are unchanged and are not newly certified for POCO firmware.

## CI

The Android workflow builds the release APK with JDK 17 and Android API 35, verifies its APK signature, and uploads the APK and SHA-256 checksum. Pushing a `v*` tag publishes a GitHub Release.

The current release variant uses the project's existing signing configuration. Configure a private release keystore and GitHub Actions secrets before production distribution.

## On-device test sequence

1. Keep LSPosed recovery access available.
2. Enable only the POCO Launcher scope first.
3. Reboot and confirm the launcher starts normally.
4. Test icon press, folder open/close, app opening, and recents gestures.
5. Enable effects one at a time.
6. If the launcher crashes, disable the module and collect its log.
