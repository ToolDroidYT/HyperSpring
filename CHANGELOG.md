# Changelog

## 2.5.0

**New**
- **Liquid glass sliders** in Desktop → Folders, applied right away without restarting the launcher:
  - **Glass quality** (25–100 %): how sharp the background seen through the glass is. Lower uses less memory.
  - **Edge thickness** (0–40 dp): width of the liquid-glass lens along the edge. 0 leaves only the thin rim.
  - **Refraction** (0–60 dp): how much the lens bends the background inwards.
  - **Color dispersion** (0–3): the rainbow split in the corners.
  - **Edge shine** (0–0.3): light the lens edge catches from the top left.
- **Touch glow on folders.** Touch a folder (or an app inside a large folder) and a soft light appears under your finger, follows it, and the rim lights up near it. It switches on quickly and fades out slowly when you lift your finger. Its strength has its own slider (**Touch glow**, 0 turns it off). It never takes the touch from the launcher.

## 2.2.0

**New**
- **Liquid glass folders on the home screen.** Folders now use the same lens as the control center: near the edge the wallpaper bends inwards like thick glass, with a hint of rainbow in the corners and a brighter lens highlight. Small folders get a proportionally smaller lens so the middle stays clear. Needs Android 13+ and a static wallpaper, as before.
- **Home as a bento grid.** A status pill on top (whether the module is active in the launcher and System UI), then the features as glass tiles: a wide one for the genie and two columns for the rest. Tap a tile to switch it, long-press to flip it and read what it does. Icons light up with a little pop when switched on.
- **Liquid-glass tab change.** Switching tabs, the new page grows from the tab you tapped behind a refracting glass rim that pushes the old page away.
- **Subpages open like HyperOS setup.** The old page dissolves (blur and fade), a soft light circle opens where you tapped, and the new page's cards come out of that circle and slide into place while they focus, one after another.
- **Hold to restart.** The launcher and System UI restart rows fill up while you hold them, so a stray tap can't restart anything.
- **Sharper top blur** (twice the samples).

**Changed**
- Colors back to coral, with flat coral accents instead of multi-hue gradients. App icon and logo redrawn flat coral.
- Switches use the Android 14 toggle haptics.

**Fixed**
- Icons and the floating logo no longer get cut off when they bounce or reach the top.
- Welcome screen: the text no longer slides under the logo when you scroll.

## 2.1.0

**New**
- **Depth style for opening and closing apps.** The normal HyperOS animation, but the window behaves like a rigid sheet with physics that tilts back, seen in real perspective. A genie that rotates instead of deforming.
  - The top leans away and the bottom towards you, turning around its middle, so its overall size never pulses. Opening, it starts with a very slight tilt that straightens as the app fills the screen. Closing, it bends clearly as soon as it leaves full screen, more the harder you swipe, and lands flat on the icon. The bottom edge never grows past the screen.
  - The tilt runs on a spring with weight: it lags a little when the window starts moving and straightens up with a tiny overshoot.
  - Real motion blur along the way each part moves (a zoom when it grows), fading out before it settles, so it always lands sharp.
  - The background blur is left to HyperOS; the home screen only dims very slightly.
  - Strength sets the tilt and the blur.
- **Style picker as a pull-down menu.** Instead of a 0–3 slider, Style shows the name of the current animation. Tap it and a menu springs open from the button, like in HyperOS 4 or iOS, with each style and a line on what it does.
- **Updates from inside the app.**
  - HyperSpring checks GitHub for a new release when you open it, at most every 6 hours. You can turn that off, or check by hand, in About → Settings.
  - When there is one, a notice appears on Home and a sheet slides up. The logo pulses, the version number rolls over to the new one and the changes appear one by one.
  - "Download and install" turns into a progress bar, then opens Android's installer. It downloads the same APK you have (`HyperSpring.apk` or `HyperSpring-legacy.apk`).
  - As the version rolls over, a burst of coral, pink and mauve sparks and confetti flies out of the logo and drifts down.
  - After updating, a "What's new" sheet shows the changes once.
  - The app now asks for internet access (only to reach GitHub) and for permission to hand the update to the installer, which always asks you to confirm.
