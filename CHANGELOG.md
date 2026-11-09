# Changelog

## 1.0.8-poco

- Recents cards now keep their last bend while a pressed card is held still.
- Added a bidirectional shader blend so the gooey effect eases into normal rendering on release instead of cutting off.
- Keep tracking the card at low residual speeds and invalidate it when the effect is removed to reduce stale preview frames.


## 1.0.7-poco

- Reverted POCO task capture to child-only mode; capturing the transition root produced a persistent white frame on POCO.
- Reduced Recents deformation strength and tuned the spring for a slower, smoother settle instead of an abrupt return to a rectangle.


## 1.0.6-poco

- POCO Genie task captures now include the transition root, not only its child layers, to avoid icon-only/blank app-window animations when the app buffer is attached to the root leash.
- Added one-time capture failure diagnostics to the copied log.
- Recents task-card render effects are cleared when cards are recycled or the recents stack detaches or becomes hidden.


## 1.0.5-poco

- Fixes safe-mode reactivation across HyperOS frozen-process boundaries.
- SystemUI queues a one-shot reset request; POCO Launcher consumes it in its own process and clears its own recovery file.
- Restarts the launcher after queuing the reset and allows more time for status confirmation.


## 1.0.4-poco

- Fixes launcher reactivation when the recovery-state file was not initialized in memory.
- Preserves and displays the legacy safe-mode diagnosis instead of overwriting it with a generic status.
- Verifies the safe-mode reset from the Home screen before reporting success.


## 1.0.3-poco

- POCO Launcher status and logs are now accepted by the settings provider.
- Launcher recovery uses Android process-exit reasons instead of rapid-start counting.
- Intentional restarts initiated from HyperSpring are marked and excluded from crash-loop detection.
- Home displays legacy safe mode and crash-loop diagnostics with a reactivation action.


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
