# Notas técnicas

Detalles de cómo funciona cada parte, para quien quiera leer o tocar el código. Para instalar y usar HyperSpring, mira el [README](../README.md).

## Escritorio (`com.miui.home`)

Animaciones para el **HyperOS Launcher Mod V7.5 de Kashi** (`com.miui.home`), sin modificar su APK.

## 0. Abrir y cerrar apps con efecto genio (`Genie.java`, `GlRenderer.java`, `WindowAnim.java`)

Estilo "Ice Home Up": al abrir, la ventana sale del icono con forma de copa (el extremo lejano se adelanta, el cercano se queda pegado al icono) y al cerrar cae en él igual. Desde el dock sale torcida hacia el icono.

**Cómo funciona**
- La animación de MIUI no llega a verse: durante todo el efecto se dibuja una capa propia (hija de la ventana del launcher) y la ventana real y el icono flotante de MIUI se ocultan.
- Cada fotograma del muelle de MIUI (`LocalWindowAnimImplementor.updateElementValue`, común al camino normal y al de arranque rápido) da el rectángulo, el radio y el tipo (`OPEN_FROM_HOME`, `CLOSE_TO_DRAG`, `CLOSE_TO_HOME`).
- **Abrir, antes de que exista la ventana** (arranque rápido de HyperOS): el contenido es el color de la app con su icono en el centro, como su pantalla de inicio.
- **Con ventana**: el contenido se captura en otro hilo (`ScreenCapture.captureLayers`, solo hijos, sin la propia capa ni los iconos flotantes), espaciado para no cargar el compositor, y entra sin copias en una `SurfaceTexture` (`Surface.attachAndQueueBufferWithColorSpace`, como la animación de girar la pantalla). El cambio entre la ventana real y la capa se hace dentro de la transacción de MIUI (`SurfaceParams.applyTo`): sin fotogramas vacíos.
- **Dibujo con OpenGL ES en un hilo y contexto propios** (`GlRenderer`), a la resolución del ajuste «Resolución del genio» (50-100 %, 75 % por defecto; también la de las capturas) y escalado a pantalla completa por el compositor. Presupuesto de capturas (en un Helio G81 Ultra, Mali-G52 MC2, cada una ocupa al compositor 25-80 ms): como mucho al 50 % de resolución; al abrir en vivo, una cada 200 ms; sin en vivo, la primera y otra a los 300 ms; al cerrar, la primera y un refresco cada 700 ms; además el compositor queda libre al menos 1,5 veces lo que costó la última y la primera al cerrar se hace al 35 % para que el genio aparezca antes. Antes de la primera captura, la pantalla provisional (color de la app + icono) se hace en el propio shader. El hilo de render del launcher (HWUI) está ocupado con su desenfoque mientras abre o cierra apps: dibujar ahí costaba 15-50 ms por fotograma en un móvil de gama de entrada. Capa, contexto y programas se crean una vez y se preparan al volver al escritorio.
- **Arrastrar para cerrar, como una lámpara**: el extremo cercano sigue al dedo y el lejano cuelga de él con un muelle (`Genie.lamp`): se queda atrás, se inclina en curva y se balancea al parar. Al soltar, las físicas se apagan en 350 ms mientras la copa vuela al icono. Intensidad en «Físicas de lámpara» (0-2).
- **Copa al revés con iconos arriba**: la copa se estrecha hacia el icono; con el icono en la mitad de arriba de la pantalla, hacia arriba (`flip`, que pasa de un sentido a otro sin saltos entre el 40 % y el 60 % de la altura). El centro de cada fila va hacia el icono con un peso pw^((lag−1)·n²): curva suave sin esquina (con la esquina de antes, al cerrar en diagonal la copa se doblaba como una camiseta).
- **Usar el escritorio mientras se cierra una app**: durante la transición de cierre el sistema manda los toques a la app que se va. `TapCatcher` pone una ventana invisible a pantalla completa (salvo la barra de estado y la zona del gesto de abajo) que pasa los gestos a la ventana del launcher; en el icono de la app que se cierra, un toque la reabre. Si se abre otra app (también desde un widget, sin genio), la que se cierra sigue sola hasta su icono por debajo de la nueva. Seguridad: si la animación deja de pedir la ventana, se quita sola.
- **Recientes como cortinas rígidas** (`RecentsCurtain`, Android 13+): cada fotograma se mide cuánto se mueve cada tarjeta (`TaskView`) en pantalla; se dobla hacia el lado contrario (parábola: el centro adelantado respecto a arriba y abajo) según su velocidad, con un muelle duro que la endereza con un pequeño balanceo. Shader AGSL como `RenderEffect`; la tarjeta se estrecha lo que se dobla para que nada se salga de sus límites. Quietas, sin efecto. Dos estilos: cortina (se dobla el centro) y telón (arriba fija, se dobla la parte de abajo).
- **Detalles**: vibración muy suave (primitiva `LOW_TICK` a baja intensidad, respeta el ajuste del sistema) cuando el genio llega al icono (`Haptics`); el icono se hunde al pulsarlo y vuelve con rebote (`IconPress`); la primera captura de la app entra fundida sobre la pantalla provisional (`fade` en el shader de ventana).
- **Zoom del fondo** (`WallpaperZoom`): MIUI ya acerca el fondo al abrir y lo aleja al volver, pero solo con nivel de potencia ≥ 4 (`ComputilityConfig.WallpaperStrategy`); se enciende y se ajusta su intensidad.
- **Carpetas que crecen desde su icono** (`FolderGrow`): MIUI usa una animación simple (solo fundido) sin desenfoque del sistema y con carpetas 2×2 de 9; se fuerza la completa, en la que el fondo del icono crece hasta la carpeta.
- **Ritmo de dibujo**: el primer fotograma se dibuja en cuanto llega; los siguientes, en el refresco de pantalla siguiente (Choreographer en el hilo de dibujo) con el último estado, como mucho a «Fotogramas por segundo del genio» (60 por defecto). Con pantallas de 90-120 Hz y GPU modesta, la mitad de trabajo sin perder fluidez.
- **Caché de la última imagen de cada app**: la captura al empezar el gesto de cerrar se guarda (4 apps, ~1 MB de GPU cada una). Al tocar el icono de una app cuya tarea sigue en recientes (Android enseñará al abrir su instantánea), esa imagen se sube junto al icono y el genio sale con el contenido real desde el primer fotograma, no con la pantalla provisional.
- **Precarga**: al empezar el gesto de cerrar, el sistema le da la ventana al launcher (`RecentsAnimationListenerImpl.onAnimationStart`) antes de que se mueva; se captura ahí (`Genie.precapture`) y el primer fotograma del genio la usa sin esperar. Al poner el dedo en un icono (`ShortcutIcon.onTouchEvent`), su imagen se sube a la GPU y la capa se prepara (`Genie.prepareIcon`); al abrir, si el icono de la apertura es ese, no hay que subirlo. El registro lo indica con «captura precargada» / «icono precargado».
- **Dos animaciones a la vez**: si se abre otra app mientras una se cierra, MIUI abandona el cierre; el genio de la que se cierra sigue solo (`Genie.Ghost`) hasta su icono con un muelle por borde que parte de la velocidad que llevaba, con su propio contenido (`GlRenderer.Content`, separado del que recibe las capturas nuevas) y dibujado en la misma capa, debajo del genio de la que se abre (alfa premultiplicado). El icono real se mantiene oculto hasta que llega (`ShortcutIcon.setIconVisibility`). Fotogramas: los del otro genio, o los suyos propios con Choreographer en el hilo de dibujo.
- **Reabrir durante el cierre**: mientras la app vuelve al icono hay una ventanita invisible encima del icono (`TapCatcher`, tipo panel de la barra de navegación, como la barra de gestos de MIUI). Si la app se cerró poco después de abrirla, su transición aún no ha terminado y el sistema manda los toques a la app, no al launcher; con la ventanita el toque llega siempre y se hace lo mismo que MIUI al tocar ese icono: `StateManager.animToFullScreen` y estado App (o abrirla de nuevo si MIUI ya no puede reutilizar la animación). Antes: MIUI no convierte en clic un toque en el icono mientras la app vuelve a él; si se levanta el dedo sobre ese icono y MIUI no dio el clic, `WindowAnim.hookReopenTap` llama a `BaseLauncher.onClick` con el icono destino y el camino normal de MIUI (`StateManager.animToFullScreen`) le da la vuelta a la animación. MIUI solo la reutiliza si el icono tocado es la misma vista a la que vuelve la app (`WindowElement.isSameElement`); un clic que empezó sobre ese icono cuenta como el mismo, para que no la lance de cero. Además, si se toca el icono de la app que se está cerrando, la apertura nueva manda al instante aunque MIUI la lleve con otro animador; el genio da la vuelta desde donde esté. Otra app distinta empieza su propio genio.
- **Interrupciones** (abrir y arrastrar a mitad, cerrar y volver a tocar): un solo genio por gesto. No se reinicia: sigue con la misma imagen y la misma forma, el extremo cercano se desliza entre icono y dedo y la forma de abrir y la de cerrar se funden en 150 ms; el muelle de MIUI conserva la velocidad. Si MIUI pasa la animación a otro animador, el genio sigue igual.
- Al cerrar se captura cada pocos fotogramas (el contenido apenas cambia): el compositor queda libre.
- Un único fragment shader (GLSL ES 1.00): deformación analítica e invertible, sin malla. Validado y simulado con el OpenGL ES de Mesa (`local-build/gl_check.py`).
- **El genio sale del icono y vuelve a él**: el icono de la app es una textura más; en el último tramo (anchura 3-20 %) la forma es la del icono y el contenido se funde con su imagen. El icono flotante de MIUI no se ve.
- **Robustez**: la capa se vuelve a colgar de la superficie del launcher en cada animación (Android la rehace al volver del segundo plano en el mismo objeto); animaciones en paralelo de dos ventanas: gana la más reciente, cada una con su contenido e icono; con el dedo quieto no se da la animación por terminada; si el sistema cancela la transición, se termina limpio; cada captura lleva la marca de su animación; tras 3 fallos seguidos el genio se aparta 5 s.
- Se desactiva el motor de animación de SurfaceFlinger cambiando los campos de `SfAnimCapability` (el getter lo incrusta ART): con él el launcher no recibe los fotogramas.
- Si el inicio se escala, la capa se compensa en la misma transacción (`HomeLayerElement.bindHomeLeash` + `TransactionCompat.setMatrix`).
- Sin reflexión en el camino rápido cuando no hay genio. Solo en vertical, con una sola ventana y fuera del cambio rápido entre apps.

