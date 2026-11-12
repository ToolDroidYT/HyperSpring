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

## Planned
Ideas noted on 2026-11-11. Not started yet, in no fixed order. Before the home screen ones, check what Kashi's Launcher Mod already includes.

### Liquid glass
- [ ] **Glass dock:** the launcher dock as a floating bar with edge refraction, using the same shader as the app's bar.
- [ ] **Glass notifications:** shade cards and the heads-up popup, with blur and a lit edge. To keep it light, every card has to share a single capture of the background.
- [ ] **Refraction on folders:** add the edge lens to the blur they already have.
- [ ] **Magnifier on control center sliders:** while dragging brightness or volume, the control turns into a glass drop that magnifies what is behind it. Note: it depends on System UI plugin classes that change between versions.
- [ ] **Glass styles:** Clear, Smoked, Frosted, Monet tint and Prism (more dispersion), picked with one tap.
- [ ] **Highlight that follows your finger:** the glow on the glass edge moves towards where you touch. No gyroscope, so no battery cost.

### Animations
- [ ] **Notifications with a genie-style animation (or something like it), iOS style:** opening a notification makes the app come out of the card; dismissing it folds the card away.
- [ ] **Springy notifications:** the heads-up popup comes in with a small bounce.
- [ ] **Fade when opening the control center**, with a ColorOS 17 style animation.
- [ ] **Genie into folders:** closing an app that lives inside a folder sends the window back to the folder.
- [ ] **Genie on widgets:** opening an app from a widget makes the window come out of the widget.
- [ ] **Predictive back gesture with physics:**
  - On the Redmi 14C HyperOS ships no predictive back animations, so they have to be created as well, not just restyled.
  - While the gesture is dragged, the screen shrinks and tilts with the finger (using the curtain physics). On release it springs back or closes.
- [ ] **Home screen page transitions** where icons stretch with the swipe speed.
- [ ] **Dark mode with a circular wave:** when the theme changes, the new palette spreads in a circle from the control center button.
- [ ] **Gentle zoom on unlock:** the home screen comes in with a slight scale, without animating each icon.

### Features
- [x] **Apps excluded from the genie:** the apps you pick open and close with the HyperOS animation. Applies right away. *Done, not released yet.* The same for the System UI curtain is still to do.
- [ ] **Automatic pause:** heavy effects switch off by themselves with battery saver, in games (Game Turbo) or when the phone is hot.
- [ ] **Subtle vibration** in sync with the animations: when the genie lands in the icon, when a folder bounces, when the widget stack changes.
- [x] **Visual spring editor:** an animated graph of the curve while you move Speed and Bounce, with a tiny phone opening a window with that spring. *Done, not released yet.*
- [ ] **Quick settings tile** to turn HyperSpring on or off and restart the launcher.
- [x] **New version notice:** checks GitHub releases (can be turned off), and downloads and installs from the app. *Done, not released yet.*
- [x] **What's new after updating:** an animated screen with the changes the first time the app opens after an update. *Done, not released yet.*

### The app
- [ ] **Redesigned, heavily animated interface**, like modern websites: staggered entrances, scroll-driven animations, shared transitions between screens and micro-interactions on every control. Always 60 fps on mid-range phones and respecting the system setting that removes animations.

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
