# HyperTweak roadmap

Ideas planned for upcoming versions. Nothing here is implemented yet, and plans may change. Suggestions are welcome in [Issues](https://github.com/olilio3/HyperTweak/issues).

## First step
- [ ] **Review what HyperCeiler, Kashi's HyperOS Launcher Mod and similar modules already offer**, so HyperTweak doesn't duplicate their features.

## Known bugs
- [ ] **Control center glass stops working after a while.** The liquid glass and shader effects in the control center disappear after some time of use. Still to investigate: does it happen after the screen turns off, after a while in the background, or after opening certain apps? Possible causes: the control center plugin (`miui.systemui.plugin`) being reloaded with a new class loader that the hooks don't reach, or the system discarding the shader to free memory.

## Animations
- [ ] **Global elastic bounce**
  - Lists stretch and spring back when you reach the end.
  - Folders bounce when they open. Today this only works with Kashi's launcher; the goal is to support the stock launcher too.
  - One shared "spring" setting (stiffness and damping) for every effect.
  - *Note:* bringing the bounce to lists in **every** app means hooking the system framework and adding more scopes in LSPosed. It may stay limited to the launcher and System UI.
- [ ] **More recents styles, off by default**
  - Community feedback: the card-bending effect also plays inside the recents view itself, which feels odd.
  - Possible styles: fan, 3D stack, and a simple slide (scale and blur).
- [ ] **Cascade on unlock:** home screen icons ripple in from the center or from where you touched.
- [ ] **Wallpaper ripple** when tapping an empty area of the home screen.
- [ ] **Gyroscope parallax** for the wallpaper.
- [ ] **Rubber-band volume and brightness sliders:** they stretch slightly at the limit and snap back.

## Features
- [ ] **libxposed API 102 with hot reload:** settings apply without restarting the launcher or System UI. The legacy engine (API 93) stays for older LSPosed versions.
- [ ] **In-app preview:** see each animation before turning it on.
- [ ] **Presets:** Smooth, iOS-like, Fast and Minimal, one tap each.
- [ ] **Export and import settings** as a file (also handy for bug reports).
- [ ] **Performance mode:** detect weaker GPUs and lower shader quality automatically.

## Done
- [x] 1.0.0: the APK was built with targetSdk 30, so Android 16 refused to install it. Fixed (now targetSdk 35).
