package cl.ziiom.videoeditor.mobile;

public class VisualizerConfig {
    public enum Mode { BARS, CIRCLE, WAVE, MIRROR, PARTICLES }

    public Mode mode = Mode.BARS;
    public float sensitivity = 1.35f;
    public float darkness = 0.72f;
    public float zoom = 0.85f;
    public boolean glow = true;

    public VisualizerConfig copy() {
        VisualizerConfig c = new VisualizerConfig();
        c.mode = mode;
        c.sensitivity = sensitivity;
        c.darkness = darkness;
        c.zoom = zoom;
        c.glow = glow;
        return c;
    }
}