- **Apps without genie** in Desktop → Opening and closing apps. Pick apps (games, the camera, video apps…) and they open and close with the normal HyperOS animation. There is a search box, the picked apps come first, and it applies right away without restarting the launcher. It's included when you export and import settings.
- **Visual spring editor** in Desktop → Opening and closing apps. A graph draws the opening curve with your Speed and Bounce, and a tiny phone next to it opens and closes a window with that same spring while a dot runs along the curve. It shows how much the window overshoots and when it settles. Speed and Bounce now sit right under the graph, and the curve morphs as you drag them.
- **Predictive back in the app (Android 14+).** While you swipe back from the edge, the page follows your finger: it shrinks, its corners round off and the previous page comes forward behind it. Let go and it springs away; change your mind and it springs back. The update sheet sinks a little with your finger too.
- **Animated welcome.** The first time the app opens, a coral drop falls, bounces and turns into the icon, which rises to its place. The name comes in white, blurred with its letters spread out and closes up into focus, and three highlights appear one by one. Behind it, soft coral, pink and mauve light drifts across a dark background.
- **Guided setup** in four more steps. Each one has a line icon on a coral tile, a big title and its pieces coming in one after another. Dots at the bottom stretch like a drop when you change step.
  - Going from the welcome to the first step, the welcome shrinks into a rounded card and fades while the step zooms in, like HyperCeiler. Between the other steps, pages slide in with a spring.
  - **Checkup:** whether the module is loaded in the launcher and System UI, and root access (asks for it).
  - **Style:** the presets, each with a tiny window that opens and closes with that preset's speed and bounce. "Keep my settings" is the default, so nothing changes unless you pick one.
  - **Features:** the same switches as on Home.
  - **All set:** offers to restart the launcher and System UI.
  - It can be replayed from About → Settings → Replay the tour.
- **Redesigned app with a fixed dark theme:** the coral of the icon as the main colour, with soft pink and mauve. It looks the same on every phone, whatever the wallpaper.
  - **Real liquid glass in switches and sliders.** While you hold it, the white knob turns into a clear drop that refracts the track: it magnifies the middle and bends it at the edge, with the same lens as the tab bar (Android 13+). Sliders also stretch with the speed of your finger.
  - **No colour fringes** on the app's glass: the tab bar, its bubble, switches and sliders. The control center glass is unchanged.
  - **Backgrounds:** About has soft coral, pink and mauve light that wanders across the whole screen, behind an opaque logo. The other pages have fine coral contour lines that flow endlessly, instead of the dot network.
  - **Progressive blur under the status bar:** content scrolling up blurs more and more, with no hard edge.
  - Row icons are coral on matching soft tiles, all alike, and group headers are coral. Cards are slightly see-through.
  - Cards and rows come in one after another. Big titles drift and fade as you scroll, and deeper pages push the previous one back.
  - Everything follows the system animation scale and stops when animations are turned off.

**Changes**
- The names of the old modules this app replaced are gone from the app and the docs. Log lines now start with `HyperSpring UI:` and `HyperSpring CC:`.

**Roadmap:** new "Planned" section (glass dock, glass notifications, predictive back with physics, exclusion list, automatic pause and more).

## 2.0.0

A major version because the way the module loads has changed: it now uses the modern Xposed API. It includes everything from 1.1.0-beta1.

**Which APK to install**
- `HyperSpring.apk`: for Vector 2.x and other current frameworks (libxposed API 101 or newer).
- `HyperSpring-legacy.apk`: for older LSPosed builds (classic API 93).
- Both install over 1.0.x and keep your settings. With `HyperSpring.apk` the old settings are imported the first time it runs; that import needs root.
- After updating, restart the launcher and System UI from the app (or reboot).

**New**
- **libxposed API 102.** The module loads through the modern Xposed API where the framework supports it. The animations themselves are unchanged, and frame counts are the same with both APIs.
- **Presets.** Smooth, iOS-like, Fast and Performance, one tap each (Home → Presets). A preset only changes the shape, speed and quality settings of the animations: it doesn't switch features on or off, and it leaves the glass look and the frame rate alone.
- **Export and import settings** (About → Settings). The file is plain JSON and also records the module version, the phone and the API in use, so it is handy to attach to a bug report. Importing only accepts settings the module knows, within their valid range.
- **The compatibility card shows which API loaded the module** in the launcher and in System UI.

