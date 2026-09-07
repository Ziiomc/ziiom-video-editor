package cl.ziiom.videoeditor.mobile;

public class AudioAnalysis {
    private final float[][] spectra;
    private final float[] bass;
    private final float[] energy;
    public final int sampleRate;
    public final int windowSize;
    public final long durationMs;

    public AudioAnalysis(float[][] spectra, float[] bass, float[] energy,
                         int sampleRate, int windowSize, long durationMs) {
        this.spectra = spectra;
        this.bass = bass;
        this.energy = energy;
        this.sampleRate = sampleRate;
        this.windowSize = windowSize;
        this.durationMs = durationMs;
    }

    public int bandCount() {
        return spectra.length == 0 ? 0 : spectra[0].length;
    }

    private double frameAt(long timeMs) {
        if (spectra.length <= 1) return 0.0;
        double framesPerMs = sampleRate / (windowSize * 1000.0);
        double frame = Math.max(0, timeMs) * framesPerMs;
        return Math.max(0.0, Math.min(spectra.length - 1.0, frame));
    }

    public float[] spectrumAt(long timeMs) {
        if (spectra.length == 0) return new float[0];
        if (spectra.length == 1) return spectra[0].clone();
        double frame = frameAt(timeMs);
        int i0 = (int)Math.floor(frame);
        int i1 = Math.min(spectra.length - 1, i0 + 1);
        float t = (float)(frame - i0);
        float[] out = new float[spectra[i0].length];
        for (int b = 0; b < out.length; b++) {
            out[b] = spectra[i0][b] + (spectra[i1][b] - spectra[i0][b]) * t;
        }
        return out;
    }

    public float bassAt(long timeMs) {
        return interpolate(bass, timeMs);
    }

    public float energyAt(long timeMs) {
        return interpolate(energy, timeMs);
    }

    private float interpolate(float[] data, long timeMs) {
        if (data.length == 0) return 0f;
        if (data.length == 1) return data[0];
        double frame = frameAt(timeMs);
        int i0 = (int)Math.floor(frame);
        int i1 = Math.min(data.length - 1, i0 + 1);
        float t = (float)(frame - i0);
        return data[i0] + (data[i1] - data[i0]) * t;
    }
}
