package cl.ziiom.videoeditor.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class VisualizerView extends View {
    private Bitmap background;
    private AudioAnalysis analysis;
    private VisualizerConfig config = new VisualizerConfig();
    private long positionMs;

    public VisualizerView(Context context) { super(context); init(); }
    public VisualizerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public VisualizerView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setBackgroundBitmap(Bitmap bitmap) { this.background = bitmap; invalidate(); }
    public void setAnalysis(AudioAnalysis analysis) { this.analysis = analysis; invalidate(); }
    public void setConfig(VisualizerConfig config) { this.config = config; invalidate(); }
    public void setPositionMs(long positionMs) { this.positionMs = positionMs; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        if (background != null) {
            float bass = analysis == null ? 0f : Math.min(1f, analysis.bassAt(positionMs) * config.sensitivity);
            float zoom = 1f + 0.055f * config.zoom * bass;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(background, null,
                    VisualizerPainter.centerCropRect(getWidth(), getHeight(), background.getWidth(), background.getHeight(), zoom), p);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.rgb(15, 20, 30));
            canvas.drawRect(0, 0, getWidth(), getHeight(), p);
        }
        VisualizerPainter.drawOverlay(canvas, analysis, config, positionMs);
    }
}