**Ajustes** (app Ice Home): genio sí/no (reiniciar launcher), intensidad (0 = rectángulo normal), contenido en vivo, velocidad y rebote de los muelles (`Springs.java`). La sección Registro muestra y copia lo que anota el launcher.

**Registro**: cada animación anota `genio abrir/cerrar: N fotogramas en X ms, captura Y ms (Z fallidas), dibujo W ms`.

## 1. Pila de widgets (`StackSwipe.java`)

**Baraja visible.** Detrás de la tarjeta activa se ven las siguientes en diagonal (arriba-derecha, 8×10 dp por nivel), cada una un 3 % más pequeña, un 18 % más transparente y con 7 dp más de desenfoque. Cada tarjeta tiene su propio contorno redondeado con el radio de MIUI (`RoundedCornerEnforcement.computeEnforcedRadius`) y ningún contenedor entre la pila y la página recorta, así la baraja se ve también en reposo y no asoman esquinas raras. Al tocar, la baraja se abre un poco.

**Arrastre libre en cualquier dirección** (también horizontal):
- La tarjeta sigue al dedo 1:1; pasado un radio de 120 dp, goma logarítmica.
- Escala 0,95, inclinación según la velocidad (máx. 6°) y estiramiento según distancia y velocidad.
- Vibración al cruzar el umbral de envío; la siguiente tarjeta se adelanta. Las de detrás siguen al dedo un 8 %.

