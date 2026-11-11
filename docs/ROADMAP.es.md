# HyperSpring: hoja de ruta

Ideas previstas para las próximas versiones. Nada de esto está implementado todavía y los planes pueden cambiar. Las sugerencias son bienvenidas en [Issues](https://github.com/olilio3/HyperSpring/issues).

## Primer paso
- [ ] **Revisar qué incluyen ya HyperCeiler, el HyperOS Launcher Mod de Kashi y otros módulos parecidos** para no duplicar funciones.

## Errores conocidos
- Ninguno abierto ahora mismo. Si encuentras uno, abre un issue con el registro (**Acerca de → Copiar registro**).

## Animaciones
- [ ] **Rebote elástico global**
  - Al llegar al final de una lista, la lista se estira y vuelve con un rebote.
  - Las carpetas rebotan al abrirse. Ojo: ahora solo funciona con el launcher de Kashi y debería funcionar también con el de serie.
  - Un único ajuste de "muelle" (rigidez y amortiguación) compartido por todos los efectos.
  - Ojo: para que el rebote llegue a las listas de *todas* las apps hay que engancharse al framework (`EdgeEffect`) y ampliar los ámbitos en LSPosed. Valorar si se limita al launcher y a la interfaz del sistema.
- [ ] **Recientes con varios estilos y un interruptor para apagarlo** *(1.0.2: las tarjetas ya solo se doblan al moverlas con el dedo)*
  - Los comentarios están divididos: a algunos el doblado les parece raro y a otros les encanta (y funciona también con navegación por botones). Se queda, pero será fácil apagarlo o cambiarlo por otro estilo.
  - Estilos posibles: abanico, pila 3D y deslizamiento simple (escala y desenfoque).
- [ ] **Entrada en cascada al desbloquear:** los iconos entran en onda desde el centro o desde el punto tocado.
- [ ] **Onda en el fondo de pantalla** al tocar una zona vacía del escritorio (shader AGSL).
- [ ] **Paralaje del fondo con el giroscopio.**
- [ ] **Efecto goma en los sliders de volumen y brillo:** se estiran al llegar al tope y vuelven.

## Funciones
- [ ] **Recarga en caliente con libxposed API 102:** las actualizaciones del módulo y los interruptores de función se aplican sin reiniciar el launcher ni la interfaz del sistema. *(1.1.0-beta1: el módulo ya se carga con la API 102; falta la recarga en caliente.)*
- [ ] **Vista previa dentro de la app:** ver cada animación antes de activarla.
- [ ] **Perfiles predefinidos:** Suave, Estilo iOS, Rápido y Mínimo, con un toque.
- [ ] **Exportar e importar ajustes** en un archivo (sirve también para los reportes de errores).
- [ ] **Modo rendimiento:** detectar GPUs débiles y bajar la calidad de los shaders automáticamente.

## Hecho
- [x] 1.1.0-beta1: libxposed API 102 con una fachada propia de hooks y dos motores; el motor antiguo (API 93) sigue como APK aparte para LSPosed viejos. Los deslizadores ya llegan al launcher sin reiniciarlo.
- [x] 1.0.3: genio más fluido y más parecido a la animación de serie de HyperOS (hilos en todos los núcleos, dibujo en cada refresco con predicción, color de la app al abrir, aterrizaje en el icono como MIUI). Detalles en el CHANGELOG.
- [x] 1.0.2: el cristal del centro de control dejaba de funcionar al cabo de un rato. Confirmado en el móvil de pruebas.
- [x] 1.0.0: el APK se compilaba con targetSdk 30 y Android 16 no dejaba instalarlo. Arreglado en `local-build/build.sh` (ahora targetSdk 35).
