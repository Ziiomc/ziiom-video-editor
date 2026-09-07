package cl.ziiom.videoeditor.mobile;

import android.graphics.Canvas;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;

@UnstableApi
public class VisualizerOverlay extends CanvasOverlay {
    private final AudioAnalysis analysis;
    private final VisualizerConfig config;

    public VisualizerOverlay(AudioAnalysis analysis, VisualizerConfig config) {
        super(true);
        this.analysis = analysis;
        this.config = config;
    }

    @Override public void onDraw(Canvas canvas, long presentationTimeUs) {
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR);
        VisualizerPainter.drawOverlay(canvas, analysis, config, presentationTimeUs / 1000L);
    }
}
