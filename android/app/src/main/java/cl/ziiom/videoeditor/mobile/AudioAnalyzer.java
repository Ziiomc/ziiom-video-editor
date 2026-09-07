package cl.ziiom.videoeditor.mobile;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class AudioAnalyzer {
    public interface ProgressListener { void onProgress(int percent); }

    private static final int WINDOW = 1024;
    private static final int BANDS = 32;

    private AudioAnalyzer() {}

    public static AudioAnalysis analyze(Context context, Uri uri, ProgressListener listener) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = f;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) throw new IllegalArgumentException("El archivo no contiene audio compatible");
            extractor.selectTrack(audioTrack);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION) ? inputFormat.getLong(MediaFormat.KEY_DURATION) : 0L;
            int expectedSampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int expectedChannels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            List<float[]> rawSpectra = new ArrayList<>();
            List<Float> rawBass = new ArrayList<>();
            List<Float> rawEnergy = new ArrayList<>();
            float[] monoWindow = new float[WINDOW];
            int[] windowFill = {0};
            int[] currentSampleRate = {expectedSampleRate};
            int[] currentChannels = {expectedChannels};
            int[] pcmEncoding = {AudioFormat.ENCODING_PCM_16BIT};

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int lastProgress = -1;

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        if (in != null) {
                            in.clear();
                            int sampleSize = extractor.readSampleData(in, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inIndex, 0, sampleSize, Math.max(0, pts), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) currentSampleRate[0] = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) currentChannels[0] = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding[0] = out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (outIndex >= 0) {
                    ByteBuffer out = codec.getOutputBuffer(outIndex);
                    if (out != null && info.size > 0) {
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        out.order(ByteOrder.nativeOrder());
                        consumePcm(out, pcmEncoding[0], currentChannels[0], monoWindow, windowFill,
                                currentSampleRate[0], rawSpectra, rawBass, rawEnergy);
                    }
                    if (durationUs > 0 && info.presentationTimeUs >= 0) {
                        int p = (int) Math.min(99, Math.round(info.presentationTimeUs * 100f / durationUs));
                        if (p != lastProgress) {
                            lastProgress = p;
                            if (listener != null) listener.onProgress(p);
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }

            if (windowFill[0] > 0) {
                for (int i = windowFill[0]; i < WINDOW; i++) monoWindow[i] = 0f;
                addFrame(monoWindow, currentSampleRate[0], rawSpectra, rawBass, rawEnergy);
            }

            if (rawSpectra.isEmpty()) throw new IllegalArgumentException("No se pudo analizar la canción");
            if (listener != null) listener.onProgress(100);
            long durationMs = durationUs > 0 ? durationUs / 1000L : Math.round(rawSpectra.size() * WINDOW * 1000.0 / currentSampleRate[0]);
            return normalize(rawSpectra, rawBass, rawEnergy, currentSampleRate[0], durationMs);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static void consumePcm(ByteBuffer buffer, int encoding, int channels,
                                   float[] window, int[] fill, int sampleRate,
                                   List<float[]> spectra, List<Float> bass, List<Float> energy) {
        channels = Math.max(1, channels);
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            while (buffer.remaining() >= 4 * channels) {
                float mono = 0f;
                for (int c = 0; c < channels; c++) mono += buffer.getFloat();
                pushSample(mono / channels, window, fill, sampleRate, spectra, bass, energy);
            }
        } else {
            while (buffer.remaining() >= 2 * channels) {
                float mono = 0f;
                for (int c = 0; c < channels; c++) mono += buffer.getShort() / 32768f;
                pushSample(mono / channels, window, fill, sampleRate, spectra, bass, energy);
            }
        }
    }

    private static void pushSample(float sample, float[] window, int[] fill, int sampleRate,
                                   List<float[]> spectra, List<Float> bass, List<Float> energy) {
        window[fill[0]++] = sample;
        if (fill[0] == WINDOW) {
            addFrame(window, sampleRate, spectra, bass, energy);
            fill[0] = 0;
        }
    }

    private static void addFrame(float[] source, int sampleRate, List<float[]> spectra,
                                 List<Float> bassList, List<Float> energyList) {
        double[] re = new double[WINDOW];
        double[] im = new double[WINDOW];
        double rms = 0;
        for (int i = 0; i < WINDOW; i++) {
            double s = source[i];
            rms += s * s;
            double hann = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (WINDOW - 1));
            re[i] = s * hann;
        }
        fft(re, im);

        float[] bands = new float[BANDS];
        double minHz = 45.0;
        double maxHz = Math.min(16000.0, sampleRate / 2.0 - 1.0);
        for (int b = 0; b < BANDS; b++) {
            double f0 = minHz * Math.pow(maxHz / minHz, b / (double) BANDS);
            double f1 = minHz * Math.pow(maxHz / minHz, (b + 1) / (double) BANDS);
            int k0 = Math.max(1, (int) Math.floor(f0 * WINDOW / sampleRate));
            int k1 = Math.max(k0 + 1, (int) Math.ceil(f1 * WINDOW / sampleRate));
            k1 = Math.min(WINDOW / 2, k1);
            double sum = 0;
            int count = 0;
            for (int k = k0; k < k1; k++) {
                double mag = Math.hypot(re[k], im[k]);
                sum += mag;
                count++;
            }
            bands[b] = (float) (count == 0 ? 0 : sum / count);
        }
        float bass = 0f;
        int bassBands = Math.max(4, BANDS / 5);
        for (int i = 0; i < bassBands; i++) bass += bands[i];
        bass /= bassBands;
        spectra.add(bands);
        bassList.add(bass);
        energyList.add((float) Math.sqrt(rms / WINDOW));
    }

    private static AudioAnalysis normalize(List<float[]> rawSpectra, List<Float> rawBass,
                                           List<Float> rawEnergy, int sampleRate, long durationMs) {
        int frames = rawSpectra.size();
        float[][] spectra = new float[frames][BANDS];
        float[] bandMax = new float[BANDS];
        float bassMax = 1e-6f;
        float energyMax = 1e-6f;

        for (int i = 0; i < frames; i++) {
            float[] f = rawSpectra.get(i);
            for (int b = 0; b < BANDS; b++) {
                f[b] = (float) Math.log1p(f[b] * 10.0);
                bandMax[b] = Math.max(bandMax[b], f[b]);
            }
            bassMax = Math.max(bassMax, (float) Math.log1p(rawBass.get(i) * 10.0));
            energyMax = Math.max(energyMax, rawEnergy.get(i));
        }

        float[] bass = new float[frames];
        float[] energy = new float[frames];
        for (int i = 0; i < frames; i++) {
            float[] f = rawSpectra.get(i);
            for (int b = 0; b < BANDS; b++) {
                float n = bandMax[b] <= 1e-6f ? 0f : f[b] / bandMax[b];
                spectra[i][b] = clamp01((float) Math.sqrt(n));
            }
            bass[i] = clamp01((float) Math.sqrt(Math.log1p(rawBass.get(i) * 10.0) / bassMax));
            energy[i] = clamp01((float) Math.sqrt(rawEnergy.get(i) / energyMax));
        }
        smooth(bass, 0.62f);
        smooth(energy, 0.55f);
        for (int b = 0; b < BANDS; b++) smoothBand(spectra, b, 0.48f);
        return new AudioAnalysis(spectra, bass, energy, sampleRate, WINDOW, durationMs);
    }

    private static void smooth(float[] data, float keep) {
        float v = data.length > 0 ? data[0] : 0f;
        for (int i = 0; i < data.length; i++) {
            v = v * keep + data[i] * (1f - keep);
            data[i] = v;
        }
    }

    private static void smoothBand(float[][] data, int band, float keep) {
        float v = data.length > 0 ? data[0][band] : 0f;
        for (float[] datum : data) {
            v = v * keep + datum[band] * (1f - keep);
            datum[band] = v;
        }
    }

    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double tr = re[i]; re[i] = re[j]; re[j] = tr;
                double ti = im[i]; im[i] = im[j]; im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wLenR = Math.cos(ang);
            double wLenI = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wr = 1, wi = 0;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = i + j + len / 2;
                    double vr = re[v] * wr - im[v] * wi;
                    double vi = re[v] * wi + im[v] * wr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    double nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
    }
}
