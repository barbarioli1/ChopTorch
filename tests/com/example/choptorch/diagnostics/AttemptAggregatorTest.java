package com.example.choptorch.diagnostics;

import com.example.choptorch.GestureDetector;
import com.example.choptorch.GestureFeatures;

import java.util.ArrayList;
import java.util.List;

public final class AttemptAggregatorTest {
    private static GestureDetector.Prediction prediction(double score, boolean shape,
            boolean triggered, long timeMs, double[] features) {
        return new GestureDetector.Prediction(score,
                new GestureFeatures.Result(features, 2, 380, 5.2, shape),
                shape && score >= GestureDetector.THRESHOLD, triggered ? 2 : 1,
                triggered, timeMs);
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        List<DiagnosticEvent> events = new ArrayList<>();
        AttemptAggregator aggregator = new AttemptAggregator(events::add);
        aggregator.accept(prediction(.49, true, false, 0, new double[27]));
        assertTrue(events.isEmpty(), ".49 must not qualify");
        aggregator.accept(prediction(.50, true, false, 160, new double[27]));
        assertTrue(!aggregator.flushIfExpired(959), ".50 must qualify for a candidate");
        assertTrue(aggregator.flushIfExpired(960), "800 ms timeout should finalize");
        assertTrue(events.size() == 1 && events.get(0).maxScore == .50, "single qualifying record");

        events.clear();
        aggregator.accept(prediction(.60, true, false, 1000, new double[27]));
        double[] bestFeatures = new double[27];
        bestFeatures[0] = .9;
        GestureDetector.Prediction best = prediction(.90, false, false, 1160, bestFeatures);
        aggregator.accept(best);
        bestFeatures[0] = .1;
        aggregator.accept(prediction(.83, true, true, 1320, new double[27]));
        assertTrue(events.size() == 1, "trigger closes attempt immediately");
        assertTrue(events.get(0).predictionCount == 3 && events.get(0).maxScore == .90,
                "predictions are grouped and the peak score wins");
        assertTrue(!events.get(0).hasDoubleChopShape && events.get(0).features()[0] == .9,
                "shape-rejected high score preserved and vectors cloned");
        double[] exposedFeatures = events.get(0).features();
        exposedFeatures[0] = .1;
        assertTrue(events.get(0).features()[0] == .9, "event feature accessor returns a defensive copy");
        assertTrue(events.get(0).triggered, "trigger status recorded");

        events.clear();
        aggregator.accept(prediction(.71, true, false, 2000, new double[27]));
        aggregator.accept(prediction(.72, true, false, 2801, new double[27]));
        assertTrue(events.size() == 1, "gap over 800 ms starts a new attempt");
        aggregator.flush();
        assertTrue(events.size() == 2, "pending candidate can be flushed");

        events.clear();
        aggregator.accept(prediction(.70, true, false, 0, new double[27]));
        aggregator.accept(prediction(.71, true, false, 4000, new double[27]));
        assertTrue(events.size() == 1 && events.get(0).durationMs == 0,
                "four-second boundary closes previous attempt before starting next");
        aggregator.flush();

        events.clear();
        aggregator.accept(prediction(Double.NaN, true, false, 0, new double[27]));
        aggregator.accept(prediction(1.01, true, false, 0, new double[27]));
        aggregator.accept(prediction(.49, false, false, 0, new double[27]));
        assertTrue(events.isEmpty(), "invalid and subthreshold scores do not create records");

        double[] invalidFeatures = new double[27];
        invalidFeatures[3] = Double.POSITIVE_INFINITY;
        aggregator.accept(prediction(.65, true, false, 10, invalidFeatures));
        aggregator.flush();
        assertTrue(events.size() == 1 && events.get(0).features() == null,
                "invalid feature vectors are omitted safely; events=" + events.size()
                        + (events.isEmpty() ? "" : ", score=" + events.get(0).maxScore
                        + (events.get(0).features() == null ? ", features=null"
                        : ", feature3=" + events.get(0).features()[3])));

        System.out.println("PASS: diagnostic attempt aggregation, thresholds, split boundaries, cloning, and finite validation");
    }
}