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

    private int indexAt(long timeMs) {
        if (spectra.length == 0) return 0;
        double framesPerMs = sampleRate / (windowSize * 1000.0);
        int i = (int) Math.floor(Math.max(0, timeMs) * framesPerMs);
        return Math.max(0, Math.min(spectra.length - 1, i));
    }

    public float[] spectrumAt(long timeMs) {
        if (spectra.length == 0) return new float[0];
        return spectra[indexAt(timeMs)];
    }

    public float bassAt(long timeMs) {
        return bass.length == 0 ? 0f : bass[Math.min(bass.length - 1, indexAt(timeMs))];
    }

    public float energyAt(long timeMs) {
        return energy.length == 0 ? 0f : energy[Math.min(energy.length - 1, indexAt(timeMs))];
    }
}
