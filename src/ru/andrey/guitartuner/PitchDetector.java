package ru.andrey.guitartuner;

/** YIN pitch detector tuned for a 6-string guitar (roughly 60..420 Hz). */
public final class PitchDetector {
    private final int sampleRate;
    private final int window;
    private final int tauMin;
    private final int tauMax;
    private final float[] diff;
    private final float[] x;

    public float lastRms;
    public float lastConfidence;

    public PitchDetector(int sampleRate, int bufferSize) {
        this.sampleRate = sampleRate;
        this.tauMin = sampleRate / 420;
        this.tauMax = sampleRate / 60;
        this.window = bufferSize - tauMax - 2;
        this.diff = new float[tauMax + 2];
        this.x = new float[bufferSize];
    }

    /** @return frequency in Hz, or -1 if no clear pitch. */
    public float detect(short[] buf, int len) {
        // DC removal + normalization
        double mean = 0;
        for (int i = 0; i < len; i++) mean += buf[i];
        mean /= len;
        double energy = 0;
        for (int i = 0; i < len; i++) {
            float v = (float) ((buf[i] - mean) / 32768.0);
            x[i] = v;
            energy += v * v;
        }
        lastRms = (float) Math.sqrt(energy / len);
        lastConfidence = 0;
        if (lastRms < 0.004f) return -1;

        int w = Math.min(window, len - tauMax - 2);
        if (w < 256) return -1;

        // Difference function
        for (int tau = 1; tau <= tauMax; tau++) {
            float sum = 0;
            for (int j = 0; j < w; j++) {
                float d = x[j] - x[j + tau];
                sum += d * d;
            }
            diff[tau] = sum;
        }
        // Cumulative mean normalized difference
        diff[0] = 1;
        float running = 0;
        for (int tau = 1; tau <= tauMax; tau++) {
            running += diff[tau];
            diff[tau] = running > 0 ? diff[tau] * tau / running : 1;
        }

        final float threshold = 0.15f;
        int tauEst = -1;
        for (int tau = tauMin; tau <= tauMax; tau++) {
            if (diff[tau] < threshold) {
                while (tau + 1 <= tauMax && diff[tau + 1] < diff[tau]) tau++;
                tauEst = tau;
                break;
            }
        }
        if (tauEst == -1) {
            // Fallback: global minimum if reasonably good
            float best = Float.MAX_VALUE;
            for (int tau = tauMin; tau <= tauMax; tau++) {
                if (diff[tau] < best) { best = diff[tau]; tauEst = tau; }
            }
            if (best > 0.35f) return -1;
        }
        lastConfidence = 1f - diff[tauEst];

        // Parabolic interpolation
        float betterTau = tauEst;
        if (tauEst > 1 && tauEst < tauMax) {
            float s0 = diff[tauEst - 1], s1 = diff[tauEst], s2 = diff[tauEst + 1];
            float denom = 2 * (2 * s1 - s2 - s0);
            if (denom != 0) betterTau = tauEst + (s2 - s0) / denom;
        }
        if (betterTau <= 0) return -1;
        return sampleRate / betterTau;
    }
}
