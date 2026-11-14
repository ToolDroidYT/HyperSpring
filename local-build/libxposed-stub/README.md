# libxposed API (solo para compilar)

`io/github/libxposed/api/` es una copia sin cambios del código de
[libxposed/api](https://github.com/libxposed/api), etiqueta `102.0.0`, licencia Apache-2.0.
`local-build/build.sh` la usa para compilar el módulo sin Gradle. **No va dentro del APK**: en el
móvil, estas clases las pone el framework (Vector, LSPosed).

`androidx/annotation/` e `io/github/libxposed/annotation/` son anotaciones vacías escritas aquí,
lo justo para que la copia compile.

Con Gradle no hace falta nada de esto: se usa `compileOnly("io.github.libxposed:api:102.0.0")`.
