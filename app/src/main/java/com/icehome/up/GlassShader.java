package com.icehome.up;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.RuntimeShader;
import android.graphics.Shader;

/**
 * Shader de cristal común a carpetas y centro de control (AGSL, Android 13+).
 *
 * Capas, de abajo arriba (todas dentro de la forma redondeada, con antialias en el canto):
 *  1. Lo que hay detrás (fondo de pantalla o captura de debajo del centro de control):
 *     - en todo el interior con opacidad {@code fill} y una ligera lupa ({@code mag});
 *     - en la franja del canto, desplazado según la normal del borde (refracción) con un
 *       desplazamiento distinto por canal (dispersión cromática).
 *  2. Canto iluminado con el color de lo que hay detrás: el cristal "llega" hasta el borde y
 *     la luz que lo atraviesa sale teñida. Más fuerte arriba-izquierda, eco abajo-derecha.
 *  3. Luz de la lente: brillo según la dirección de la luz, contraluz, línea cáustica y un leve
 *     surco oscuro hacia dentro (grosor del cristal).
 */
final class GlassShader {

    static final String AGSL =
            "uniform shader wall;\n"
            + "uniform float2 size;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float disp;\n"
            + "uniform float dispersion;\n"
            + "uniform float2 origin;\n"      // px del fondo en el punto local (0,0)
            + "uniform float2 scale;\n"       // px del fondo por px local
            + "uniform float hasWall;\n"
            + "uniform float fill;\n"
            + "uniform float bandAlpha;\n"
            + "uniform float tone;\n"
            + "uniform float sat;\n"
            + "uniform float darker;\n"       // oscurece las luces altas (como el 'darker' de HyperOS 4)
            + "uniform float whiteMix;\n"
            + "uniform float edgeLift;\n"     // canto esmerilado: se aclara hacia el borde
            + "uniform float stretch;\n"      // canto: estira hacia dentro el color del borde (0..1)
            + "uniform float overshoot;\n"    // ...tomándolo hasta 'overshoot' px por fuera del borde
            + "uniform float edgeSat;\n"      // saturación extra en el canto     // velo blanco dentro del cristal
            + "uniform float mag;\n"
            + "uniform float rimw;\n"         // núcleo del canto de luz (px)
            + "uniform float glow;\n"         // halo del canto hacia dentro (px)
            + "uniform float rimGain;\n"
            + "uniform float innerDark;\n"    // línea interior tenue bajo el canto (contorno nítido)
            + "uniform float light;\n"
            + "uniform float active;\n"
            + "float sd(float2 p, float2 b, float r) {\n"
            + "  float2 q = abs(p) - b + r;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
            + "}\n"
            + "half4 over(half4 top, half4 bot) { return top + bot * (1.0 - top.a); }\n"
            + "float3 vivid(float3 c) {\n"
            + "  float l = dot(c, float3(0.299, 0.587, 0.114));\n"
            + "  float3 v = clamp(mix(float3(l), c, sat), 0.0, 1.0);\n"
            + "  float l2 = dot(v, float3(0.299, 0.587, 0.114));\n"
            + "  v = v * (1.0 - darker * smoothstep(0.6, 1.0, l2));\n"
            + "  v = mix(v, float3(1.0), whiteMix);\n"
            + "  return clamp(v * tone, 0.0, 1.0);\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 h = size * 0.5;\n"
            + "  float2 p = xy - h;\n"
            + "  float d = -sd(p, h, radius);\n"
            + "  if (d <= 0.0) return half4(0.0);\n"
            + "  float aa = clamp(d, 0.0, 1.0);\n"
            + "  float t = clamp(1.0 - d / band, 0.0, 1.0);\n"
            + "  float2 g = float2(sd(p + float2(1.0, 0.0), h, radius) - sd(p - float2(1.0, 0.0), h, radius),\n"
            + "                    sd(p + float2(0.0, 1.0), h, radius) - sd(p - float2(0.0, 1.0), h, radius));\n"
            + "  float2 n = g / max(length(g), 0.0001);\n"
            + "  float f = dot(n, float2(-0.55, -0.835));\n"   // + arriba-izquierda, - abajo-derecha
            + "  float k = t * t * (3.0 - 2.0 * t);\n"
            + "  half4 acc = half4(0.0);\n"
            + "  float3 tint = float3(1.0);\n"
            + "  if (hasWall > 0.5) {\n"
            + "    float2 dv = -n * disp * k - p * mag + n * (d * stretch + overshoot) * k;\n"
            + "    float2 base = origin + xy * scale;\n"
            + "    float cr = float(wall.eval(base + dv * (1.0 + dispersion) * scale).r);\n"
            + "    float cg = float(wall.eval(base + dv * scale).g);\n"
            + "    float cb = float(wall.eval(base + dv * (1.0 - dispersion) * scale).b);\n"
            + "    float3 bg = vivid(float3(cr, cg, cb));\n"
            + "    float le = dot(bg, float3(0.299, 0.587, 0.114));\n"
            + "    bg = clamp(mix(float3(le), bg, 1.0 + edgeSat * k), 0.0, 1.0);\n"
            + "    bg = mix(bg, float3(1.0), edgeLift * k * k);\n"
            + "    float a = max(fill, k * bandAlpha);\n"
            + "    acc = half4(half3(bg * a), half(a));\n"
            + "    tint = clamp(bg * 1.8 + 0.25, 0.0, 1.0);\n"
            + "    float ga = clamp(k * k * max(f, 0.0) * 0.22 * rimGain * min(1.0, bandAlpha * 4.0), 0.0, 1.0);\n"
            + "    acc = over(half4(half3(tint * ga), half(ga)), acc);\n"
            + "  }\n"
            + "  if (t > 0.0 && light > 0.0) {\n"
            + "    float lens = t * t * t;\n"
            + "    float key = lens * max(f, 0.0);\n"
            + "    float opp = lens * max(-f, 0.0) * 0.62;\n"
            + "    float ca = (t - 0.62) / 0.11;\n"
            + "    float caustic = exp(-ca * ca) * 0.08 * (0.4 + 0.6 * abs(f));\n"
            + "    float tr = (t - 0.30) / 0.13;\n"
            + "    float trough = exp(-tr * tr) * 0.05 * (1.0 - 0.85 * active) * light;\n"
            + "    float w = clamp((key * 0.72 + opp * 0.38 + caustic) * light * (1.0 + 0.18 * active), 0.0, 1.0);\n"
            + "    acc = over(half4(0.0, 0.0, 0.0, half(trough)), acc);\n"
            + "    float3 col = clamp(float3(1.0) + float3(0.10, 0.02, -0.09) * f * t, 0.0, 1.0);\n"
            + "    acc = over(half4(half3(col * w), half(w)), acc);\n"
            + "  }\n"
            // Canto de luz (como el "bloom stroke" de HyperOS): núcleo fino + halo, brillante en las
            // esquinas arriba-izquierda y abajo-derecha, teñido con el color de detrás.
            + "  if (innerDark > 0.0) {\n"
            + "    float q = (d - rimw * 2.6) / (rimw * 1.3);\n"
            + "    acc = over(half4(0.0, 0.0, 0.0, half(exp(-q * q) * innerDark)), acc);\n"
            + "  }\n"
            + "  if (rimGain > 0.0) {\n"
            + "    float core = 1.0 - smoothstep(rimw * 0.6, rimw * 1.4, d);\n"
            + "    float halo = glow > 0.0 ? exp(-d / glow) * 0.55 : 0.0;\n"
            + "    float la = 0.06 + 1.1 * pow(max(f, 0.0), 1.8) + 0.18 * pow(max(-f, 0.0), 3.0);\n"
            + "    float ra = clamp((core * 0.92 + halo) * la * rimGain, 0.0, 1.0);\n"
            + "    float3 rc = mix(tint, float3(1.0), 0.6);\n"
            + "    acc = over(half4(half3(rc * ra), half(ra)), acc);\n"
            + "  }\n"
            + "  return acc * half(aa);\n"
            + "}\n";

