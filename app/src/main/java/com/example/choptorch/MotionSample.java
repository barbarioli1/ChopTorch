package com.example.choptorch;

/** One time-aligned six-axis IMU observation and Kalman-fused orientation. */
public final class MotionSample {
    public final long timeMs;
    public final double ax, ay, az;   // gravity-removed acceleration, m/s^2
    public final double gx, gy, gz;   // rotation, rad/s
    public final double roll, pitch;  // fused angles, radians

    public MotionSample(long timeMs, double ax, double ay, double az,
                        double gx, double gy, double gz, double roll, double pitch) {
        this.timeMs = timeMs;
        this.ax = ax; this.ay = ay; this.az = az;
        this.gx = gx; this.gy = gy; this.gz = gz;
        this.roll = roll; this.pitch = pitch;
    }
}
