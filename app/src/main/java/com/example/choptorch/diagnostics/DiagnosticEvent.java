package com.example.choptorch.diagnostics;

import java.util.UUID;

/** Immutable, validated diagnostic record. Platform metadata is added by the recorder. */
public final class DiagnosticEvent {
    public static final String GESTURE_ATTEMPT = "gesture_attempt";
    public static final String SENSOR_RECOVERY = "sensor_recovery";
    public static final String TORCH_ERROR = "torch_error";
    public static final String MISSED_CHOP_REPORT = "missed_chop_report";
    public static final String MONITOR_START_FAILURE = "monitor_start_failure";

    public final String eventId;
    public final String kind;
    public final long createdAtEpochMs;
    public final Long startMonotonicMs;
    public final Long endMonotonicMs;
    public final Double maxScore;
    public final Integer predictionCount;
    private final double[] features;
    public final Integer strongPeaks;
    public final Long peakGapMs;
    public final Double maxGyro;
    public final Boolean hasDoubleChopShape;
    public final Boolean passedActivationScoreAndShape;
    public final Boolean triggered;
    public final Boolean requestedTorchOn;
    public final String torchOutcome;
    public final String torchErrorClass;
    public final Long torchConfirmationLatencyMs;
    public final Integer recoveryEpisodeCount;
    public final Long durationMs;

    private DiagnosticEvent(Builder builder) {
        if (!isUuid(builder.eventId)) throw new IllegalArgumentException("eventId must be a UUID v4");
        if (!isKind(builder.kind)) throw new IllegalArgumentException("Unsupported diagnostic kind");
        if (builder.createdAtEpochMs < 0) throw new IllegalArgumentException("createdAtEpochMs must be nonnegative");
        if (builder.maxScore != null && (!Double.isFinite(builder.maxScore)
                || builder.maxScore < 0 || builder.maxScore > 1)) {
            throw new IllegalArgumentException("maxScore must be finite and between 0 and 1");
        }
        if (builder.predictionCount != null && (builder.predictionCount < 1 || builder.predictionCount > 100)) {
            throw new IllegalArgumentException("predictionCount must be between 1 and 100");
        }
        if (builder.durationMs != null && (builder.durationMs < 0 || builder.durationMs > 4000)) {
            throw new IllegalArgumentException("durationMs must be between 0 and 4000");
        }
        if (builder.features != null) {
            if (builder.features.length != 27) throw new IllegalArgumentException("features must contain 27 values");
            for (double feature : builder.features) {
                if (!Double.isFinite(feature)) throw new IllegalArgumentException("features must be finite");
            }
        }
        eventId = builder.eventId;
        kind = builder.kind;
        createdAtEpochMs = builder.createdAtEpochMs;
        startMonotonicMs = builder.startMonotonicMs;
        endMonotonicMs = builder.endMonotonicMs;
        maxScore = builder.maxScore;
        predictionCount = builder.predictionCount;
        features = builder.features == null ? null : builder.features.clone();
        strongPeaks = builder.strongPeaks;
        peakGapMs = builder.peakGapMs;
        maxGyro = builder.maxGyro;
        hasDoubleChopShape = builder.hasDoubleChopShape;
        passedActivationScoreAndShape = builder.passedActivationScoreAndShape;
        triggered = builder.triggered;
        requestedTorchOn = builder.requestedTorchOn;
        torchOutcome = builder.torchOutcome;
        torchErrorClass = builder.torchErrorClass;
        torchConfirmationLatencyMs = builder.torchConfirmationLatencyMs;
        recoveryEpisodeCount = builder.recoveryEpisodeCount;
        durationMs = builder.durationMs;
    }

    public static Builder builder(String kind) { return new Builder(kind); }

    public double[] features() { return features == null ? null : features.clone(); }

    public DiagnosticEvent withTorch(boolean requestedOn, String outcome,
                                     String errorClass, Long latencyMs) {
        Builder copy = new Builder(kind).eventId(eventId).createdAtEpochMs(createdAtEpochMs);
        if (startMonotonicMs != null && endMonotonicMs != null) {
            copy.monotonicTimes(startMonotonicMs, endMonotonicMs);
        }
        if (maxScore != null && predictionCount != null) {
            copy.prediction(maxScore, predictionCount, features(), strongPeaks == null ? 0 : strongPeaks,
                    peakGapMs == null ? 0 : peakGapMs, maxGyro == null ? 0 : maxGyro,
                    Boolean.TRUE.equals(hasDoubleChopShape),
                    Boolean.TRUE.equals(passedActivationScoreAndShape), Boolean.TRUE.equals(triggered));
        }
        if (recoveryEpisodeCount != null) copy.recoveryEpisodeCount(recoveryEpisodeCount);
        if (durationMs != null) copy.durationMs(durationMs);
        copy.torch(requestedOn, outcome, errorClass, latencyMs);
        return copy.build();
    }

    public static final class Builder {
        private String eventId = UUID.randomUUID().toString();
        private final String kind;
        private long createdAtEpochMs = System.currentTimeMillis();
        private Long startMonotonicMs, endMonotonicMs;
        private Double maxScore;
        private Integer predictionCount;
        private double[] features;
        private Integer strongPeaks;
        private Long peakGapMs;
        private Double maxGyro;
        private Boolean hasDoubleChopShape;
        private Boolean passedActivationScoreAndShape;
        private Boolean triggered;
        private Boolean requestedTorchOn;
        private String torchOutcome = "not_requested";
        private String torchErrorClass;
        private Long torchConfirmationLatencyMs;
        private Integer recoveryEpisodeCount;
        private Long durationMs;

        private Builder(String kind) { this.kind = kind; }
        public Builder eventId(String value) { setEventId(value); return this; }
        public Builder createdAtEpochMs(long value) { createdAtEpochMs = value; return this; }
        public Builder monotonicTimes(long start, long end) { startMonotonicMs = start; endMonotonicMs = end; return this; }
        public Builder prediction(double score, int count, double[] vector, int peaks,
                                  long gapMs, double gyro, boolean shape, boolean passed,
                                  boolean didTrigger) {
            maxScore = score;
            predictionCount = count;
            features = vector == null ? null : vector.clone();
            strongPeaks = peaks;
            peakGapMs = gapMs;
            maxGyro = gyro;
            hasDoubleChopShape = shape;
            passedActivationScoreAndShape = passed;
            triggered = didTrigger;
            return this;
        }
        public Builder torch(boolean requestedOn, String outcome, String errorClass, Long latencyMs) {
            requestedTorchOn = requestedOn;
            torchOutcome = outcome;
            torchErrorClass = errorClass;
            torchConfirmationLatencyMs = latencyMs;
            return this;
        }
        public Builder recoveryEpisodeCount(int value) { recoveryEpisodeCount = value; return this; }
        public Builder durationMs(long value) { durationMs = value; return this; }
        public DiagnosticEvent build() { return new DiagnosticEvent(this); }

        private void setEventId(String value) {
            if (!isUuid(value)) throw new IllegalArgumentException("eventId must be a UUID v4");
            eventId = value;
        }
    }

    private static boolean isUuid(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).version() == 4; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean isKind(String value) {
        return GESTURE_ATTEMPT.equals(value) || SENSOR_RECOVERY.equals(value)
                || TORCH_ERROR.equals(value) || MISSED_CHOP_REPORT.equals(value)
                || MONITOR_START_FAILURE.equals(value);
    }
}