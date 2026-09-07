package cl.ziiom.videoeditor.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.Random;

public final class VisualizerPainter {
    private VisualizerPainter() {}

    public static void drawOverlay(Canvas canvas, AudioAnalysis analysis, VisualizerConfig config,
                                   long timeMs, Bitmap centerImage) {
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
            case TRIANGLE:
                drawTriangle(canvas, spectrum, config, timeMs, bass, centerImage);
                break;
            case CIRCLE:
                drawCircle(canvas, spectrum, config, timeMs, bass, centerImage, false);
                break;
            case DIAMOND:
                drawDiamond(canvas, spectrum, config, timeMs, bass, centerImage);
                break;
            case RING:
                drawCircle(canvas, spectrum, config, timeMs, bass, centerImage, true);
                break;
            case WAVE:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.CIRCLE);
                drawWave(canvas, spectrum, config);
                break;
            case MIRROR:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.ROUNDED);
                drawMirror(canvas, spectrum, config);
                break;
            case PARTICLES:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.CIRCLE);
                drawParticles(canvas, spectrum, config, timeMs);
                break;
            case BARS:
            default:
                drawCenterArtwork(canvas, centerImage, config, bass, Shape.ROUNDED);
                drawBars(canvas, spectrum, config);
                break;
        }
    }

    private enum Shape { CIRCLE, ROUNDED }

    private static Paint paint(boolean glow, float width, int alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.argb(alpha, 235, 247, 255));
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStyle(Paint.Style.STROKE);
        if (glow) {
            p.setShadowLayer(width * 2.8f, 0, 0,
                    Color.argb(Math.min(220, alpha), 70, 205, 255));
        }
        return p;
    }

    private static void drawTriangle(Canvas c, float[] s, VisualizerConfig cfg,
                                     long timeMs, float bass, Bitmap centerImage) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.045f * cfg.zoom * bass;
        float triH = min * cfg.centerSize * 1.08f * pulse;
        float triW = triH * 1.13f;

        float topY = cy - triH * 0.54f;
        float bottomY = cy + triH * 0.46f;
        float leftX = cx - triW / 2f;
        float rightX = cx + triW / 2f;

        float[] xs = {cx, rightX, leftX};
        float[] ys = {topY, bottomY, bottomY};

        Path clip = new Path();
        clip.moveTo(xs[0], ys[0]);
        clip.lineTo(xs[1], ys[1]);
        clip.lineTo(xs[2], ys[2]);
        clip.close();

        if (centerImage != null) {
            RectF bounds = new RectF(leftX, topY, rightX, bottomY);
            drawBitmapClipped(c, centerImage, bounds, clip);
            Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
            shade.setColor(Color.argb(38, 0, 0, 0));
            c.drawPath(clip, shade);
        }

        Paint outline = paint(cfg.glow, Math.max(3f, min * 0.006f), 230);
        c.drawPath(clip, outline);

        drawSpectrumAroundPolygon(c, s, cfg, xs, ys, min * 0.16f);
        Paint inner = paint(cfg.glow, Math.max(1.8f, min * 0.003f), 120);
        c.drawPath(clip, inner);
    }

    private static void drawDiamond(Canvas c, float[] s, VisualizerConfig cfg,
                                    long timeMs, float bass, Bitmap centerImage) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.04f * cfg.zoom * bass;
        float rx = min * cfg.centerSize * 0.46f * pulse;
        float ry = min * cfg.centerSize * 0.55f * pulse;

        float[] xs = {cx, cx + rx, cx, cx - rx};
        float[] ys = {cy - ry, cy, cy + ry, cy};

        Path clip = new Path();
        clip.moveTo(xs[0], ys[0]);
        for (int i = 1; i < 4; i++) clip.lineTo(xs[i], ys[i]);
        clip.close();

        if (centerImage != null) {
            drawBitmapClipped(c, centerImage,
                    new RectF(cx - rx, cy - ry, cx + rx, cy + ry), clip);
        }

        Paint outline = paint(cfg.glow, Math.max(3f, min * 0.006f), 230);
        c.drawPath(clip, outline);
        drawSpectrumAroundPolygon(c, s, cfg, xs, ys, min * 0.15f);
    }

    private static void drawCircle(Canvas c, float[] s, VisualizerConfig cfg,
                                   long timeMs, float bass, Bitmap centerImage, boolean ringMode) {
        int w = c.getWidth(), h = c.getHeight();
        float cx = w / 2f, cy = h / 2f;
        float min = Math.min(w, h);
        float pulse = 1f + 0.045f * cfg.zoom * bass;
        float r = min * cfg.centerSize * (ringMode ? 0.39f : 0.40f) * pulse;

        if (centerImage != null) {
            Path clip = new Path();
            clip.addCircle(cx, cy, r * 0.96f, Path.Direction.CW);
            drawBitmapClipped(c, centerImage,
                    new RectF(cx-r, cy-r, cx+r, cy+r), clip);
        }

        float max = min * (ringMode ? 0.19f : 0.16f);
        Paint p = paint(cfg.glow, Math.max(3f, min * 0.006f), 245);
        float rot = ringMode ? 0f : (timeMs % 12000L) / 12000f * 360f;
        for (int i = 0; i < s.length; i++) {
            float a = (float) Math.toRadians(rot + i * (360f / s.length));
            float v = clamp01(s[i] * cfg.sensitivity);
            float len = max * (0.08f + 0.92f * v);
            float inner = ringMode ? r - min * 0.012f : r;
            float x0 = cx + (float) Math.cos(a) * inner;
            float y0 = cy + (float) Math.sin(a) * inner;
            float x1 = cx + (float) Math.cos(a) * (r + len);
            float y1 = cy + (float) Math.sin(a) * (r + len);
            c.drawLine(x0, y0, x1, y1, p);
        }

        Paint ring = paint(cfg.glow, Math.max(2.2f, min * 0.004f), 185);
        c.drawCircle(cx, cy, r, ring);
        if (ringMode) {
            Paint second = paint(cfg.glow, Math.max(1.6f, min * 0.0025f), 95);
            c.drawCircle(cx, cy, r * 1.07f + bass * min * 0.015f, second);
        }
    }

    private static void drawSpectrumAroundPolygon(Canvas c, float[] s, VisualizerConfig cfg,
                                                  float[] xs, float[] ys, float maxLen) {
        int n = xs.length;
        float cx = 0f, cy = 0f;
        for (int i = 0; i < n; i++) { cx += xs[i]; cy += ys[i]; }
        cx /= n; cy /= n;

        float[] lengths = new float[n];
        float total = 0f;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float dx = xs[j] - xs[i], dy = ys[j] - ys[i];
            lengths[i] = (float)Math.sqrt(dx*dx + dy*dy);
            total += lengths[i];
        }

        Paint p = paint(cfg.glow, Math.max(2.5f, Math.min(c.getWidth(), c.getHeight()) * 0.005f), 245);
        for (int b = 0; b < s.length; b++) {
            float target = total * (b / (float)s.length);
            int edge = 0;
            while (edge < n - 1 && target > lengths[edge]) {
                target -= lengths[edge];
                edge++;
            }
            int next = (edge + 1) % n;
            float t = lengths[edge] <= 0f ? 0f : target / lengths[edge];
            float x = xs[edge] + (xs[next] - xs[edge]) * t;
            float y = ys[edge] + (ys[next] - ys[edge]) * t;

            float tx = xs[next] - xs[edge], ty = ys[next] - ys[edge];
            float inv = 1f / Math.max(0.001f, (float)Math.sqrt(tx*tx + ty*ty));
            float nx = -ty * inv, ny = tx * inv;
            float outwardX = x - cx, outwardY = y - cy;
            if (nx * outwardX + ny * outwardY < 0f) { nx = -nx; ny = -ny; }

            float v = clamp01(s[b] * cfg.sensitivity);
            float len = maxLen * (0.08f + 0.92f * v);
            c.drawLine(x, y, x + nx * len, y + ny * len, p);
        }
    }

    private static void drawCenterArtwork(Canvas c, Bitmap bitmap, VisualizerConfig cfg,
                                          float bass, Shape shape) {
        if (bitmap == null) return;
        int w = c.getWidth(), h = c.getHeight();
        float min = Math.min(w, h);
        float pulse = 1f + 0.035f * cfg.zoom * bass;
        float size = min * cfg.centerSize * 0.62f * pulse;
        float cx = w / 2f, cy = h * 0.48f;
        RectF target = new RectF(cx-size/2f, cy-size/2f, cx+size/2f, cy+size/2f);

        Path clip = new Path();
        if (shape == Shape.CIRCLE) {
            clip.addCircle(cx, cy, size/2f, Path.Direction.CW);
        } else {
            float radius = size * 0.11f;
            clip.addRoundRect(target, radius, radius, Path.Direction.CW);
        }
        drawBitmapClipped(c, bitmap, target, clip);
        Paint border = paint(cfg.glow, Math.max(2f, min * 0.0035f), 160);
        c.drawPath(clip, border);
    }

    private static void drawBitmapClipped(Canvas c, Bitmap bitmap, RectF target, Path clip) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;
        int save = c.save();
        c.clipPath(clip);
        float scale = Math.max(target.width() / bitmap.getWidth(), target.height() / bitmap.getHeight());
        float dw = bitmap.getWidth() * scale;
        float dh = bitmap.getHeight() * scale;
        RectF dst = new RectF(
                target.centerX() - dw / 2f,
                target.centerY() - dh / 2f,
                target.centerX() + dw / 2f,
                target.centerY() + dh / 2f
        );
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(bitmap, null, dst, p);
        c.restoreToCount(save);
    }

    private static void drawBars(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        float margin = w * 0.08f;
        float base = h * 0.88f;
        float usable = w - margin * 2f;
        float step = usable / s.length;
        Paint p = paint(cfg.glow, Math.max(3f, step * 0.46f), 245);
        for (int i = 0; i < s.length; i++) {
            float v = clamp01(s[i] * cfg.sensitivity);
            float bar = h * 0.34f * (0.08f + 0.92f * v);
            float x = margin + step * (i + 0.5f);
            c.drawLine(x, base, x, base - bar, p);
        }
    }

    private static void drawMirror(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        float center = h * 0.69f;
        float margin = w * 0.06f;
        float usable = w - margin * 2f;
        float step = usable / s.length;
        Paint p = paint(cfg.glow, Math.max(3f, step * 0.42f), 235);
        for (int i = 0; i < s.length; i++) {
            float v = clamp01(s[i] * cfg.sensitivity);
            float len = h * 0.20f * v;
            float x = margin + step * (i + 0.5f);
            c.drawLine(x, center - len, x, center + len, p);
        }
    }

    private static void drawWave(Canvas c, float[] s, VisualizerConfig cfg) {
        int w = c.getWidth(), h = c.getHeight();
        Path path = new Path();
        float center = h * 0.76f;
        float amp = h * 0.15f;
        for (int i = 0; i < s.length; i++) {
            float x = i * (w / (float) Math.max(1, s.length - 1));
            float sign = (i & 1) == 0 ? -1f : 1f;
            float y = center + sign * amp * clamp01(s[i] * cfg.sensitivity);
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        c.drawPath(path, paint(cfg.glow, Math.max(4f, h * 0.007f), 245));
    }

    private static void drawParticles(Canvas c, float[] s, VisualizerConfig cfg, long timeMs) {
        int w = c.getWidth(), h = c.getHeight();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        float energy = 0f;
        for (float v : s) energy += v;
        energy = clamp01((energy / s.length) * cfg.sensitivity);
        Random rnd = new Random(timeMs / 70L);
        int count = 48;
        for (int i = 0; i < count; i++) {
            float x = rnd.nextFloat() * w;
            float y = rnd.nextFloat() * h;
            float band = s[i % s.length];
            float radius = (2f + 10f * band * cfg.sensitivity) * (0.7f + energy);
            int alpha = 65 + (int) (180 * clamp01(band * cfg.sensitivity));
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
