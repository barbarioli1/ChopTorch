package com.example.choptorch;

/** Pure-Java guard that validates IMU timestamps and reports measurement gaps. */
public final class SensorDeliveryGuard {
    public enum Decision { ACCEPT, DROP_STALE, DROP_OUT_OF_ORDER, DROP_INVALID_TIMESTAMP, DROP_FUTURE }

    public static final long MAX_STALE_NS = 500_000_000L;
    public static final long DEFAULT_STALE_NS = MAX_STALE_NS;
    public static final long MAX_GAP_NS = 500_000_000L;
    private static final long FUTURE_TOLERANCE_NS = 100_000_000L;

    private final long staleNs;
    private long lastAcceptedNs;
    private long lastGapNs;
    public SensorDeliveryGuard() {
        this(DEFAULT_STALE_NS);
    }

    public SensorDeliveryGuard(long staleNs) {
        this.staleNs = staleNs;
    }

    /** Both timestamps must come from the elapsed-realtime clock base. */
    public Decision check(long eventNs, long nowNs) {
        lastGapNs = 0;
        if (eventNs <= 0 || nowNs <= 0) return Decision.DROP_INVALID_TIMESTAMP;
        long ageNs = nowNs - eventNs;
        if (ageNs > staleNs) {
            return Decision.DROP_STALE;
        }
        if (ageNs < -FUTURE_TOLERANCE_NS) return Decision.DROP_FUTURE;
        if (lastAcceptedNs != 0 && eventNs <= lastAcceptedNs) return Decision.DROP_OUT_OF_ORDER;
        if (lastAcceptedNs != 0) lastGapNs = eventNs - lastAcceptedNs;
        lastAcceptedNs = eventNs;
        return Decision.ACCEPT;
    }

    /** Returns the previous-to-current measurement interval for the last accepted sample. */
    public long consumeGapNs() {
        long gapNs = lastGapNs;
        lastGapNs = 0;
        return gapNs;
    }

}
