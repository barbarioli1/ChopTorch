package com.example.choptorch.diagnostics;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.choptorch.BuildConfig;

/** Persisted consent gate for all remote diagnostics behavior. */
public final class DiagnosticsConsent {
    private static final String PREFERENCES = "choptorch_diagnostics";
    private static final String KEY_ENABLED = "share_anonymous_diagnostics";
    private static final String KEY_GENERATION = "consent_generation";
    private final SharedPreferences preferences;
    private long generation;

    public DiagnosticsConsent(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        generation = preferences.getLong(KEY_GENERATION, 0);
    }

    public synchronized boolean isEnabled() {
        return BuildConfig.ENABLE_REMOTE_DIAGNOSTICS
            && preferences.getBoolean(KEY_ENABLED, BuildConfig.DIAGNOSTICS_CONSENT_DEFAULTS_ON);
    }

    public synchronized long generation() { return generation; }

    public synchronized long setEnabled(boolean enabled) {
        if (!BuildConfig.ENABLE_REMOTE_DIAGNOSTICS) enabled = false;
        boolean previous = preferences.getBoolean(KEY_ENABLED, BuildConfig.DIAGNOSTICS_CONSENT_DEFAULTS_ON);
        if (previous != enabled || !enabled) generation++;
        preferences.edit().putBoolean(KEY_ENABLED, enabled).putLong(KEY_GENERATION, generation).apply();
        return generation;
    }
}