    private static BitmapShader empty;

    /** Crea el shader con un fondo vacío (1×1) ya enlazado; null si el sistema no lo admite. */
    static RuntimeShader create() {
        RuntimeShader rs = new RuntimeShader(AGSL);
        rs.setInputShader("wall", empty());
        rs.setFloatUniform("hasWall", 0f);
        rs.setFloatUniform("sat", 1f);
        rs.setFloatUniform("darker", 0f);
        rs.setFloatUniform("whiteMix", 0f);
        rs.setFloatUniform("edgeLift", 0f);
        rs.setFloatUniform("stretch", 0f);
        rs.setFloatUniform("overshoot", 0f);
        rs.setFloatUniform("edgeSat", 0f);
        rs.setFloatUniform("innerDark", 0f);
        rs.setFloatUniform("tone", 1f);
        return rs;
    }

    /**
     * Fondo para el shader con filtrado bilineal. Sin esto, dentro de un RuntimeShader la imagen
     * reducida se muestrea al píxel más cercano y el desenfoque se ve en cuadraditos.
     */
    static BitmapShader linear(Bitmap b) {
        BitmapShader bs = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (android.os.Build.VERSION.SDK_INT >= 33) bs.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        return bs;
    }

    static BitmapShader empty() {
        if (empty == null) {
            Bitmap b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            empty = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        return empty;
    }

    /**
     * Copia reducida y desenfocada: {@code pxPerSample} píxeles de pantalla por píxel del
     * resultado y un desenfoque de caja triple (≈ gaussiano) de {@code blurPx} píxeles de pantalla.
     * {@code screenPerSrc} = píxeles de pantalla que ocupa un píxel del original.
     */
    static Bitmap shrinkBlur(Bitmap src, float screenPerSrc, float pxPerSample, float blurPx) {
        if (src.getConfig() == Bitmap.Config.HARDWARE) src = src.copy(Bitmap.Config.ARGB_8888, false);
        if (src == null) return null;
        float f = screenPerSrc / pxPerSample;
        int w = Math.max(8, Math.round(src.getWidth() * f)), h = Math.max(8, Math.round(src.getHeight() * f));
        Bitmap s = Bitmap.createScaledBitmap(src, w, h, true);
        if (s.getConfig() != Bitmap.Config.ARGB_8888) s = s.copy(Bitmap.Config.ARGB_8888, true);
        else if (!s.isMutable()) s = s.copy(Bitmap.Config.ARGB_8888, true);
        int r = Math.round(blurPx / pxPerSample / 1.7f);        // 3 cajas de radio r ≈ sigma 1.7·r
        if (r > 0) {
            int[] px = new int[w * h], tmp = new int[w * h];
            s.getPixels(px, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                box(px, tmp, w, h, r, true);
                box(tmp, px, w, h, r, false);
            }
            s.setPixels(px, 0, w, 0, 0, w, h);
        }
        return s;
    }

    private static void box(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        int div = 2 * r + 1;
        for (int l = 0; l < lines; l++) {
            int ra = 0, ga = 0, ba = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[idx(l, clampI(i, len), w, horizontal)];
                ra += (c >> 16) & 255;
                ga += (c >> 8) & 255;
                ba += c & 255;
            }
            for (int i = 0; i < len; i++) {
                out[idx(l, i, w, horizontal)] = 0xFF000000 | ((ra / div) << 16) | ((ga / div) << 8) | (ba / div);
                int cOut = in[idx(l, clampI(i - r, len), w, horizontal)];
                int cIn = in[idx(l, clampI(i + r + 1, len), w, horizontal)];
                ra += ((cIn >> 16) & 255) - ((cOut >> 16) & 255);
                ga += ((cIn >> 8) & 255) - ((cOut >> 8) & 255);
                ba += (cIn & 255) - (cOut & 255);
            }
        }
    }

    private static int idx(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }

    private static int clampI(int i, int len) {
        return i < 0 ? 0 : i >= len ? len - 1 : i;
    }

    private GlassShader() {
    }
}
