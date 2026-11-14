# HyperSpring: hoja de ruta

Lo previsto para las próximas versiones. Los planes pueden cambiar. Las sugerencias son bienvenidas en [Issues](https://github.com/ToolDroidYT/HyperSpring/issues).

Criterio para entrar aquí: que lo vaya a usar bastante gente, que se pueda mantener cuando cambie HyperOS y que no cueste batería ni fluidez a quien no lo usa. Un efecto llamativo que se desactiva a los dos días no entra.

## Errores conocidos
- [x] **Bienvenida** (arreglado en la 2.2.0): en la primera pantalla, el texto se mete por debajo del icono. Idea: que el icono empuje las letras al bajar, y que estas se desenfoquen hasta desaparecer.
- [x] **Acerca de** (arreglado en la 2.2.0): el icono que flota se corta al llegar arriba.
- Si encuentras uno, abre un issue con el registro (**Acerca de → Copiar registro**) y tus ajustes (**Acerca de → Exportar ajustes**).

## Siguiente
- [ ] **Abrir y cerrar apps mejor que nunca:** pulir la animación hasta que parezca hecha por la propia Xiaomi y sea un espectáculo visual.
  - **Sin saltos bruscos:** ahora la ventana tarda en capturarse (~100 ms en gama media), y por eso a veces el primer fotograma llega tarde y la ventana pega un salto. La captura tiene que estar lista antes de que empiece a moverse, y el paso entre la ventana real y la nuestra no se debe notar.
  - **Como de serie:** mismos muelles, tiempos, esquinas y sombras que HyperOS, para que nadie note que es un módulo.
  - **Un espectáculo:** más física y más luz en los estilos (sobre todo en Profundidad), sin perder fluidez en móviles de gama media.
- [ ] **Recarga en caliente (libxposed API 102):** que una actualización del módulo y los interruptores de función se apliquen sin reiniciar el launcher ni la interfaz del sistema.
  - El módulo ya se carga con la API 102; falta que sepa "desmontarse": parar sus hilos, quitar sus listeners y volver a instalar los hooks con el código nuevo.
  - Es lo más delicado de la lista: si se deja algo a medias, se cae la interfaz. Saldrá primero en una beta.
- [ ] **Carpetas con muelle en el launcher de serie.** Ahora el rebote solo funciona con el launcher de Kashi.
- [ ] **Soporte de POCO Launcher**, integrando el trabajo del fork de la comunidad ([ToolDroidYT/hyperspring](https://github.com/ToolDroidYT/hyperspring)).
- [ ] **Compilación automática en GitHub** en cada cambio, para detectar errores antes de publicar. Los APK de las releases se siguen firmando aparte.

## Planeado
Ideas apuntadas el 11-11-2026. Están sin empezar y sin orden fijo. Antes de hacer las del escritorio, hay que revisar qué trae ya el Launcher Mod de Kashi.

### Cristal líquido
- [ ] **Dock de cristal:** el dock del launcher como barra flotante con refracción en el borde, con el mismo shader que la barra de la app.
- [ ] **Notificaciones de cristal:** las tarjetas de la sombra y la notificación emergente (heads-up), con desenfoque y borde iluminado. Para que no pese, todas las tarjetas tienen que usar una sola captura del fondo.
- [ ] **Refracción en las carpetas:** añadir la lente del borde al desenfoque que ya tienen.
- [ ] **Lupa en los sliders del CC:** al arrastrar el brillo o el volumen, el control se convierte en una gota de cristal que aumenta lo que tiene debajo. Ojo: depende de clases del plugin de SystemUI que cambian entre versiones.
- [ ] **Estilos de cristal:** Transparente, Ahumado, Esmerilado, Tinte Monet y Prisma (con más dispersión), elegibles con un toque.
- [ ] **Brillo que sigue al dedo:** el reflejo del borde del cristal se mueve hacia donde tocas. Sin giroscopio, así que no gasta batería.

### Animaciones
- [ ] **Notificaciones con animación de genio, o una parecida, al estilo de iOS:** al abrir una notificación la app sale de la tarjeta; al descartarla, la tarjeta se recoge.
- [ ] **Notificaciones con muelle:** la emergente entra con un pequeño rebote.
- [ ] **Fundido al abrir el CC** con una animación al estilo de ColorOS 17.
- [ ] **Genio hacia la carpeta:** al cerrar una app que está dentro de una carpeta, la ventana vuelve a la carpeta.
- [ ] **Genio en los widgets:** al abrir una app desde un widget, la ventana sale del widget.
- [ ] **Gesto de atrás predictivo con físicas:**
  - En el Redmi 14C, HyperOS no trae animaciones de atrás predictivas, así que hay que crearlas también, no solo cambiar las que ya existen.
  - Mientras se arrastra el gesto, la pantalla se encoge y se inclina siguiendo el dedo (con la física del telón). Al soltar, vuelve con muelle o se cierra.
- [ ] **Transiciones entre páginas del escritorio** en las que los iconos se estiran según la velocidad del deslizamiento.
- [ ] **Modo oscuro con onda circular:** al cambiar el tema, la paleta nueva se extiende en círculo desde el botón del CC.
- [ ] **Zoom suave al desbloquear:** el escritorio entra con una escala ligera, sin animar cada icono.

### Funciones
- [x] **Lista de apps excluidas del genio:** las apps elegidas se abren y se cierran con la animación de HyperOS. Se aplica al momento. *Publicado en la 2.1.0.* Falta lo mismo para la cortina de la interfaz del sistema.
- [ ] **Pausa automática:** los efectos pesados se apagan solos con el ahorro de batería, en juegos (Game Turbo) o con el móvil caliente.
- [ ] **Vibración sutil** sincronizada con las animaciones: cuando el genio aterriza en el icono, cuando rebota una carpeta, cuando cambia la pila de widgets.
- [x] **Editor visual del muelle:** una gráfica animada de la curva mientras mueves Velocidad y Rebote, con un móvil en miniatura que abre una ventana con ese muelle. *Publicado en la 2.1.0.*
- [ ] **Tile en los ajustes rápidos** para activar o desactivar HyperSpring y reiniciar el launcher.
- [x] **Aviso de versión nueva:** consulta las releases de GitHub (se puede desactivar), y descarga e instala desde la app. *Publicado en la 2.1.0.*
- [x] **Novedades al actualizar:** al abrir la app tras actualizarla, una pantalla animada con lo nuevo. *Publicado en la 2.1.0.*

### La app
- [ ] **Interfaz renovada, muy animada**, como las webs modernas: entradas escalonadas, animaciones al desplazarse, transiciones compartidas entre pantallas y microinteracciones en cada control. Siempre a 60 fps en gama media y respetando el ajuste del sistema que quita las animaciones.

## Más adelante, si hay demanda
- [ ] **Calidad automática:** bajar sola la resolución del genio cuando el registro detecte muchos tirones seguidos. Por ahora lo cubre el perfil «Rendimiento».
- [ ] **Vista previa dentro de la app.** Obliga a duplicar las animaciones fuera del launcher; con los perfiles y los cambios en vivo, el beneficio es pequeño.
- [ ] **Revisar qué incluyen HyperCeiler y el HyperOS Launcher Mod de Kashi** antes de añadir funciones nuevas, para no duplicar.

## Descartado
Ideas que estaban en la lista y se han quitado, con el motivo:
- **Rebote elástico en las listas de todas las apps.** Habría que engancharse al framework de Android en cada app: mucho riesgo y mucho ámbito para un efecto pequeño.
- **Paralaje del fondo con el giroscopio.** Mantiene un sensor encendido en el escritorio: gasta batería a cambio de un adorno.
- **Onda en el fondo al tocar** y **entrada en cascada al desbloquear.** Efectos vistosos que cansan pronto y añaden trabajo a la GPU justo en los momentos en que el móvil ya va cargado.
- **Efecto goma en los sliders de volumen y brillo.** Depende de clases del plugin de SystemUI que cambian en cada versión de HyperOS; mucho mantenimiento para un detalle.
- **Más estilos de recientes (abanico, pila 3D).** Lo que pedía la gente era poder apagar el doblado, y eso ya está (**Inicio → Recientes que se doblan**).

## Hecho
- [x] 2.1.0: estilo Profundidad (la ventana como una hoja rígida que se inclina, con estela de movimiento), selector de estilo en menú desplegable, editor visual del muelle, apps sin genio, actualizaciones desde la app y app rediseñada. Detalles en el CHANGELOG.
- [x] 2.0.0: libxposed API 102 con una fachada propia de hooks y dos motores (el antiguo, API 93, sigue como APK aparte). Perfiles de un toque (Suave, Estilo iOS, Rápido, Rendimiento). Exportar e importar ajustes. La tarjeta de compatibilidad dice con qué API se ha cargado el módulo. Los deslizadores llegan al launcher sin reiniciarlo.
- [x] 1.0.3: genio más fluido y más parecido a la animación de serie de HyperOS (hilos en todos los núcleos, dibujo en cada refresco con predicción, color de la app al abrir, aterrizaje en el icono como MIUI). Detalles en el CHANGELOG.
- [x] 1.0.2: el cristal del centro de control dejaba de funcionar al cabo de un rato. Las tarjetas de recientes ya solo se doblan al moverlas con el dedo.
- [x] 1.0.0: el APK se compilaba con targetSdk 30 y Android 16 no dejaba instalarlo. Arreglado en `local-build/build.sh` (ahora targetSdk 35).
