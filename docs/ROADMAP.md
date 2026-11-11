# HyperSpring roadmap

Ideas planned for upcoming versions. Nothing here is implemented yet, and plans may change. Suggestions are welcome in [Issues](https://github.com/olilio3/HyperSpring/issues).

## First step
- [ ] **Review what HyperCeiler, Kashi's HyperOS Launcher Mod and similar modules already offer**, so HyperSpring doesn't duplicate their features.

## Known bugs
- None open right now. If you find one, please open an issue with the log (**About → Copy log**).

## Animations
- [ ] **Global elastic bounce**
  - Lists stretch and spring back when you reach the end.
  - Folders bounce when they open. Today this only works with Kashi's launcher; the goal is to support the stock launcher too.
  - One shared "spring" setting (stiffness and damping) for every effect.
  - *Note:* bringing the bounce to lists in **every** app means hooking the system framework and adding more scopes in LSPosed. It may stay limited to the launcher and System UI.
- [ ] **More recents styles, with an on/off switch** *(1.0.2: cards now only bend when moved with the finger)*
  - Community feedback is split: some find the card bending odd, others love it (and it works with button navigation too). It stays, but becomes easy to turn off or swap for another style.
  - Possible styles: fan, 3D stack, and a simple slide (scale and blur).
- [ ] **Cascade on unlock:** home screen icons ripple in from the center or from where you touched.
- [ ] **Wallpaper ripple** when tapping an empty area of the home screen.
- [ ] **Gyroscope parallax** for the wallpaper.
- [ ] **Rubber-band volume and brightness sliders:** they stretch slightly at the limit and snap back.

## Features
- [ ] **Hot reload on libxposed API 102:** module updates and feature switches apply without restarting the launcher or System UI. *(1.1.0-beta1: the module already loads through API 102; hot reload itself is the next step.)*
- [ ] **In-app preview:** see each animation before turning it on.
- [ ] **Presets:** Smooth, iOS-like, Fast and Minimal, one tap each.
- [ ] **Export and import settings** as a file (also handy for bug reports).
- [ ] **Performance mode:** detect weaker GPUs and lower shader quality automatically.

## Done
- [x] 1.1.0-beta1: libxposed API 102 through our own hook facade with two engines; the legacy engine (API 93) stays as a separate APK for older LSPosed versions. Sliders now reach the launcher without restarting it.
- [x] 1.0.3: smoother genie that looks closer to stock HyperOS (threads on every core, drawing on every refresh with prediction, the app's own colour when opening, landing in the icon like MIUI). Details in the CHANGELOG.
- [x] 1.0.2: the control center glass stopped working after a while. Confirmed on the test phone.
- [x] 1.0.0: the APK was built with targetSdk 30, so Android 16 refused to install it. Fixed (now targetSdk 35).
