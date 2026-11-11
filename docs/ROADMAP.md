# HyperSpring roadmap

What is planned for the next versions. Plans may change. Suggestions are welcome in [Issues](https://github.com/olilio3/HyperSpring/issues).

The bar for getting on this list: a fair number of people will use it, it can be maintained when HyperOS changes, and it costs no battery or smoothness to those who don't use it. A flashy effect that gets turned off after two days doesn't make it.

## Known bugs
- None open right now. If you find one, open an issue with the log (**About → Copy log**) and your settings (**About → Export settings**).

## Next
- [ ] **Hot reload (libxposed API 102):** module updates and feature switches apply without restarting the launcher or System UI.
  - The module already loads through API 102; it still has to learn to "unmount" itself: stop its threads, remove its listeners and reinstall the hooks with the new code.
  - This is the most delicate item here: leaving anything half-done crashes System UI. It will ship in a beta first.
- [ ] **Springy folders on the stock launcher.** The bounce only works with Kashi's launcher for now.
- [ ] **POCO Launcher support**, bringing in the work from the community fork ([ToolDroidYT/hyperspring](https://github.com/ToolDroidYT/hyperspring)).
- [ ] **Automatic builds on GitHub** on every change, to catch mistakes before a release. Release APKs are still signed separately.

## Later, if there is demand
- [ ] **Automatic quality:** lower the genie resolution on its own when the log shows many stutters in a row. For now the "Performance" preset covers it.
- [ ] **In-app preview.** It means duplicating the animations outside the launcher; with presets and live changes, the benefit is small.
- [ ] **Review what HyperCeiler and Kashi's HyperOS Launcher Mod already include** before adding new features, to avoid duplicates.

## Dropped
Ideas that were on the list and have been removed, with the reason:
- **Elastic overscroll in every app's lists.** It needs hooks in the Android framework of every app: a lot of risk and scope for a small effect.
- **Gyroscope wallpaper parallax.** It keeps a sensor running on the home screen: battery spent on decoration.
- **Wallpaper ripple on touch** and **icon cascade on unlock.** Eye-catching effects that get old quickly and add GPU work right when the phone is already busy.
- **Rubber-band volume and brightness sliders.** They depend on System UI plugin classes that change with every HyperOS version; a lot of upkeep for a detail.
- **More recents styles (fan, 3D stack).** What people asked for was a way to turn the bending off, and that already exists (**Home → Recents that bend**).

## Done
- [x] 2.0.0: libxposed API 102 through our own hook facade with two engines (the legacy one, API 93, stays as a separate APK). One-tap presets (Smooth, iOS-like, Fast, Performance). Export and import settings. The compatibility card shows which API the module loaded with. Sliders reach the launcher without restarting it.
- [x] 1.0.3: smoother genie that looks closer to stock HyperOS (threads on every core, drawing on every refresh with prediction, the app's own colour when opening, landing in the icon like MIUI). Details in the CHANGELOG.
- [x] 1.0.2: the control center glass stopped working after a while. Recents cards only bend when you move them with your finger.
- [x] 1.0.0: the APK was built with targetSdk 30, so Android 16 refused to install it. Fixed (now targetSdk 35).
