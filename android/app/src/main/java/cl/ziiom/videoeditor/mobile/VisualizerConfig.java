package cl.ziiom.videoeditor.mobile;

public class VisualizerConfig {
    public enum Mode { TRIANGLE, CIRCLE, DIAMOND, RING, BARS, WAVE, MIRROR, PARTICLES }
    public enum Palette { ICE, NEON, PHONK, FIRE, GOLD, WHITE }

    public Mode mode = Mode.TRIANGLE;
    public Palette palette = Palette.ICE;
    public float sensitivity = 1.35f;
    public float darkness = 0.72f;
    public float zoom = 0.85f;
    public float centerSize = 0.58f;
    public float thickness = 1.0f;
    public float rotationSpeed = 1.0f;
    public float vignette = 0.35f;
    public boolean glow = true;

    public VisualizerConfig copy() {
        VisualizerConfig c = new VisualizerConfig();
        c.mode = mode;
        c.palette = palette;
        c.sensitivity = sensitivity;
        c.darkness = darkness;
        c.zoom = zoom;
        c.centerSize = centerSize;
        c.thickness = thickness;
        c.rotationSpeed = rotationSpeed;
        c.vignette = vignette;
        c.glow = glow;
        return c;
    }
}
