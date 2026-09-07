package cl.ziiom.videoeditor.mobile;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Random;

public final class VisualizerPainter {
    private VisualizerPainter() {}

    public static void drawOverlay(Canvas canvas, AudioAnalysis analysis, VisualizerConfig config, long timeMs) {
        if (analysis == null) return;
        float[] spectrum = analysis.spectrumAt(timeMs);
        if (spectrum.length == 0) return;
        float bass = clamp01(analysis.bassAt(timeMs) * config.sensitivity);
        int w = canvas.getWidth();
        int h = canvas.getHeight();

        Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        int darkAlpha = (int) (210f * config.darkness * bass * bass);
        dark.setColor(Color.argb(Math.max(0, Math.min(220, darkAlpha)), 0, 0, 0));
        canvas.drawRect(0, 0, w, h, dark);

        switch (config.mode) {
            case CIRCLE: drawCircle(canvas, spectrum, config, timeMs); break;
            case WAVE: drawWave(canvas, spectrum, config); break;
            case MIRROR: drawMirror(canvas, spectrum, config); break;
            case PARTICLES: drawParticles(canvas, spectrum, config, timeMs); break;
            case BARS:
            default: drawBars(canvas, spectrum, config); break;
        }
    }

    private static Paint paint(boolean glow, float width, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(alpha, 235, 247, 255));
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        if (glow) p.setShadowLayer(width * 2.4f, 0, 0, Color.argb(Math.min(210, alpha), 70, 185, 255));
        return p;
    }

    private static void drawBars(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        float margin = w * 0.08f;
        float base = h * 0.86f;
        float usable = w - margin * 2f;
        float step = usable / s.length;
        Paint p = paint(cfg.glow, Math.max(3f, step * 0.46f), 245);
        for (int i = 0; i < s.length; i++) {
            float v = clamp01(s[i] * cfg.sensitivity);
            float bar = h * 0.48f * (0.08f + 0.92f * v);
            float x = margin + step * (i + 0.5f);
            c.drawLine(x, base, x, base - bar, p);
        }
    }

    private static void drawMirror(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        float center = h * 0.62f;
        float margin = w * 0.06f;
        float usable = w - margin * 2f;
        float step = usable / s.length;
        Paint p = paint(cfg.glow, Math.max(3f, step * 0.42f), 235);
        for (int i = 0; i < s.length; i++) {
            float v = clamp01(s[i] * cfg.sensitivity);
            float len = h * 0.28f * v;
            float x = margin + step * (i + 0.5f);
            c.drawLine(x, center - len, x, center + len, p);
        }
    }

    private static void drawCircle(Canvas c, float[] s, VisualizerConfig cfg, long timeMs) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float r = Math.min(w, h) * 0.24f;
        float max = Math.min(w, h) * 0.16f;
        Paint p = paint(cfg.glow, Math.max(3f, Math.min(w, h) * 0.006f), 245);
        float rot = (timeMs % 12000L) / 12000f * 360f;
        for (int i = 0; i < s.length; i++) {
            float a = (float) Math.toRadians(rot + i * (360f / s.length));
            float v = clamp01(s[i] * cfg.sensitivity);
            float len = max * (0.12f + 0.88f * v);
            float x0 = cx + (float) Math.cos(a) * r;
            float y0 = cy + (float) Math.sin(a) * r;
            float x1 = cx + (float) Math.cos(a) * (r + len);
            float y1 = cy + (float) Math.sin(a) * (r + len);
            c.drawLine(x0, y0, x1, y1, p);
        }
        Paint ring = paint(cfg.glow, Math.max(2f, Math.min(w, h) * 0.003f), 150);
        c.drawCircle(cx, cy, r, ring);
    }

    private static void drawWave(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        Path path = new Path();
        float center = h * 0.68f;
        float amp = h * 0.25f;
        for (int i = 0; i < s.length; i++) {
            float x = i * (w / (float) (s.length - 1));
            float sign = (i & 1) == 0 ? -1f : 1f;
            float y = center + sign * amp * clamp01(s[i] * cfg.sensitivity);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, paint(cfg.glow, Math.max(4f, h * 0.008f), 245));
    }

    private static void drawParticles(Canvas c, float[] s, VisualizerConfig cfg, long timeMs) {
        int w = c.getWidth(), h = c.getHeight();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        float energy = 0f;
        for (float v : s) energy += v;
        energy = clamp01((energy / s.length) * cfg.sensitivity);
        Random rnd = new Random(timeMs / 70L);
        int count = 42;
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float band = s[i % s.length];
            float radius = (2f + 12f * band * cfg.sensitivity) * (0.7f + energy);
            int alpha = 70 + (int) (180 * clamp01(band * cfg.sensitivity));
            p.setColor(Color.argb(Math.min(255, alpha), 215, 244, 255));
            if (cfg.glow) p.setShadowLayer(radius * 1.8f, 0, 0, Color.rgb(64, 172, 255));
            c.drawCircle(x, y, radius, p);
        }
    }

    public static RectF centerCropRect(int canvasW, int canvasH, int bitmapW, int bitmapH, float zoom) {
        float scale = Math.max(canvasW / (float) bitmapW, canvasH / (float) bitmapH) * zoom;
        float dw = bitmapW * scale;
        float dh = bitmapH * scale;
        float left = (canvasW - dw) / 2f;
        float top = (canvasH - dh) / 2f;
        return new RectF(left, top, left + dw, top + dh);
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
}
