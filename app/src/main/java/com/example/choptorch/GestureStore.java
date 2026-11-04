package com.example.choptorch;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local-only, offline examples and neural-net weights. No sensor data is uploaded. */
public final class GestureStore {
    private static final String KEY_EXAMPLES = "examples_v1";
    private static final String KEY_MODEL = "model_v1";
    private final SharedPreferences preferences;
    private final List<TinyNeuralNet.Example> examples = new ArrayList<>();
    private TinyNeuralNet model;
    private int trainedCount;

    public GestureStore(Context context) {
        preferences = context.getSharedPreferences("choptorch_private", Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(preferences.getString(KEY_EXAMPLES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject x = array.getJSONObject(i);
                double[] features = toDoubles(x.getJSONArray("x"), TinyNeuralNet.INPUT);
                examples.add(new TinyNeuralNet.Example(features, x.getBoolean("positive")));
            }
        } catch (Exception e) {
            examples.clear(); // Reject damaged or incompatible stored input.
        }
        try {
            JSONObject saved = new JSONObject(preferences.getString(KEY_MODEL, "{}"));
            if (saved.getInt("featureCount") != GestureFeatures.COUNT) throw new JSONException("Version");
            JSONArray matrix = saved.getJSONArray("w1");
            double[][] w1 = new double[TinyNeuralNet.HIDDEN][TinyNeuralNet.INPUT];
            for (int j = 0; j < w1.length; j++) w1[j] = toDoubles(matrix.getJSONArray(j), TinyNeuralNet.INPUT);
            model = TinyNeuralNet.fromParameters(w1,
                    toDoubles(saved.getJSONArray("b1"), TinyNeuralNet.HIDDEN),
                    toDoubles(saved.getJSONArray("w2"), TinyNeuralNet.HIDDEN),
                    saved.getDouble("b2"));
            trainedCount = saved.getInt("trainedCount");
        } catch (Exception e) {
            model = null;
            trainedCount = -1;
        }
    }

    private static double[] toDoubles(JSONArray json, int n) throws JSONException {
        if (json.length() != n) throw new JSONException("Array length mismatch");
        double[] output = new double[n];
        for (int i = 0; i < n; i++) {
            output[i] = json.getDouble(i);
            if (!Double.isFinite(output[i])) throw new JSONException("Non-finite value");
        }
        return output;
    }

    private static JSONArray asJson(double[] doubles) {
        JSONArray a = new JSONArray();
        for (double v : doubles) a.put(v);
        return a;
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

    public synchronized void saveModel(TinyNeuralNet trained, int exampleCount) {
        if (examples.size() != exampleCount) return;
        try {
            JSONObject saved = new JSONObject();
            saved.put("featureCount", GestureFeatures.COUNT);
            JSONArray w1 = new JSONArray();
            for (double[] row : trained.getFirst()) w1.put(asJson(row));
            saved.put("w1", w1);
            saved.put("b1", asJson(trained.getHiddenBias()));
            saved.put("w2", asJson(trained.getSecond()));
            saved.put("b2", trained.getOutputBias());
            saved.put("trainedCount", exampleCount);
            preferences.edit().putString(KEY_MODEL, saved.toString()).apply();
            model = trained;
            trainedCount = exampleCount;
        } catch (JSONException unexpected) { throw new IllegalStateException(unexpected); }
    }

    public synchronized void eraseAll() {
        examples.clear();
        model = null;
        trainedCount = -1;
        preferences.edit().remove(KEY_EXAMPLES).remove(KEY_MODEL).apply();
    }
}
