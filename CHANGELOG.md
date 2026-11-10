# Changelog

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

First public release. It merges three earlier modules into one app: Ice Home, Telón and KashiPlus. The package stays `com.icehome.up`, so it installs over Ice Home and keeps its settings.

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

Primera versión pública. Reúne en una sola app tres módulos anteriores: Ice Home, Telón y KashiPlus. El paquete sigue siendo `com.icehome.up`, así que se instala encima de Ice Home y conserva sus ajustes. Los cambios son los de arriba.

Lo principal:
- La app está en inglés y español.
- Los interruptores ahora se respetan.
- Los botones de reiniciar usan root, y sin root se reinicia a través de la interfaz del sistema.
- La barra de cristal es nueva, con aberración cromática.
- El fondo de puntos es animado.
- «Acerca de» tiene brillos de color y un logo translúcido.
- Hay un aviso de dispositivo probado: Redmi 14C, HyperOS 3.
