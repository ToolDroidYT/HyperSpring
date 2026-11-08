# HyperTweak: hoja de ruta

Ideas previstas para las próximas versiones. Nada de esto está implementado todavía y los planes pueden cambiar. Las sugerencias son bienvenidas en [Issues](https://github.com/olilio3/HyperTweak/issues).

## Primer paso
- [ ] **Revisar qué incluyen ya HyperCeiler, el HyperOS Launcher Mod de Kashi y otros módulos parecidos** para no duplicar funciones.

## Animaciones
- [ ] **Rebote elástico global**
  - Al llegar al final de una lista, la lista se estira y vuelve con un rebote.
  - Las carpetas rebotan al abrirse. Ojo: ahora solo funciona con el launcher de Kashi y debería funcionar también con el de serie.
  - Un único ajuste de "muelle" (rigidez y amortiguación) compartido por todos los efectos.
  - Ojo: para que el rebote llegue a las listas de *todas* las apps hay que engancharse al framework (`EdgeEffect`) y ampliar los ámbitos en LSPosed. Valorar si se limita al launcher y a la interfaz del sistema.
- [ ] **Recientes con varios estilos y apagado por defecto**
  - Comentarios de la comunidad: el doblado de tarjetas se activa dentro de la propia vista de recientes y queda raro.
  - Estilos posibles: abanico, pila 3D y deslizamiento simple (escala y desenfoque).
- [ ] **Entrada en cascada al desbloquear:** los iconos entran en onda desde el centro o desde el punto tocado.
- [ ] **Onda en el fondo de pantalla** al tocar una zona vacía del escritorio (shader AGSL).
- [ ] **Paralaje del fondo con el giroscopio.**
- [ ] **Efecto goma en los sliders de volumen y brillo:** se estiran al llegar al tope y vuelven.

## Funciones
- [ ] **libxposed API 102 con recarga en caliente:** los ajustes se aplican sin reiniciar el launcher ni la interfaz del sistema. Mantener también el motor antiguo (API 93) para quien use LSPosed viejo.
- [ ] **Vista previa dentro de la app:** ver cada animación antes de activarla.
- [ ] **Perfiles predefinidos:** Suave, Estilo iOS, Rápido y Mínimo, con un toque.
- [ ] **Exportar e importar ajustes** en un archivo (sirve también para los reportes de errores).
- [ ] **Modo rendimiento:** detectar GPUs débiles y bajar la calidad de los shaders automáticamente.

## Hecho
- [x] 1.0.0: el APK se compilaba con targetSdk 30 y Android 16 no dejaba instalarlo. Arreglado en `local-build/build.sh` (ahora targetSdk 35).
