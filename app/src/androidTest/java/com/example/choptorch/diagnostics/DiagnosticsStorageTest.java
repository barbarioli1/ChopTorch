package com.example.choptorch.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.choptorch.BuildConfig;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class DiagnosticsStorageTest {
    private Context context;
    private DiagnosticsQueue queue;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.deleteDatabase("choptorch_diagnostics.db");
        context.getSharedPreferences("choptorch_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
        queue = new DiagnosticsQueue(context);
    }

    @After public void tearDown() {
        queue.close();
        context.deleteDatabase("choptorch_diagnostics.db");
        context.getSharedPreferences("choptorch_diagnostics", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void consentDefaultsOffAndBuildFlagGatesIt() {
        DiagnosticsConsent consent = new DiagnosticsConsent(context);
        assertFalse(consent.isEnabled());
        consent.setEnabled(true);
        assertEquals(BuildConfig.ENABLE_REMOTE_DIAGNOSTICS, consent.isEnabled());
        if (!BuildConfig.ENABLE_REMOTE_DIAGNOSTICS) assertFalse(consent.isEnabled());
    }

    @Test public void queueIsBoundedAndDropsOldestNonErrorFirst() {
        for (int index = 0; index <= DiagnosticsQueue.MAX_EVENTS; index++) {
            String eventId = String.format(java.util.Locale.US, "%04d", index);
            String kind = index == 0 ? DiagnosticEvent.TORCH_ERROR : DiagnosticEvent.GESTURE_ATTEMPT;
            assertTrue(queue.enqueue(eventId, index, kind, "{}"));
        }
        assertEquals(DiagnosticsQueue.MAX_EVENTS, queue.count());
        assertEquals(1, queue.droppedCount());
        assertEquals("0000", queue.oldest(1).get(0).eventId);
    }

    @Test public void queueEnforcesSerializedByteLimit() {
        String payload = new String(new char[DiagnosticsQueue.MAX_EVENT_BYTES]).replace('\0', 'x');
        assertFalse(queue.enqueue("oversized", 0, DiagnosticEvent.GESTURE_ATTEMPT, payload));
        assertEquals(0, queue.count());
    }
}
