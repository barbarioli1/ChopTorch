package com.example.choptorch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixed-size features from the last ~1.9 seconds of sensor-fusion output.
 * Fixed scaling means a saved model can classify streaming windows consistently.
 */
public final class GestureFeatures {
    public static final int COUNT = 27;

    public static final class Result {
        public final double[] values;
        public final int strongPeaks;
        public final long peakGapMs;
        public final double maxGyro;
        public final boolean hasDoubleChopShape;
        /** Timestamp of the newest valid two-peak pair (for event-based re-arming). */
        public final long newestPairedPeakMs;

        public Result(double[] values, int strongPeaks, long peakGapMs,
                      double maxGyro, boolean hasDoubleChopShape, long newestPairedPeakMs) {
            this.values = values;
            this.strongPeaks = strongPeaks;
            this.peakGapMs = peakGapMs;
            this.maxGyro = maxGyro;
            this.hasDoubleChopShape = hasDoubleChopShape;
            this.newestPairedPeakMs = newestPairedPeakMs;
        }
    }

    private GestureFeatures() { }

    public static Result extract(List<MotionSample> samples) {
        if (samples == null || samples.size() < 15) return null;
        int n = samples.size();
        double[] gyroMean = new double[3], gyroMax = new double[3];
        double[] accMean = new double[3], accMax = new double[3];
        double[] gyroIntegrals = new double[3], gyroBins = new double[4];
        double gyroEnergy = 0, accelEnergy = 0, maxGyro = 0, maxAccel = 0;
        double[] energies = new double[n];
        double[] rollUnwrapped = new double[n], pitchUnwrapped = new double[n];
        long t0 = samples.get(0).timeMs;
        long duration = Math.max(1, samples.get(n - 1).timeMs - t0);
        double lastRoll = samples.get(0).roll, lastPitch = samples.get(0).pitch;
        rollUnwrapped[0] = lastRoll;
        pitchUnwrapped[0] = lastPitch;
        for (int i = 0; i < n; i++) {
            MotionSample s = samples.get(i);
            double[] a = {s.ax, s.ay, s.az};
            double[] g = {s.gx, s.gy, s.gz};
            for (int k = 0; k < 3; k++) {
                gyroMean[k] += Math.abs(g[k]) / n;
                gyroMax[k] = Math.max(gyroMax[k], Math.abs(g[k]));
                accMean[k] += Math.abs(a[k]) / n;
                accMax[k] = Math.max(accMax[k], Math.abs(a[k]));
                if (i > 0) {
                    double dt = Math.min(0.10,
                            Math.max(0, (s.timeMs - samples.get(i - 1).timeMs) / 1000.0));
                    gyroIntegrals[k] += g[k] * dt;
                }
            }
            double eG = Math.sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz);
            double eA = Math.sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az);
            energies[i] = eG;
            gyroEnergy += eG * eG / n;
            accelEnergy += eA * eA / n;
            maxGyro = Math.max(maxGyro, eG);
            maxAccel = Math.max(maxAccel, eA);
            int bin = Math.min(3, (int) ((s.timeMs - t0) * 4L / duration));
            gyroBins[bin] += eG / n * 4.0;
            if (i > 0) {
                rollUnwrapped[i] = rollUnwrapped[i - 1] + AngleKalman.wrap(s.roll - lastRoll);
                pitchUnwrapped[i] = pitchUnwrapped[i - 1] + AngleKalman.wrap(s.pitch - lastPitch);
            }
            lastRoll = s.roll;
            lastPitch = s.pitch;
        }

        // 5-sample smoothing, local maxima, and 190 ms peak non-maximum suppression.
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(n - 1, i + 2); j++) {
                sum += energies[j];
                count++;
            }
            smooth[i] = sum / count;
        }
        List<Integer> peaks = new ArrayList<>();
        for (int i = 2; i < n - 2; i++) {
            if (smooth[i] < 1.05 || smooth[i] < smooth[i - 1] || smooth[i] <= smooth[i + 1]) continue;
            if (!peaks.isEmpty() && samples.get(i).timeMs - samples.get(peaks.get(peaks.size() - 1)).timeMs < 190) {
                int old = peaks.get(peaks.size() - 1);
                if (smooth[i] > smooth[old]) peaks.set(peaks.size() - 1, i);
            } else {
                peaks.add(i);
            }
        }
        long bestGap = 0;
        boolean pairFound = false;
        double bestPairStrength = 0;
        long newestPairedPeakMs = 0;
        for (int i = 0; i < peaks.size(); i++) {
            for (int j = i + 1; j < peaks.size(); j++) {
                long gap = samples.get(peaks.get(j)).timeMs - samples.get(peaks.get(i)).timeMs;
                if (gap < 190 || gap > 1100) continue;
                newestPairedPeakMs = Math.max(newestPairedPeakMs,
                        samples.get(peaks.get(j)).timeMs);
                double strength = Math.min(smooth[peaks.get(i)], smooth[peaks.get(j)]);
                if (strength > bestPairStrength) {
                    bestPairStrength = strength;
                    bestGap = gap;
                    pairFound = true;
                }
            }
        }

        double[] features = new double[COUNT];
        int p = 0;
        for (double v : gyroMean) features[p++] = scale(v, 5);
        for (double v : gyroMax) features[p++] = scale(v, 12);
        for (double v : accMean) features[p++] = scale(v, 15);
        for (double v : accMax) features[p++] = scale(v, 35);
        features[p++] = scale(Math.sqrt(gyroEnergy), 6);
        features[p++] = scale(Math.sqrt(accelEnergy), 18);
        features[p++] = scale(maxGyro, 14);
        features[p++] = scale(maxAccel, 40);
        for (double v : gyroIntegrals) features[p++] = signedScale(v, 7);
        features[p++] = scale(range(rollUnwrapped), Math.PI);
        features[p++] = scale(range(pitchUnwrapped), Math.PI);
        features[p++] = scale(peaks.size(), 5);
        features[p++] = pairFound ? scale(bestGap, 1100) : 0;
        for (double v : gyroBins) features[p++] = scale(v, 6);
        if (p != COUNT) throw new AssertionError("Feature dimension mismatch: " + p);
        return new Result(features, peaks.size(), bestGap, maxGyro,
                pairFound, newestPairedPeakMs);
    }

    private static double scale(double x, double divisor) {
        return Math.min(2.0, Math.max(0.0, x / divisor));
    }

    private static double signedScale(double x, double divisor) {
        return Math.max(-2.0, Math.min(2.0, x / divisor));
    }

    private static double range(double[] xs) {
        double lo = Arrays.stream(xs).min().orElse(0);
        double hi = Arrays.stream(xs).max().orElse(0);
        return hi - lo;
    }
}