**Al soltar**, física por fotograma que conserva la velocidad del dedo:
- Si supera el 50 % del radio o hay *fling* hacia fuera: la tarjeta sigue su inercia, hace un arco, pasa por detrás cuando ya vuelve y acaba en el último nivel de la baraja con un micro-rebote de unos 5 dp.
- Si no: vuelve a su sitio con sobreimpulso.

Durante el gesto se aplican las transformaciones directamente (sin el render de Kashi), con capas por hardware; toda la jerarquía sube su Z para que la tarjeta pase por encima de iconos, dock y buscador.

## 2. Carpetas (`FolderMorph.java`)

Como en HyperOS 4: la carpeta salta entre los diseños reales de cada tamaño.
- El dedo se mide siempre respecto a la carpeta del **inicio del gesto** y se proyecta sobre los estados válidos: 1x1, 2x1, 1x2, 2x2 y 4x2 que caben en la rejilla, en celdas libres y en la página.
- Se elige el estado más cercano **con histéresis** (0,22 celdas): en la frontera no alterna.
- El cambio se aplica en cuanto se decide (sin el retardo `readyAt/lastApply` de Kashi), de uno en uno.
- El contenedor persigue ese tamaño con un muelle de rigidez 400 y amortiguación 0,8, con un tirón elástico hacia el dedo de 0,18 celdas como máximo en cualquier dirección.
- **Los iconos no se regeneran**: durante todo el gesto los dibuja una capa persistente (`IconLayer`) con los iconos de la carpeta; los reales, que Kashi recrea y recarga en cada cambio de tamaño, quedan ocultos debajo. En cada cambio de diseño cada icono vuela con muelle a su nuevo sitio, los que entran aparecen con zoom y rebote y los que sobran se encogen. Al final, con todo asentado y los iconos reales cargados, se cambian por los reales en el mismo punto.
- **Nunca se recorta un icono**: al escalar, cada icono tiene una escala uniforme que nunca supera la del contenedor.
- Al soltar, se asienta en el tamaño final con el mismo muelle.

