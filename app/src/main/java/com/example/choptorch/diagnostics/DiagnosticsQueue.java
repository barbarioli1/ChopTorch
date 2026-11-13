package com.example.choptorch.diagnostics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Durable, bounded app-private queue. Call only from diagnostics worker threads. */
public final class DiagnosticsQueue extends SQLiteOpenHelper {
    private static final String DATABASE = "choptorch_diagnostics.db";
    private static final int VERSION = 1;
    public static final int MAX_EVENTS = 500;
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_EVENT_BYTES = 16 * 1024;

    public DiagnosticsQueue(Context context) { super(context.getApplicationContext(), DATABASE, null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (event_id TEXT PRIMARY KEY NOT NULL, created_at_ms INTEGER NOT NULL, kind TEXT NOT NULL, json_payload TEXT NOT NULL)");
        db.execSQL("CREATE TABLE status (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
        db.execSQL("INSERT INTO status(key, value) VALUES('dropped_local_event_count', '0')");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != newVersion) throw new IllegalStateException("Unsupported diagnostics queue upgrade");
    }

    public synchronized boolean enqueue(String eventId, long createdAtMs, String kind, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EVENT_BYTES) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (exists(db, eventId)) return true;
            while (count(db) >= MAX_EVENTS || totalBytes(db) + bytes.length > MAX_BYTES) {
                if (!dropOldestNonError(db)
                        && !dropOldest(db, null)) break;
                incrementDropped(db);
            }
            if (count(db) >= MAX_EVENTS || totalBytes(db) + bytes.length > MAX_BYTES) return false;
            ContentValues row = new ContentValues();
            row.put("event_id", eventId);
            row.put("created_at_ms", createdAtMs);
            row.put("kind", kind);
            row.put("json_payload", payload);
            db.insertOrThrow("events", null, row);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public synchronized List<QueuedEvent> oldest(int limit) {
        ArrayList<QueuedEvent> events = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("events",
                new String[]{"event_id", "kind", "json_payload"}, null, null,
                null, null, "created_at_ms ASC", Integer.toString(Math.min(20, Math.max(0, limit))))) {
            while (cursor.moveToNext()) {
                events.add(new QueuedEvent(cursor.getString(0), cursor.getString(1), cursor.getString(2)));
            }
        }
        return events;
    }

    public synchronized void delete(String eventId) {
        getWritableDatabase().delete("events", "event_id=?", new String[]{eventId});
    }

    public synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("events", null, null);
            db.delete("status", null, null);
            db.execSQL("INSERT INTO status(key, value) VALUES('dropped_local_event_count', '0')");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized int count() { return count(getReadableDatabase()); }

    public synchronized long totalBytes() { return totalBytes(getReadableDatabase()); }

    public synchronized long droppedCount() { return statusLong("dropped_local_event_count"); }

    public synchronized void setLastUploadError(String code) {
        putStatus("last_upload_error", code == null ? "" : code);
    }

    public synchronized String lastUploadError() { return statusString("last_upload_error"); }

    public synchronized void setLastUploadTime(long epochMs) { putStatus("last_upload_epoch_ms", Long.toString(epochMs)); }

    public synchronized long lastUploadTime() { return statusLong("last_upload_epoch_ms"); }

    private boolean exists(SQLiteDatabase db, String eventId) {
        try (Cursor cursor = db.rawQuery("SELECT 1 FROM events WHERE event_id=? LIMIT 1", new String[]{eventId})) {
            return cursor.moveToFirst();
        }
    }

    private int count(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM events", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private long totalBytes(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT COALESCE(SUM(length(CAST(json_payload AS BLOB))), 0) FROM events", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    private boolean dropOldest(SQLiteDatabase db, String kind) {
        String selection = kind == null ? "event_id=(SELECT event_id FROM events ORDER BY created_at_ms ASC LIMIT 1)"
                : "event_id=(SELECT event_id FROM events WHERE kind=? ORDER BY created_at_ms ASC LIMIT 1)";
        return db.delete("events", selection, kind == null ? null : new String[]{kind}) > 0;
    }

    private boolean dropOldestNonError(SQLiteDatabase db) {
        return db.delete("events", "event_id=(SELECT event_id FROM events "
                + "WHERE kind NOT IN ('torch_error', 'monitor_start_failure') "
                + "ORDER BY created_at_ms ASC LIMIT 1)", null) > 0;
    }

    private void incrementDropped(SQLiteDatabase db) {
        db.execSQL("UPDATE status SET value=CAST(value AS INTEGER)+1 WHERE key='dropped_local_event_count'");
    }

    private void putStatus(String key, String value) {
        ContentValues row = new ContentValues();
        row.put("key", key);
        row.put("value", value);
        getWritableDatabase().insertWithOnConflict("status", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String statusString(String key) {
        try (Cursor cursor = getReadableDatabase().query("status", new String[]{"value"},
                "key=?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        }
    }

    private long statusLong(String key) {
        try { return Long.parseLong(statusString(key)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    public static final class QueuedEvent {
        public final String eventId, kind, payload;
        QueuedEvent(String eventId, String kind, String payload) {
            this.eventId = eventId;
            this.kind = kind;
            this.payload = payload;
        }
    }
}