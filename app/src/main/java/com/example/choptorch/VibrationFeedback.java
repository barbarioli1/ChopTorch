package com.example.choptorch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.VibrationEffect;
import android.os.Vibrator;

public final class VibrationFeedback {
    private static final String PREFERENCES_NAME = "choptorch_preferences";
    private static final String ENABLED_KEY = "vibration_feedback_enabled";

    private VibrationFeedback() { }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(ENABLED_KEY, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(ENABLED_KEY, enabled).apply();
    }

    public static void confirmTorchState(Context context, boolean torchOn) {
        if (!isEnabled(context)) return;
        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) return;

        long[] pattern = torchOn
                ? new long[]{0, 53, 63, 53}
                : new long[]{0, 83};
        int[] amplitudes = torchOn
            ? new int[]{0, 255, 0, 255}
            : new int[]{0, 255};
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }
}
