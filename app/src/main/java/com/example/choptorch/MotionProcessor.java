package com.example.choptorch;

/** Align the most recent gyro reading with each accelerometer update; fuse roll/pitch. */
public final class MotionProcessor {
    private final AngleKalman rollFilter = new AngleKalman();
    private final AngleKalman pitchFilter = new AngleKalman();
    private double gx, gy, gz;
    private long previousAccelerationNs;

    public void reset() {
        rollFilter.reset();
        pitchFilter.reset();
        gx = gy = gz = 0;
        previousAccelerationNs = 0;
    }

    public void onGyroscope(double x, double y, double z) {
        gx = x; gy = y; gz = z;
    }

    public MotionSample onAccelerometer(long tNs, double ax, double ay, double az) {
        double dt = previousAccelerationNs == 0 ? 0.02
                : (tNs - previousAccelerationNs) / 1_000_000_000.0;
        if (dt <= 0 || dt > 0.5) {
            reset();
            dt = 0.02;
        }
        previousAccelerationNs = tNs;

        double rollMeasurement = Math.atan2(ay, az);
        double pitchMeasurement = Math.atan2(-ax, Math.hypot(ay, az));
        double accelDeviation = Math.abs(Math.sqrt(ax * ax + ay * ay + az * az) - 9.80665);
        double trustPenalty = 1.0 + Math.pow(accelDeviation / 1.7, 2.0) * 18.0;
        double roll = rollFilter.update(rollMeasurement, gx, dt, trustPenalty);
        double pitch = pitchFilter.update(pitchMeasurement, gy, dt, trustPenalty);

        // Reconstruct gravity from Kalman angles to estimate linear acceleration.
        double gX = -Math.sin(pitch) * 9.80665;
        double gY = Math.sin(roll) * Math.cos(pitch) * 9.80665;
        double gZ = Math.cos(roll) * Math.cos(pitch) * 9.80665;
        return new MotionSample(tNs / 1_000_000L,
                ax - gX, ay - gY, az - gZ, gx, gy, gz, roll, pitch);
    }
}