**Fixes**
- **Sliders now reach the launcher right away.** Android was dropping the "settings changed" message on its way to the launcher, so launcher values only applied after restarting it.
- **Folder hooks on Kashi's launcher.** One launcher class could not be inspected because of a method that refers to a class missing from the APK, so its folder-resize hook was never installed and an error was logged on every start. The module now reads that class without tripping on the broken method.

**Roadmap:** rewritten. Effects that few people would keep on (gyroscope parallax, wallpaper ripple, unlock cascade, rubber-band sliders, elastic overscroll in every app) are dropped, with the reasons. Hot reload is next.

**Tested on a Redmi 14C** (HyperOS 3, Android 16, Vector 2.2) with both APKs.

## 1.1.0-beta1

**libxposed API 102**
- **The module now loads through the modern Xposed API** (libxposed 102) on frameworks that support it, such as Vector 2.x. Every hook goes through a small facade of our own (`xp/`) with two engines behind it: libxposed (`ModernEngine`) and classic Xposed 93 (`LegacyEngine`). The animations themselves are unchanged.
- **Two APKs per release, same code:**
  - `HyperSpring.apk`: libxposed (needs API 101 or newer; written for 102). Use this one on Vector and other current frameworks.
  - `HyperSpring-legacy.apk`: classic API 93 only, for older LSPosed builds. Those only know the retired API 100 and would fail to load the other APK.
- **Your settings come along.** With the classic API, LSPosed kept the app's settings in a folder of its own; with libxposed they live in the app's normal storage. The first time 1.1.0 runs it imports the old settings (needs root, which the app already uses to restart the launcher). Without root, settings go back to defaults.
- Hot reload (applying a module update without restarting the launcher or System UI) is planned for a later beta.

**Fixes**
- **Sliders now reach the launcher right away.** The "settings changed" broadcast required the *receiver* to hold the app's signature permission. System UI passed that check, the launcher did not, and Android dropped the broadcast, so launcher values only applied after restarting it. The broadcast is now sent to each package by name; receivers still only accept it from this app.

**Tested on a Redmi 14C** (HyperOS 3, Android 16, Vector 2.2): both APKs, opening and closing apps with button and gesture, recents, control center glass, settings import. Frame counts are the same with both engines.

**Build:** `local-build/build.sh` now produces both APKs. It compiles against a copy of the libxposed API sources (`local-build/libxposed-stub`, Apache-2.0), which are not packaged. With Gradle, `compileOnly("io.github.libxposed:api:102.0.0")`.

## 1.0.3

**Genie: smoother, and closer to how HyperOS animates on its own**
- **About twice as many frames.** The genie's drawing threads were stuck on the two big CPU cores, which System UI's compositor keeps almost full during an animation. They now use every core, and the drawing thread gets a higher priority.
- **The genie draws on every screen refresh.** Before, it only drew when HyperOS sent a new frame of its spring, and HyperOS skips some of them while an app is starting. In those gaps the genie now predicts where the window is.
- **It starts right away.** The first frame now comes 5-35 ms after the tap (it took 75-550 ms before).
- **No white flash when opening apps in dark mode.** The genie now uses the app's own colour, which HyperOS reports a few frames late. Until then it uses black in dark mode and white in light mode, like the system starting windows. No icon in the middle while it grows either, just like stock HyperOS.
- **Cleaner landing when closing.** The icon takes over earlier, while the window is still about twice the icon's size, as stock HyperOS does. It no longer turns into a dark square.
- **No light frame around the window when it starts closing.** That was HyperOS's own floating icon peeking out from under the genie.
- **Fewer captures while closing.** The periodic refresh is gone: each capture froze the compositor for 25-40 ms just as the window landed.

The curtain and the control center glass threads had the same CPU-core problem, so they benefit too.

**Measured on a Redmi 14C** (Helio G81 Ultra, HyperOS 3, Android 16; classic style, strength 1.0, quality 80, 60 Hz). Numbers come from the module's own animation log and Perfetto traces, over several runs opening and closing Settings in dark mode. A "stutter" is a gap of more than 25 ms between two drawn frames.

| | 1.0.2 | 1.0.3 |
|---|---|---|
| Frames drawn, opening | 20-30 | ~49 |
| Stutters, opening | 11-22 | 0-7 |
| Frames drawn, closing | 13-33 | ~60 |
| Stutters, closing | 11-25 | 2-5 |
| Time to first frame | 75-550 ms | 5-35 ms |
| Drawing cost per frame | 15-60 ms | 4-6 ms |
| Genie thread CPUs | 6-7 (big cores only) | 0-7 |

