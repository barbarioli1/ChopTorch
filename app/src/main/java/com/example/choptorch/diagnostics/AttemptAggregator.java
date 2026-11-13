package com.example.choptorch.diagnostics;

import com.example.choptorch.GestureDetector;

/** Groups qualifying prediction snapshots into bounded movement attempts. */
public final class AttemptAggregator {
    public static final double LOGGING_THRESHOLD = 0.50;
    public static final long SPLIT_GAP_MS = 800;
    public static final long MAX_ATTEMPT_MS = 4000;

    public interface Listener { void onAttempt(DiagnosticEvent event); }

    private final Listener listener;
    private Candidate candidate;

    public AttemptAggregator(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener is required");
        this.listener = listener;
    }

    public synchronized void accept(GestureDetector.Prediction prediction) {
        if (prediction == null) return;
        if (!Double.isFinite(prediction.score) || prediction.score < 0 || prediction.score > 1) return;
        if (prediction.score < LOGGING_THRESHOLD) {
            flushIfExpired(prediction.timeMs);
            return;
        }
        if (candidate != null && (prediction.timeMs - candidate.lastTimeMs > SPLIT_GAP_MS
                || prediction.timeMs - candidate.startTimeMs >= MAX_ATTEMPT_MS)) {
            finish(false);
        }
        if (candidate == null) candidate = new Candidate(prediction);
        else candidate.add(prediction);
        if (prediction.triggered) finish(true);
    }

    public synchronized boolean flushIfExpired(long monotonicTimeMs) {
        if (candidate == null || monotonicTimeMs - candidate.lastTimeMs < SPLIT_GAP_MS) return false;
        finish(false);
        return true;
    }

    public synchronized void flush() {
        finish(false);
    }

    public synchronized void discard() { candidate = null; }

    private void finish(boolean triggered) {
        if (candidate == null) return;
        Candidate completed = candidate;
        candidate = null;
        GestureDetector.Prediction best = completed.best;
        double[] bestFeatures = best.features;
        double[] features = finiteVector(bestFeatures) ? bestFeatures : null;
        DiagnosticEvent event = DiagnosticEvent.builder(DiagnosticEvent.GESTURE_ATTEMPT)
                .eventId(completed.eventId)
                .monotonicTimes(completed.startTimeMs, completed.lastTimeMs)
                .prediction(best.score, completed.predictionCount, features, best.strongPeaks,
                        best.peakGapMs, finiteOrZero(best.maxGyro), best.hasDoubleChopShape,
                        best.passedActivationScoreAndShape, triggered || completed.triggered)
                .durationMs(Math.max(0, Math.min(MAX_ATTEMPT_MS,
                        completed.lastTimeMs - completed.startTimeMs)))
                .build();
        listener.onAttempt(event);
    }

    private static boolean finiteVector(double[] values) {
        if (values == null || values.length != 27) return false;
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }

    private static double finiteOrZero(double value) { return Double.isFinite(value) ? value : 0; }

    private static final class Candidate {
        final String eventId = java.util.UUID.randomUUID().toString();
        final long startTimeMs;
        long lastTimeMs;
        int predictionCount;
        boolean triggered;
        GestureDetector.Prediction best;

        Candidate(GestureDetector.Prediction first) {
            startTimeMs = first.timeMs;
            lastTimeMs = first.timeMs;
            predictionCount = 1;
            best = first;
            triggered = first.triggered;
        }

        void add(GestureDetector.Prediction prediction) {
            lastTimeMs = prediction.timeMs;
            predictionCount = Math.min(100, predictionCount + 1);
            triggered |= prediction.triggered;
            if (prediction.score > best.score) best = prediction;
        }
    }
}