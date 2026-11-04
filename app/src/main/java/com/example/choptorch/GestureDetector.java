package com.example.choptorch;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Pure-Java streaming double-chop detector shared by background service and tests. */
public final class GestureDetector {
    public static final long WINDOW_MS = 1900;
    public static final double THRESHOLD = 0.82;
    // Keep the trained 1.9 s feature window unchanged. Only evaluate more often.
    private static final long PREDICTION_INTERVAL_MS = 60;
    private static final long PAIR_FRESH_MS = 320;
    private static final long EVENT_GUARD_MS = 1400;
    private final MotionProcessor motion = new MotionProcessor();
    private final ArrayDeque<MotionSample> history = new ArrayDeque<>();
    private long lastPredictionAt, lastTriggeredAt;
    private long lastCandidatePeakMs, lastAcceptedPeakMs;
    private int consecutiveHits;
    private double latestScore;

    public static final class Detection {
        public final double[] features;
        public final double score;
        Detection(double[] features, double score) {
            this.features = features.clone();
            this.score = score;
        }
    }

    public void reset() {
        motion.reset();
        history.clear();
        lastPredictionAt = 0;
        lastTriggeredAt = 0;
        lastCandidatePeakMs = 0;
        lastAcceptedPeakMs = 0;
        consecutiveHits = 0;
        latestScore = 0;
    }

    public void onGyroscope(float x, float y, float z) { motion.onGyroscope(x, y, z); }

    public Detection onAccelerometer(long timestamp, float x, float y, float z, TinyNeuralNet model) {
        return onSample(motion.onAccelerometer(timestamp, x, y, z), model);
    }

    /** Also used by deterministic pure-Java tests with pre-fused synthetic sensor samples. */
    Detection onSample(MotionSample sample, TinyNeuralNet model) {
        history.addLast(sample);
        while (!history.isEmpty() && sample.timeMs - history.peekFirst().timeMs > WINDOW_MS + 160)
            history.removeFirst();
        if (model == null || sample.timeMs - lastPredictionAt < PREDICTION_INTERVAL_MS) return null;
        lastPredictionAt = sample.timeMs;
        ArrayList<MotionSample> window = new ArrayList<>();
        for (MotionSample s : history) if (s.timeMs >= sample.timeMs - WINDOW_MS) window.add(s);
        // Do not change the input distribution of the pretrained network.
        if (window.size() <= 35 || sample.timeMs - window.get(0).timeMs <= 1550) return null;
        GestureFeatures.Result result = GestureFeatures.extract(window);
        if (result == null) return null;
        latestScore = model.probability(result.values);

        // The old 3-second time-only cooldown made quick intentional uses unresponsive.
        // Instead, recognize a FRESH second peak and remember the accepted pair. An
        // overlapping 1.9-second window must never toggle the torch twice.
        long pairPeak = result.newestPairedPeakMs;
        boolean fresh = result.hasDoubleChopShape && pairPeak > 0
                && sample.timeMs - pairPeak >= 0 && sample.timeMs - pairPeak <= PAIR_FRESH_MS;
        boolean newEvent = pairPeak > lastAcceptedPeakMs + 180;
        boolean pass = fresh && newEvent && latestScore >= THRESHOLD;
        if (!pass) {
            consecutiveHits = 0;
            lastCandidatePeakMs = 0;
            return null;
        }
        if (Math.abs(pairPeak - lastCandidatePeakMs) > 160) consecutiveHits = 0;
        lastCandidatePeakMs = pairPeak;
        if (++consecutiveHits < 2) return null;
        if (lastTriggeredAt != 0 && sample.timeMs - lastTriggeredAt < EVENT_GUARD_MS)
            return null;
        consecutiveHits = 0;
        lastAcceptedPeakMs = pairPeak;
        lastTriggeredAt = sample.timeMs;
        return new Detection(result.values, latestScore);
    }

    public double latestScore() { return latestScore; }
}
