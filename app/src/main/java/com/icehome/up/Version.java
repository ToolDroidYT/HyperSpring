package com.icehome.up;

/**
 * Versión del módulo, compilada en el código: la que publica cada proceso enganchado (que puede
 * seguir con la anterior hasta reiniciarlo) y con la que la app la compara. local-build/build.sh
 * toma de aquí el versionName.
 */
public final class Version {
    public static final String NAME = "1.0.1";
    public static final int CODE = 45;

    private Version() {
    }
}
