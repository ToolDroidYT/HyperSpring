# HyperSpring: notas para Claude Code

Módulo LSPosed para HyperOS (antes HyperTweak). Añade animaciones al launcher y a la interfaz del sistema, y cristal líquido al centro de control. Lo mantiene **olilio3** (github.com/olilio3/HyperSpring), que está aprendiendo Java. **Responde siempre en español** y explica lo que cambias de forma sencilla.

## Lo básico

- **Paquete:** `com.icehome.up`. **No cambiarlo nunca**: así se actualiza encima de versiones anteriores y se conservan los ajustes.
- **API de Xposed:** la antigua (93, `de.robv.android.xposed`). La entrada es `MainHook` (`app/src/main/assets/xposed_init`).
- **Ámbitos:** `com.miui.home` (launcher), `com.android.systemui` y `miui.systemui.plugin` (centro de control y volumen).
- **Móvil de pruebas:** Redmi 14C (Helio G81 Ultra, Mali-G52), HyperOS 3, Android 16, con los mods de Kashi (HyperOS Launcher Mod 7.5 e iOS Control Center). Sin dispositivo aquí: todo lo visual se valida en el móvil del usuario.
- **Versión:** cambia a la vez `Version.java` (`NAME`, `CODE`) y `app/build.gradle.kts` (`versionName`, `versionCode`). Añade la entrada en `CHANGELOG.md` y la versión en los dos README.

## Estructura (`app/src/main/java/com/icehome/up/`)

- **Launcher:**
  - `WindowAnim.java`: hooks de la animación de ventanas de MIUI. Decide qué animaciones llevan genio (`OPEN_FROM_HOME`, `CLOSE_TO_HOME`, `CLOSE_TO_DRAG`).
  - `Genie.java`: sesión del genio (estado, capturas, física de lámpara, forma en `shape()`).
  - `GlRenderer.java`: OpenGL, shaders del genio (`VERTEX`, `FRAGMENT_*`).
  - `RecentsCurtain.java`: tarjetas de recientes que se doblan (AGSL).
  - `FolderMorph`, `GlassFolder`, `StackSwipe`, `IconPress`, `WallpaperZoom`, `Springs`: el resto de efectos.
- **Interfaz del sistema:**
  - `telon/`: transición de cortina y modo seguro de SystemUI.
  - `cc/CcGlass.java` + `cc/GlassShader.java`: cristal del centro de control (y del volumen).
- **App de ajustes:**
  - `SettingsActivity.java`: toda la interfaz, sin XML.
  - `I18n.java`: textos en español e inglés. Todo texto visible pasa por `I18n.t(es, en)`.
  - `Tune.java` / `telon/Tune.java` / `cc/Tune.java`: ajustes y sus valores por defecto. La tabla `ITEMS` es la fuente de verdad.
  - `PrefsProvider.java`: guarda los ajustes. `Remote.java`: avisos entre la app y los procesos enganchados.
- **Registro:** `Log.java` / `telon/Log.java`. Se ve en la app con **Acerca de → Copiar registro**. Es lo que piden los usuarios en los issues.

## Compilar

- **Con Android SDK:** `./gradlew assembleRelease`.
- **Sin SDK:** `TOOLS=/ruta bash local-build/build.sh`.
  - Necesita JDK 17, `android.jar` (API 35), jadx, apktool y uber-apk-signer.
  - El APK sale en `local-build/out/HyperSpring.apk`.
- **Firma, importante:**
  - Las releases publicadas están firmadas con la **clave de depuración de uber-apk-signer**.
  - Un APK de Gradle se firma con la clave de depuración de cada ordenador, así que **no se instala encima** de las versiones publicadas.
  - **Antes de publicar, firma con uber-apk-signer** (como hace `build.sh`) o habla con el usuario de pasar a una clave de release privada. Eso obligaría a todos a reinstalar una vez. Una clave privada es más segura: la de depuración es pública y cualquiera podría firmar una "actualización" falsa.
  - Nunca subas un keystore al repo.
- **Shaders, valídalos siempre antes de compilar:**
  - **AGSL:** `python3 local-build/agsl_check.py <archivo.java>`. Necesita `pip install skia-python`.
  - **GLSL del genio:** `python3 local-build/gl_check.py`. Necesita Mesa; en Windows, usa WSL.

## Cómo trabajar aquí

- **Cada función se instala por separado y en `try/catch`:** si un hook falla, se anota en el registro y lo demás sigue funcionando. Mantén ese patrón.
- **Nunca bloquees ni lances excepciones en un hook de SystemUI o del launcher.** Un fallo repetido activa el modo seguro y apaga esa parte.
- **Hilos:**
  - El genio dibuja en `IceHome-genie` y captura en `IceHome-capture`.
  - El centro de control captura en `KashiPlusGlass`.
  - Nada de trabajo pesado en el hilo principal.
- **Hilos nuevos, siempre con `Threads.start`:** un hilo hereda las CPU permitidas de quien lo crea, y durante una animación HyperOS ata el hilo principal a los núcleos grandes. Con `new HandlerThread` en mitad de una animación, el hilo se queda atado a cpu6-7 para siempre (era la causa de los tirones del genio).
- **Medir antes de cambiar:** el registro de cada animación da fotogramas, tirones y tiempos. Para ir más a fondo, una traza de perfetto (`perfetto ... sched freq gfx view -a com.miui.home`) enseña si un hilo espera CPU (estado R) o búfer.
- **Ajustes reales:** LSPosed los guarda en `/data/misc/<id>/prefs/com.icehome.up/`, no en `shared_prefs` de la app. Si los tocas con root, escribe con `cat >` (conserva dueño y etiqueta SELinux), nunca con `sed -i`.
- **Listeners de `ViewTreeObserver`:** se pierden si la ventana se quita y se vuelve a poner. Vuelve a registrarlos con `OnAttachStateChangeListener`, como en `CcGlass.watchRoot` y `Skin.track`.
- **Comentarios:** en español, explicando el porqué. El usuario aprende leyéndolos.
- **Vídeos y capturas para el README:** revisa que no salgan datos personales (nombre de la cuenta, red Wi-Fi, notificaciones) antes de publicarlos.
- **Licencia Apache-2.0.** Si incorporas código de otro proyecto o fork, acredítalo en `NOTICE` y en el `CHANGELOG`. Ya hay un fork comunitario con soporte para POCO Launcher: github.com/ToolDroidYT/hyperspring.

## Pendiente

- **Hoja de ruta completa:** `docs/ROADMAP.md` y `docs/ROADMAP.es.md`.
- **libxposed API 102 con recarga en caliente:**
  - El plan: una fachada propia de hooks con dos motores (API 93 y 102) en la 1.1.0-beta1, y la recarga en caliente en una beta posterior.
  - Un módulo que declare la API 102 no puede usar la antigua.
- **Confirmado en el móvil:** las correcciones de la 1.0.2 funcionan (volumen, centro de control, genio clásico, recientes).
- **Navegación:** no está comprobado qué animación usa MIUI al volver al escritorio con el gesto o el botón de atrás en la última pantalla de una app. El registro anota cada tipo de animación que ve ("animación X por Y" y "sin genio en X"). Pide ese registro antes de añadir tipos nuevos en `WindowAnim.onFrame`.
