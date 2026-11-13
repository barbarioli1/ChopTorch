package com.example.choptorch;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Pure-Java streaming double-chop detector shared by background service and tests. */
public final class GestureDetector {
    public static final long WINDOW_MS = 1900;
    public static final double THRESHOLD = 0.82;
    interface Scorer { double score(GestureFeatures.Result result); }
    private final MotionProcessor motion = new MotionProcessor();
    private final ArrayDeque<MotionSample> history = new ArrayDeque<>();
    private final Scorer scorer;
    private PredictionObserver predictionObserver;
    private long lastPredictionAt, lastPositiveMatchAt, lastTriggeredAt;
    private int consecutiveHits;
    private double latestScore;

    public GestureDetector() {
        this(null);
    }

    GestureDetector(Scorer scorer) {
        this.scorer = scorer;
    }

    public static final class Detection {
        public final double[] features;
        public final double score;
        Detection(double[] features, double score) {
            this.features = features.clone();
            this.score = score;
        }
    }

    public interface PredictionObserver { void onPrediction(Prediction prediction); }

    public static final class Prediction {
        public final double score;
        public final double[] features;
        public final boolean hasDoubleChopShape;
        public final int strongPeaks;
        public final long peakGapMs;
        public final double maxGyro;
        public final boolean passedActivationScoreAndShape;
        public final int consecutiveHits;
        public final boolean triggered;
        public final long timeMs;

        public Prediction(double score, GestureFeatures.Result result, boolean pass,
                  int consecutiveHits, boolean triggered, long timeMs) {
            this.score = score;
            this.features = result.values.clone();
            this.hasDoubleChopShape = result.hasDoubleChopShape;
            this.strongPeaks = result.strongPeaks;
            this.peakGapMs = result.peakGapMs;
            this.maxGyro = result.maxGyro;
            this.passedActivationScoreAndShape = pass;
            this.consecutiveHits = consecutiveHits;
            this.triggered = triggered;
            this.timeMs = timeMs;
        }
    }

    public void setPredictionObserver(PredictionObserver observer) {
        predictionObserver = observer;
    }

    public void reset() {
        motion.reset();
        history.clear();
        lastPredictionAt = 0;
        lastPositiveMatchAt = 0;
        lastTriggeredAt = 0;
        consecutiveHits = 0;
        latestScore = 0;
    }

    /** Drops sensor history after a delivery interruption but keeps the activation cooldown. */
    public void resetForRecovery() {
        motion.reset();
        history.clear();
        lastPredictionAt = 0;
        lastPositiveMatchAt = 0;
        consecutiveHits = 0;
        latestScore = 0;
    }

    public void onGyroscope(float x, float y, float z) { motion.onGyroscope(x, y, z); }

    public Detection onAccelerometer(long timestamp, float x, float y, float z, TinyNeuralNet model) {
        MotionSample sample = motion.onAccelerometer(timestamp, x, y, z);
        history.addLast(sample);
        while (!history.isEmpty() && sample.timeMs - history.peekFirst().timeMs > WINDOW_MS + 160)
            history.removeFirst();
        if (model == null || sample.timeMs - lastPredictionAt < 160) return null;
        lastPredictionAt = sample.timeMs;
        ArrayList<MotionSample> window = new ArrayList<>();
        for (MotionSample s : history) if (s.timeMs >= sample.timeMs - WINDOW_MS) window.add(s);
        if (window.size() <= 35 || sample.timeMs - window.get(0).timeMs <= 1550) return null;
        GestureFeatures.Result result = GestureFeatures.extract(window);
        if (result == null) return null;
        latestScore = scorer == null ? model.probability(result.values) : scorer.score(result);
        boolean pass = result.hasDoubleChopShape && latestScore >= THRESHOLD;
        if (pass) lastPositiveMatchAt = sample.timeMs;
        consecutiveHits = pass ? consecutiveHits + 1 : 0;
        int predictionConsecutiveHits = consecutiveHits;
        boolean triggered = consecutiveHits >= 2 && sample.timeMs - lastTriggeredAt >= 1600;
        if (triggered) {
            consecutiveHits = 0;
            lastTriggeredAt = sample.timeMs;
        }
        PredictionObserver observer = predictionObserver;
        if (observer != null) {
            try {
                observer.onPrediction(new Prediction(latestScore, result, pass,
                        predictionConsecutiveHits, triggered, sample.timeMs));
            } catch (RuntimeException ignored) { }
        }
        return triggered ? new Detection(result.values, latestScore) : null;
    }

    public double latestScore() { return latestScore; }
    public long lastPredictionTimeMs() { return lastPredictionAt; }
    public long lastPositiveMatchTimeMs() { return lastPositiveMatchAt; }
}
