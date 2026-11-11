# HyperSpring: hoja de ruta

Lo previsto para las próximas versiones. Los planes pueden cambiar. Las sugerencias son bienvenidas en [Issues](https://github.com/olilio3/HyperSpring/issues).

Criterio para entrar aquí: que lo vaya a usar bastante gente, que se pueda mantener cuando cambie HyperOS y que no cueste batería ni fluidez a quien no lo usa. Un efecto llamativo que se desactiva a los dos días no entra.

## Errores conocidos
- Ninguno abierto ahora mismo. Si encuentras uno, abre un issue con el registro (**Acerca de → Copiar registro**) y tus ajustes (**Acerca de → Exportar ajustes**).

## Siguiente
- [ ] **Recarga en caliente (libxposed API 102):** que una actualización del módulo y los interruptores de función se apliquen sin reiniciar el launcher ni la interfaz del sistema.
  - El módulo ya se carga con la API 102; falta que sepa "desmontarse": parar sus hilos, quitar sus listeners y volver a instalar los hooks con el código nuevo.
  - Es lo más delicado de la lista: si se deja algo a medias, se cae la interfaz. Saldrá primero en una beta.
- [ ] **Carpetas con muelle en el launcher de serie.** Ahora el rebote solo funciona con el launcher de Kashi.
- [ ] **Soporte de POCO Launcher**, integrando el trabajo del fork de la comunidad ([ToolDroidYT/hyperspring](https://github.com/ToolDroidYT/hyperspring)).
- [ ] **Compilación automática en GitHub** en cada cambio, para detectar errores antes de publicar. Los APK de las releases se siguen firmando aparte.

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
- [x] 2.0.0: libxposed API 102 con una fachada propia de hooks y dos motores (el antiguo, API 93, sigue como APK aparte). Perfiles de un toque (Suave, Estilo iOS, Rápido, Rendimiento). Exportar e importar ajustes. La tarjeta de compatibilidad dice con qué API se ha cargado el módulo. Los deslizadores llegan al launcher sin reiniciarlo.
- [x] 1.0.3: genio más fluido y más parecido a la animación de serie de HyperOS (hilos en todos los núcleos, dibujo en cada refresco con predicción, color de la app al abrir, aterrizaje en el icono como MIUI). Detalles en el CHANGELOG.
- [x] 1.0.2: el cristal del centro de control dejaba de funcionar al cabo de un rato. Las tarjetas de recientes ya solo se doblan al moverlas con el dedo.
- [x] 1.0.0: el APK se compilaba con targetSdk 30 y Android 16 no dejaba instalarlo. Arreglado en `local-build/build.sh` (ahora targetSdk 35).
