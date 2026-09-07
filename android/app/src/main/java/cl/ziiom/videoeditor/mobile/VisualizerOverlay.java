package cl.ziiom.videoeditor.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

@UnstableApi
public class VisualizerOverlay extends CanvasOverlay {
    private final AudioAnalysis analysis;
    private final VisualizerConfig config;
    private final Bitmap centerImage;

    public VisualizerOverlay(AudioAnalysis analysis, VisualizerConfig config, Bitmap centerImage) {
        super(true);
        this.analysis = analysis;
        this.config = config;
        this.centerImage = centerImage;
    }

    @Override public void onDraw(Canvas canvas, long presentationTimeUs) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        VisualizerPainter.drawOverlay(canvas, analysis, config, presentationTimeUs / 1000L, centerImage);
    }
}