The stutters that remain are waits behind real-time system threads and the GPU, which is shared with HyperOS's own blur. The module can't avoid those on this phone.

**Build:** `local-build/build.sh` also works with apktool 3, which renamed the bundled aapt2.

## 1.0.2

**Genie**
- **Classic genie is now the default style.** If you never changed the style, you'll get it after updating.
- **Smoother start and landing (classic style).** The shape now grows out of the icon and lands in it gradually, instead of jumping at the very first and last frames.

**Recents**
- **Cards only bend when you move them with your finger** (and during the fling after you let go), not during the recents opening animation.
- The bend fades out instead of disappearing at once.
- Cards that the launcher hides or recycles no longer keep the bend (thanks to the fork by ToolDroidYT, see NOTICE).

**Control center and volume**
- **Fixed: the volume bar kept an old liquid-glass image.** Each window (control center, volume) now uses a capture of what's actually behind it, refreshed every time it appears. Until it's ready, the stock HyperOS blur is shown instead of a stale image.
- **Fixed: the glass disappeared after a while** (confirmed on a Redmi 14C).
  - A single failed capture or draw no longer turns the glass off until the next reboot; it only gives up after several failures in a row.
  - When System UI removes and re-adds the control center window, the glass now keeps tracking it.
  - Discarded views are no longer kept in memory.

## 1.0.1

- **New name: HyperSpring** (formerly HyperTweak). Another HyperOS module already uses that name, so this avoids confusion. The package is still `com.icehome.up`: install over 1.0.0 and your settings are kept.
- **Fixed:** the APK could not be installed on Android 16 (it targeted SDK 30; now 35).

## 1.0.0

First public release. It merges three earlier modules into one app. The package stays `com.icehome.up`, so it installs over Ice Home and keeps its settings.

**Launcher**
- Genie effect when opening and closing apps:
  - A cup shape that adapts to where the icon sits.
  - Three styles: classic, Samsung-like and Ice.
  - Drawn with OpenGL on its own thread.
- Drag to close with lamp physics.
- Bending recents, sinking icons and wallpaper zoom.
- Springy glass folders and a shuffling widget stack.
- Safe mode: after 4 crashes within a minute, the animations stop loading and the app shows a notice.

**System UI**
- Curtain transition when switching screens inside an app.
- Liquid-glass control center with edge refraction and colour dispersion (lens from Kyant's AndroidLiquidGlass).
- Tested with the Mods Center iOS Control Center and the stock one.

**App**
- English and Spanish, following the system language; can be changed in About.
- Every setting has a clear name and an explanation: tap the row to expand it.
- Floating liquid-glass bar:
  - Refracts the whole page, edge lens with slight chromatic aberration.
  - No more half-empty bar during page transitions.
- Animated dot-network background.
- About page:
  - Coral and blue glows behind a translucent logo.
  - Compatibility card (Android, HyperOS, launcher and control center versions, and whether HyperTweak is active in each part).
  - Requirements, credits, language, and a **Copy log** button that adds your device info.
- Restart buttons use root (`am force-stop` / `kill`).
  - Without root, System UI (which HyperOS never freezes) restarts the launcher instead.
  - A soft red notice explains root is needed until it has worked once.
- Feature switches are read once the hooked process has its own context. Before, the settings provider rejected the early query and everything stayed on.

**Removed during development:** home entry animation, ColorOS-style control center opening, icon wave and folder opening from the icon.

---

## 1.0.0 (español)

Primera versión pública. Reúne en una sola app tres módulos anteriores. El paquete sigue siendo `com.icehome.up`, así que se instala encima de Ice Home y conserva sus ajustes. Los cambios son los de arriba.

Lo principal:
- La app está en inglés y español.
- Los interruptores ahora se respetan.
- Los botones de reiniciar usan root, y sin root se reinicia a través de la interfaz del sistema.
- La barra de cristal es nueva, con aberración cromática.
- El fondo de puntos es animado.
- «Acerca de» tiene brillos de color y un logo translúcido.
- Hay un aviso de dispositivo probado: Redmi 14C, HyperOS 3.