## 3. Cristal suave en carpetas (`GlassFolder.java` + `GlassShader.java`)

Material de HyperOS 4 sobre el fondo real de cada carpeta (por debajo de los iconos), respetando las texturas avanzadas:
- **Con texturas avanzadas**: el interior muestra el fondo de pantalla con un desenfoque propio de 6 dp (menor que el del sistema) y una lupa leve; en una franja de 14 dp el canto lo **refracta** con dispersión de color.
- **Sin texturas avanzadas**: fondo plano del sistema; solo el canto refracta.
- **Bordes iluminados con el color de detrás** (1,9 dp, más fuertes arriba-izquierda, eco abajo-derecha) más un brillo interior teñido: el cristal llega hasta el canto.
- Velo blanco suave, brillo especular y filo blanco fino. Con fondos animados o sin AGSL, vuelve al cristal sin refracción.

Todos los valores ajustables están al principio de cada fichero.

## Métodos enganchados (por si cambian en otra versión del launcher)

Genio: `LocalWindowAnimImplementor.updateElementValue/getLastAnimType/getCurrentRectFParams` y campo `windowAnimContext`, `LocalWindowAnimImplementor$rectFSpringAnimListener$1.onAnimationEnd/onAnimationCancel`, `WindowAnimContext.getRemoteAnimationTargetSet/getFloatingIcons`, `RemoteAnimationTargetSet.getFirstTarget/getHomeSurfaceControlCompat/hasMultiTask`, `RemoteAnimationTargetCompat.leash/position`, `SyncRtSurfaceTransactionApplierCompat$SurfaceParams.applyTo` y campos `surface, alpha, flags`, `TransactionCompat.setMatrix` y `mTransaction`, campos `SfAnimCapability.shouldUseSfAnim/shouldUseSfBigIconAnim/useMultiLayersForSfAnim`, `SpringParamsProvider$Companion.generateSpringBundlesByAnimType`.
Pila: `WidgetStack$Layer.render/layoutPages/release/touch/viewsOf/height/hideDotsLater/cancelChildren/showDots`, `WidgetStack.save` y los campos `pages, cur, key, settle, front, showAll, base, tracking, dragging, downY, slop`.
Carpetas: `FolderIconConvertSizeController.convertFolderSize`, `BaseLauncher.bindFolderResize/dispatchTouchEvent`, `ResizeHandle.sOverlay`, `ResizeHandle$Overlay.apply/sizes/target/onPreDraw/close/dispatchTouchEvent` y los campos `item, ghost, resizing, info, cells, scale, wantX, wantY, readyAt, lastApply`, `Kx.iconBounds`, `CellLayout.mOccupiedCell/mHCells/mVCells`.
