package com.example.choptorch;

/** Two-state (angle, gyro bias) Kalman filter. Angles and rates are in radians. */
public final class AngleKalman {
    private double angle, bias;
    private double p00, p01, p10, p11;
    private boolean initialized;

    public void reset() {
        angle = bias = p00 = p01 = p10 = p11 = 0;
        initialized = false;
    }

    public double update(double angleFromAccelerometer, double gyroRate,
                         double dtSeconds, double measurementNoiseScale) {
        if (!initialized) {
            angle = angleFromAccelerometer;
            initialized = true;
            p00 = p11 = 1.0;
            return angle;
        }
        double dt = Math.min(Math.max(dtSeconds, 0.001), 0.08);
        // Predict angle from angular velocity after estimated gyro-bias removal.
        angle = wrap(angle + dt * (gyroRate - bias));
        double oldP00 = p00, oldP01 = p01, oldP10 = p10, oldP11 = p11;
        p00 = oldP00 - dt * (oldP01 + oldP10) + dt * dt * oldP11 + 0.009 * dt;
        p01 = oldP01 - dt * oldP11;
        p10 = oldP10 - dt * oldP11;
        p11 = oldP11 + 0.0003 * dt;

        // Accelerometer is unreliable during fast linear motion, so raise R.
        double innovation = wrap(angleFromAccelerometer - angle);
        double s = p00 + 0.035 * Math.max(1, measurementNoiseScale);
        double k0 = p00 / s, k1 = p10 / s;
        angle = wrap(angle + k0 * innovation);
        bias += k1 * innovation;
        double predictedP00 = p00, predictedP01 = p01;
        p00 -= k0 * predictedP00;
        p01 -= k0 * predictedP01;
        p10 -= k1 * predictedP00;
        p11 -= k1 * predictedP01;
        // A tiny symmetric projection controls round-off over long sessions.
        double pOff = 0.5 * (p01 + p10);
        p01 = p10 = pOff;
        return angle;
    }

    public static double wrap(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
