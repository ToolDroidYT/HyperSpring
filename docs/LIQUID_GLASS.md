# Método "liquid glass" (KashiPlus v52)

Receta probada en HyperOS 3 (centro de control y carpetas). Código: `GlassShader.java` (AGSL común), `CcGlass.java` (centro de control), `GlassFolder.java` (carpetas).

## 1. Qué hay detrás
- Centro de control: captura de lo que hay debajo al empezar a abrir (`IWindowManager.captureDisplay`, excluyendo la propia ventana, ¼ de resolución). Reducida y desenfocada con `GlassShader.shrinkBlur`.
- Launcher: fondo de pantalla (`WallpaperManager`) con center-crop + offsets de página.
- Siempre `GlassShader.linear()` (filtrado bilineal) o se ven cuadraditos.

## 2. Capas (de abajo arriba)
1. **Interior** (bajo el contenido, hook de `View.drawBackground`): lo de detrás, desenfoque 12 dp (CC) / 6 dp (carpetas), saturación 1,55 (círculos 1,3), tono 1,1, luces altas −8 %, velo blanco 3 %, lupa 3,5 %.
2. **Canto** (3 dp): estira el color de hasta 3 dp por FUERA del borde (`stretch=1`, `overshoot=3dp`), saturación extra 0,4, sin blanquear.
3. **Línea de luz**: 0,8 dp, intensidad completa en los primeros 1–2 px (smoothstep, no en el último píxel), 60 % blanco + 40 % color de justo fuera del borde, casi uniforme (0,82 + 0,18 hacia arriba-izquierda). Es la que "mueve los colores" por el contorno.
4. Brillo superior suave (glossy) solo en tarjetas; círculos sin velo ni brillo.
- Fondo del centro de control: desenfoque real del sistema + negro al 22 %.

## 3. Reglas que importan
- Piezas encendidas (blanco/color): sin cristal debajo, solo la línea; conservar su fondo y quitar la mezcla de MIUI.
- La mezcla de MIUI (`setMiViewBlurMode(1)` + colores) oscurece lo que dibuja la vista: quitarla bajo el cristal (llamando a los métodos originales) y restaurarla al encender.
- Fondos dibujados en `onDraw`/imagen (capa 0 del `LayerDrawable` de los iconos, `CCNewTileCardView.blend()`) tapan el cristal: volverlos transparentes cuando la pieza está apagada.
- Solo piezas con esquinas redondeadas reales (≥ 8 dp) y ≥ 34–40 dp; excluir rellenos e iconos de sliders.
- Validar el AGSL antes de compilar (`local-build/agsl_check.py` con skia-python): un nombre repetido rompe todo el shader.

## 4. Estado final (v52)
- Fondo del centro de control: desenfoque real del sistema + negro al 22 %.
- Línea de luz: brillo direccional desde la esquina superior izquierda (0,06 + 1,1·f^1,8, eco 0,18 abajo-derecha), 0,6 dp.
- Sin clipPath en GPU (dentado): formas con antialias propio y máscara SDF en el shader.
- App de ajustes "Kashi+ Tweaks": SharedPreferences servidas por `PrefsProvider`; SystemUI/launcher las releen al recibir la difusión `com.kashiplus.tweaks.TUNE` (permiso de firma) y se aplican al momento. Botones de reinicio de SystemUI y launcher sin root (`Remote.java`).
- Ajustes y valores por defecto: `Tune.java`.

## 5. Siguiente proyecto
Animaciones estilo "Ice Home Up" (Good Lock Home Up) en el launcher de Xiaomi: abrir/cerrar apps con muelle desde el icono, carpetas que se expanden, cajón y recientes que siguen el dedo, panel de ajustes con deslizadores.
