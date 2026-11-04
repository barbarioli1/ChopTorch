package com.example.choptorch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Synthetic streaming tests for latency and duplicate-trigger prevention. */
public final class StreamingDetectorTest {
    private static final long DT_MS = 20;

    private static MotionSample sample(long t, boolean doubleChop, int repetitions, Random random) {
        double p = 0;
        for (int i = 0; i < repetitions; i++) {
            long offset = i * 4000L;
            p += bump(t, 2380 + offset, 85);
            if (doubleChop) p += bump(t, 3120 + offset, 85);
        }
        double noise = random.nextGaussian() * .02;
        return new MotionSample(t, 9 * p + noise, 2 * p, 3 * p,
                4.2 * p + noise, 1.0 * p, 0.4 * p,
                0.1 * p, 0.08 * p);
    }

    private static double bump(double t, double center, double width) {
        return Math.exp(-Math.pow((t - center) / width, 2));
    }

    private static GestureFeatures.Result example(boolean doubleChop, int seed) {
        ArrayList<MotionSample> recording = new ArrayList<>();
        Random random = new Random(seed);
        for (long t = 1300; t <= 3200; t += DT_MS) {
            recording.add(sample(t, doubleChop, 1, random));
        }
        return GestureFeatures.extract(recording);
    }

    public static void main(String[] args) {
        List<TinyNeuralNet.Example> train = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            train.add(new TinyNeuralNet.Example(example(true, 100+i).values, true));
            train.add(new TinyNeuralNet.Example(example(false, 300+i).values, false));
        }
        TinyNeuralNet model = new TinyNeuralNet();
        model.train(train);
        GestureDetector detector = new GestureDetector();
        ArrayList<Long> triggers = new ArrayList<>();
        Random random = new Random(505);
        for (long t=0; t<10500; t+=DT_MS) {
            GestureDetector.Detection match = detector.onSample(sample(t,true,2,random), model);
            if (match!=null) triggers.add(t);
        }
        if (triggers.size()!=2) throw new AssertionError("Expected exactly two distinct double-chop detections, got " + triggers);
        if (triggers.get(0) - 3120 > 320 || triggers.get(1) - 7120 > 320)
            throw new AssertionError("Streaming recognition not prompt enough after chop: " + triggers);
        if (triggers.get(0) < 3120 || triggers.get(1) < 7120)
            throw new AssertionError("Detected before second chop: " + triggers);

        GestureDetector negativeDetector = new GestureDetector();
        int negatives = 0;
        random = new Random(505);
        for (long t=0; t<10500; t+=DT_MS)
            if (negativeDetector.onSample(sample(t,false,2,random), model)!=null) negatives++;
        if (negatives!=0) throw new AssertionError("Single chop triggered " + negatives + " times");

        System.out.println("PASS: streaming double-chops toggled once each at " + triggers +
                ", single-chop triggers: " + negatives);
    }
}
