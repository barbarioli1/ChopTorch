package com.example.choptorch;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Shared, versioned offline dataset and model storage. Access from service and activity. */
public final class GestureStore {
    private static final String KEY_EXAMPLES = "examples_v1";
    private static final String KEY_MODEL = "model_v1";
    private static final String KEY_LAST_DETECTION = "last_on_detection_v2";
    private static final String KEY_MESSAGE = "feedback_message_v2";
    private static final long FEEDBACK_LIMIT_MS = 10 * 60 * 1000L;
    private static GestureStore instance;

    private final SharedPreferences preferences;
    private final List<TinyNeuralNet.Example> examples = new ArrayList<>();
    private TinyNeuralNet model;
    private int trainedCount = -1;

    public static synchronized GestureStore shared(Context context) {
        if (instance == null) instance = new GestureStore(context.getApplicationContext());
        return instance;
    }

    private GestureStore(Context context) {
        preferences = context.getSharedPreferences("choptorch_private", Context.MODE_PRIVATE);
        if (BuildConfig.ENABLE_TRAINING) {
            // Seed data are bundled only in development builds, never in shipping.
            // Read once, then load from preferences once: the original v2 first
            // launch accidentally kept seed examples in memory and appended them
            // again while reading preferences (19 displayed as 38).
            List<TinyNeuralNet.Example> seed = readSeedExamples(context);
            if (!preferences.contains(KEY_EXAMPLES) && seed.size() >= 12) {
                examples.addAll(seed);
                saveExamples();
            }
            examples.clear();
            try {
                JSONArray array = new JSONArray(preferences.getString(KEY_EXAMPLES, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject x = array.getJSONObject(i);
                    examples.add(new TinyNeuralNet.Example(
                            toDoubles(x.getJSONArray("x"), TinyNeuralNet.INPUT), x.getBoolean("positive")));
                }
            } catch (Exception bad) {
                examples.clear();
            }
            // Repair any copy of the v2 seed that was actually persisted twice
            // (e.g., a user retrained on the first session). Only remove the
            // duplicated recovered block, never independently recorded gestures.
            int duplicatesRemoved = RecoveredExampleDeduper.removeDuplicatedSeed(examples, seed);
            if (duplicatesRemoved != 0) saveExamples();
            try {
                JSONObject saved = new JSONObject(preferences.getString(KEY_MODEL, "{}"));
                model = decodeModel(saved);
                int originalCount = saved.getInt("trainedCount");
                trainedCount = originalCount;
                if (duplicatesRemoved > 0 && originalCount >= duplicatesRemoved) {
                    trainedCount = originalCount - duplicatesRemoved;
                    preferences.edit().putString(KEY_MODEL,
                            encodeModel(model, trainedCount).toString()).apply();
                }
            } catch (Exception ignored) {
                model = null;
                trainedCount = -1;
            }
        }
        // A shipping build uses ONLY the bundled model, never previously recorded user data.
        // Development builds may use the bundled model as a fallback until samples are recorded.
        if (model == null) {
            try (InputStream input = context.getAssets().open("choptorch_model.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                JSONObject bundled = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                model = decodeModel(bundled);
                // In development, new examples require retraining; do not silently
                // relabel old bundled weights as trained on a larger dataset.
                // In shipping, examples are intentionally never loaded.
                trainedCount = BuildConfig.ENABLE_TRAINING
                        ? bundled.optInt("trainedCount", -1) : examples.size();
            } catch (Exception missingOrInvalid) {
                model = null;
                trainedCount = -1;
            }
        }
    }

    private static List<TinyNeuralNet.Example> readSeedExamples(Context context) {
        List<TinyNeuralNet.Example> restored = new ArrayList<>();
        try (InputStream input = context.getAssets().open("choptorch_seed_examples.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONObject seed = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (seed.getInt("featureCount") != TinyNeuralNet.INPUT)
                throw new JSONException("Wrong training feature version");
            JSONArray samples = seed.getJSONArray("examples");
            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                restored.add(new TinyNeuralNet.Example(
                        toDoubles(sample.getJSONArray("x"), TinyNeuralNet.INPUT),
                        sample.getBoolean("positive")));
            }
        } catch (Exception ignored) { restored.clear(); }
        return restored;
    }

    private static double[] toDoubles(JSONArray json, int n) throws JSONException {
        if (json.length() != n) throw new JSONException("Array length mismatch");
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            values[i] = json.getDouble(i);
            if (!Double.isFinite(values[i])) throw new JSONException("Non-finite weight");
        }
        return values;
    }

    private static JSONArray asJson(double[] xs) throws JSONException {
        JSONArray array = new JSONArray();
        for (double x : xs) array.put(x);
        return array;
    }

    private static TinyNeuralNet decodeModel(JSONObject json) throws JSONException {
        if (json.getInt("featureCount") != GestureFeatures.COUNT
                || json.optInt("hiddenCount", TinyNeuralNet.HIDDEN) != TinyNeuralNet.HIDDEN
                || json.optInt("featureVersion", 1) != 1
                || json.optInt("modelVersion", 1) != 1) {
            throw new JSONException("Incompatible model format");
        }
        if (json.has("detectionThreshold")
                && Math.abs(json.getDouble("detectionThreshold") - GestureDetector.THRESHOLD) > 0.000001) {
            throw new JSONException("Detection threshold mismatch");
        }
        if (json.has("requiresDoubleChopShape") && !json.getBoolean("requiresDoubleChopShape")) {
            throw new JSONException("Unsupported double-chop gate");
        }
        if (json.has("windowMs") && json.getLong("windowMs") != GestureDetector.WINDOW_MS) {
            throw new JSONException("Sensor window mismatch");
        }
        JSONArray matrix = json.getJSONArray("w1");
        if (matrix.length() != TinyNeuralNet.HIDDEN) throw new JSONException("Bad model rows");
        double[][] w1 = new double[TinyNeuralNet.HIDDEN][];
        for (int j = 0; j < w1.length; j++) w1[j] = toDoubles(matrix.getJSONArray(j), TinyNeuralNet.INPUT);
        double b2 = json.getDouble("b2");
        if (!Double.isFinite(b2)) throw new JSONException("Non-finite output bias");
        return TinyNeuralNet.fromParameters(w1,
                toDoubles(json.getJSONArray("b1"), TinyNeuralNet.HIDDEN),
                toDoubles(json.getJSONArray("w2"), TinyNeuralNet.HIDDEN), b2);
    }

    private JSONObject encodeModel(TinyNeuralNet net, int sampleCount) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("modelVersion", 1);
        json.put("featureVersion", 1);
        json.put("featureCount", GestureFeatures.COUNT);
        json.put("hiddenCount", TinyNeuralNet.HIDDEN);
        json.put("detectionThreshold", GestureDetector.THRESHOLD);
        json.put("requiresDoubleChopShape", true);
        json.put("windowMs", GestureDetector.WINDOW_MS);
        JSONArray w1 = new JSONArray();
        for (double[] row : net.getFirst()) w1.put(asJson(row));
        json.put("w1", w1);
        json.put("b1", asJson(net.getHiddenBias()));
        json.put("w2", asJson(net.getSecond()));
        json.put("b2", net.getOutputBias());
        json.put("trainedCount", sampleCount);
        return json;
    }

