package com.icehome.up;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Desenfoque progresivo en lo alto de la app, como en iOS y HyperOS: bajo la barra de estado lo
 * que sube al desplazarse se ve muy desenfocado, y el desenfoque se va quitando poco a poco hasta
 * desaparecer sin un borde marcado.
 * <p>
 * Cómo: graba lo que hay debajo (el contenedor de páginas) en un RenderNode y lo pasa por un
 * desenfoque gaussiano propio en AGSL cuyo radio depende de la altura: grande arriba, cero abajo.
 * Un desenfoque gaussiano se puede separar en dos pasadas (horizontal y luego vertical), así cada
 * píxel lee 17 muestras por pasada en vez de cientos. El desenfoque del sistema
 * ({@code RenderEffect.createBlurEffect}) no sirve aquí porque usa el mismo radio en toda la zona.
 * <p>
 * Encima, un velo del color del fondo que también se apaga hacia abajo, para que la hora y los
 * iconos de la barra de estado se lean sobre cualquier color. Android 13+; antes, solo el velo.
 */
final class TopBlur extends View {

    /** Una pasada del desenfoque en la dirección {@code dir}; el radio crece hacia arriba. */
    private static final String VBLUR =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 dir;\n"
            + "uniform float maxSigma;\n"
            + "half4 sampleAt(float2 p) {\n"
            // fuera de la zona grabada no hay nada: se repite el borde para que no entre negro
            + "  return content.eval(clamp(p, float2(0.5), size - float2(0.5)));\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "  float f = clamp(1.0 - coord.y / size.y, 0.0, 1.0);\n"
            + "  float s = maxSigma * f * f * (3.0 - 2.0 * f);\n"
            + "  if (s < 0.4) return sampleAt(coord);\n"
            + "  float stp = s * 0.45;\n"
            + "  half4 acc = sampleAt(coord);\n"
            + "  float wsum = 1.0;\n"
            + "  for (int i = 1; i <= 8; i++) {\n"
            + "    float x = float(i) * stp;\n"
            + "    float w = exp(-0.5 * x * x / (s * s));\n"
            + "    acc += (sampleAt(coord + dir * x) + sampleAt(coord - dir * x)) * half(w);\n"
            + "    wsum += 2.0 * w;\n"
            + "  }\n"
            + "  return acc / half(wsum);\n"
            + "}\n";

    private final float dp;
    private final int bg;
    private final Paint veil = new Paint();
    private View source;
    private RenderNode node;
    private RuntimeShader hPass, vPass;
    private final int[] myLoc = new int[2], srcLoc = new int[2];

    TopBlur(Context c, int bg) {
        super(c);
        this.bg = bg;
        dp = c.getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                hPass = new RuntimeShader(VBLUR);
                vPass = new RuntimeShader(VBLUR);
                hPass.setFloatUniform("dir", 1f, 0f);
                vPass.setFloatUniform("dir", 0f, 1f);
                node = new RenderNode("topBlur");
            } catch (Throwable t) {
                android.util.Log.w("HyperSpring", "desenfoque superior: el sombreador no compila", t);
                hPass = vPass = null;
                node = null;
            }
        }
    }

    /** La vista que se ve (desenfocada) por debajo. */
    void setSource(View v) {
        source = v;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // Con desenfoque, el velo es suave: el desenfoque ya separa la hora del contenido.
        // Sin él, el velo tiene que hacer todo el trabajo y es más opaco.
        boolean blur = node != null;
        veil.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{(bg & 0x00FFFFFF) | (blur ? 0x8C000000 : 0xF2000000),
                        (bg & 0x00FFFFFF) | (blur ? 0x40000000 : 0xB3000000), bg & 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (node != null && source != null && canvas.isHardwareAccelerated() && source.getVisibility() == VISIBLE) {
            try {
                getLocationInWindow(myLoc);
                source.getLocationInWindow(srcLoc);
                node.setPosition(0, 0, w, h);
                RecordingCanvas rc = node.beginRecording(w, h);
                rc.translate(srcLoc[0] - myLoc[0] - source.getScrollX(), srcLoc[1] - myLoc[1] - source.getScrollY());
                source.draw(rc);
                node.endRecording();
                float sigma = 11f * dp;
                hPass.setFloatUniform("size", w, h);
                hPass.setFloatUniform("maxSigma", sigma);
                vPass.setFloatUniform("size", w, h);
                vPass.setFloatUniform("maxSigma", sigma);
                // Primero horizontal (la de dentro) y luego vertical sobre el resultado.
                node.setRenderEffect(RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(vPass, "content"),
                        RenderEffect.createRuntimeShaderEffect(hPass, "content")));
                canvas.drawRenderNode(node);
            } catch (Throwable t) {
                android.util.Log.w("HyperSpring", "desenfoque superior desactivado", t);
                node = null;                                       // solo el velo a partir de aquí
                onSizeChanged(w, h, w, h);
            }
        }
        canvas.drawRect(0, 0, w, h, veil);
    }
}
