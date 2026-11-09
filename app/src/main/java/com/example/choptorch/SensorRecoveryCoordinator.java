package com.example.choptorch;

/** Coordinates one detector recovery episode across the accelerometer and gyroscope streams. */
final class SensorRecoveryCoordinator {
    enum Sensor { ACCELEROMETER, GYROSCOPE }

    private boolean recovering;
    private boolean accelFresh;
    private boolean gyroFresh;
    private long accelTimestampNs;
    private long gyroTimestampNs;
    private int episodeCount;
    private String lastReason = "NONE";

    boolean begin(String reason) {
        if (recovering) return false;
        recovering = true;
        accelFresh = false;
        gyroFresh = false;
        lastReason = reason;
        episodeCount++;
        return true;
    }

    /** Returns true when both streams are fresh, ordered, and temporally coherent. */
    boolean recordFresh(Sensor sensor, long timestampNs) {
        if (!recovering || timestampNs <= 0) return false;
        if (sensor == Sensor.ACCELEROMETER) {
            accelTimestampNs = timestampNs;
            accelFresh = true;
        } else {
            gyroTimestampNs = timestampNs;
            gyroFresh = true;
        }
        if (!accelFresh || !gyroFresh
                || Math.abs(accelTimestampNs - gyroTimestampNs) > SensorDeliveryGuard.MAX_GAP_NS) {
            return false;
        }
        recovering = false;
        return true;
    }

    boolean isRecovering() { return recovering; }
    int episodeCount() { return episodeCount; }
    String lastReason() { return lastReason; }
}
