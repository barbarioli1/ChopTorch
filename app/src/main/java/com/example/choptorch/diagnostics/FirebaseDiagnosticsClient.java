package com.example.choptorch.diagnostics;

import android.content.Context;

import com.google.android.gms.tasks.Task;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Firebase entry points used only by the consent-gated background worker. */
public final class FirebaseDiagnosticsClient {
    private FirebaseDiagnosticsClient() { }

    public static String ensureAnonymousUser(long timeoutSeconds) throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser current = auth.getCurrentUser();
        if (current != null) return current.getUid();
        await(auth.signInAnonymously(), timeoutSeconds, TimeUnit.SECONDS);
        current = auth.getCurrentUser();
        if (current == null) throw new IllegalStateException("AUTH_UNAVAILABLE");
        return current.getUid();
    }

    public static void writeEvent(String uid, DiagnosticsQueue.QueuedEvent event,
                                  long timeoutSeconds) throws Exception {
        JSONObject json = new JSONObject(event.payload);
        if (!event.eventId.equals(json.optString("eventId"))) {
            throw new IllegalArgumentException("EVENT_ID_MISMATCH");
        }
        Map<String, Object> document = toMap(json);
        Task<Void> write = FirebaseFirestore.getInstance()
                .collection("diagnosticEvents").document(uid)
                .collection("events").document(event.eventId)
                .set(document);
        await(write, timeoutSeconds, TimeUnit.SECONDS);
    }

    public static void setNetworkEnabled(Context context, boolean enabled) {
        if (FirebaseApp.getApps(context.getApplicationContext()).isEmpty()) return;
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();
        if (enabled) firestore.enableNetwork();
        else firestore.disableNetwork();
    }

    private static Map<String, Object> toMap(JSONObject json) throws Exception {
        HashMap<String, Object> result = new HashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            result.put(key, convert(value));
        }
        return result;
    }

    private static Object convert(Object value) throws Exception {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            ArrayList<Object> result = new ArrayList<>(array.length());
            for (int index = 0; index < array.length(); index++) result.add(convert(array.get(index)));
            return result;
        }
        return value;
    }

    private static <T> T await(Task<T> task, long timeout, TimeUnit unit) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        task.addOnCompleteListener(done -> {
            if (done.isSuccessful()) result.set(done.getResult());
            else {
                Exception exception = done.getException();
                failure.set(exception == null ? new IllegalStateException("FIREBASE_TASK_FAILED") : exception);
            }
            completed.countDown();
        });
        if (!completed.await(timeout, unit)) throw new java.util.concurrent.TimeoutException("FIREBASE_ACK_TIMEOUT");
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}