    public synchronized String exportModelJson() {
        if (!isModelCurrent()) throw new IllegalStateException("Train a model before exporting");
        try { return encodeModel(model, trainedCount).toString(2); }
        catch (JSONException e) { throw new IllegalStateException(e); }
    }

    public synchronized List<TinyNeuralNet.Example> getExamples() {
        return new ArrayList<>(examples);
    }

    public synchronized int count(boolean positive) {
        int n = 0;
        for (TinyNeuralNet.Example e : examples) if (e.positive == positive) n++;
        return n;
    }

    public synchronized void addExample(double[] features, boolean positive) {
        if (!BuildConfig.ENABLE_TRAINING) throw new IllegalStateException("Training is disabled");
        examples.add(new TinyNeuralNet.Example(features, positive));
        saveExamples();
    }

    private void saveExamples() {
        JSONArray saved = new JSONArray();
        try {
            for (TinyNeuralNet.Example e : examples) {
                JSONObject item = new JSONObject();
                item.put("positive", e.positive);
                item.put("x", asJson(e.features));
                saved.put(item);
            }
        } catch (JSONException unexpected) { throw new IllegalStateException(unexpected); }
        preferences.edit().putString(KEY_EXAMPLES, saved.toString()).apply();
    }

    public synchronized boolean isModelCurrent() {
        return model != null && trainedCount == examples.size();
    }

    public synchronized TinyNeuralNet getModel() {
        return isModelCurrent() ? model : null;
    }

    public synchronized boolean saveModel(TinyNeuralNet net, int sampleCount) {
        if (!BuildConfig.ENABLE_TRAINING || examples.size() != sampleCount) return false;
        try {
            preferences.edit().putString(KEY_MODEL, encodeModel(net, sampleCount).toString()).apply();
            model = net;
            trainedCount = sampleCount;
            return true;
        } catch (JSONException unexpected) { throw new IllegalStateException(unexpected); }
    }

    /** Called only after a detected gesture has actually switched the torch ON. */
    public synchronized void rememberTorchOn(double[] features, double score) {
        if (!BuildConfig.ENABLE_TRAINING) return;
        try {
            JSONObject last = new JSONObject();
            last.put("x", asJson(features));
            last.put("confidence", score);
            last.put("at", System.currentTimeMillis());
            preferences.edit().putString(KEY_LAST_DETECTION, last.toString()).apply();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public synchronized boolean hasFeedbackCandidate() {
        if (!BuildConfig.ENABLE_TRAINING) return false;
        try {
            JSONObject json = new JSONObject(preferences.getString(KEY_LAST_DETECTION, "{}"));
            long age = System.currentTimeMillis() - json.getLong("at");
            return age >= 0 && age <= FEEDBACK_LIMIT_MS;
        } catch (Exception bad) { return false; }
    }

    /** Consume once; save the exact feature window as a NEGATIVE sample for retraining. */
    public synchronized boolean flagFalsePositive() {
        if (!hasFeedbackCandidate()) return false;
        try {
            JSONObject last = new JSONObject(preferences.getString(KEY_LAST_DETECTION, "{}"));
            double[] x = toDoubles(last.getJSONArray("x"), TinyNeuralNet.INPUT);
            // Invalidate the candidate before continuing so repeat taps cannot duplicate it.
            preferences.edit().remove(KEY_LAST_DETECTION).apply();
            addExample(x, false);
            setFeedbackMessage("False positive saved. Retraining neural network…");
            return true;
        } catch (Exception bad) {
            preferences.edit().remove(KEY_LAST_DETECTION).apply();
            return false;
        }
    }

    public synchronized void setFeedbackMessage(String text) {
        preferences.edit().putString(KEY_MESSAGE, text).apply();
    }

    public synchronized String getFeedbackMessage() {
        return preferences.getString(KEY_MESSAGE, "");
    }

    public synchronized void eraseAll() {
        if (!BuildConfig.ENABLE_TRAINING) return;
        examples.clear();
        model = null;
        trainedCount = -1;
        preferences.edit().remove(KEY_EXAMPLES).remove(KEY_MODEL)
                .remove(KEY_LAST_DETECTION).remove(KEY_MESSAGE).apply();
    }
}
