package com.example.choptorch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Tiny 27 -> 18 -> 1 multilayer perceptron, trained with supervised backprop on-device. */
public final class TinyNeuralNet {
    public static final int INPUT = GestureFeatures.COUNT;
    public static final int HIDDEN = 18;
    private final double[][] first = new double[HIDDEN][INPUT];
    private final double[] hiddenBias = new double[HIDDEN];
    private final double[] second = new double[HIDDEN];
    private double outputBias;

    public TinyNeuralNet() {
        Random random = new Random(6381);
        for (int j = 0; j < HIDDEN; j++) {
            for (int i = 0; i < INPUT; i++) {
                first[j][i] = random.nextGaussian() * 0.14;
            }
            second[j] = random.nextGaussian() * 0.14;
        }
    }

    public static final class Example {
        public final double[] features;
        public final boolean positive;

        public Example(double[] features, boolean positive) {
            if (features.length != INPUT) throw new IllegalArgumentException("Unexpected features");
            this.features = features.clone();
            this.positive = positive;
        }
    }

    public static final class Metrics {
        public final double accuracy;
        public final int positives, negatives;

        public Metrics(double accuracy, int positives, int negatives) {
            this.accuracy = accuracy;
            this.positives = positives;
            this.negatives = negatives;
        }
    }

    public double probability(double[] x) {
        if (x.length != INPUT) throw new IllegalArgumentException("Unexpected features");
        double z = outputBias;
        for (int j = 0; j < HIDDEN; j++) {
            double h = hiddenBias[j];
            for (int i = 0; i < INPUT; i++) h += first[j][i] * x[i];
            z += second[j] * Math.tanh(h);
        }
        return logistic(z);
    }

    public Metrics train(List<Example> trainingExamples) {
        if (trainingExamples.size() < 12) throw new IllegalArgumentException("Need more examples");
        int positives = 0;
        for (Example e : trainingExamples) if (e.positive) positives++;
        int negatives = trainingExamples.size() - positives;
        if (positives < 6 || negatives < 6) throw new IllegalArgumentException("Need six examples in each class");
        List<Example> shuffled = new ArrayList<>(trainingExamples);
        Random rng = new Random(12102);
        // 850 short epochs. Small model/data run in a background worker.
        for (int epoch = 0; epoch < 850; epoch++) {
            Collections.shuffle(shuffled, rng);
            double lr = 0.035 * (1.0 - 0.75 * epoch / 850.0);
            for (Example e : shuffled) learn(e, lr);
        }
        int correct = 0;
        for (Example e : trainingExamples) {
            if ((probability(e.features) >= 0.5) == e.positive) correct++;
        }
        return new Metrics((double) correct / trainingExamples.size(), positives, negatives);
    }

    private void learn(Example e, double lr) {
        double[] x = e.features;
        double[] hidden = new double[HIDDEN];
        double logit = outputBias;
        for (int j = 0; j < HIDDEN; j++) {
            double z = hiddenBias[j];
            for (int i = 0; i < INPUT; i++) z += first[j][i] * x[i];
            hidden[j] = Math.tanh(z);
            logit += second[j] * hidden[j];
        }
        double error = logistic(logit) - (e.positive ? 1 : 0);
        double[] hiddenErrors = new double[HIDDEN];
        for (int j = 0; j < HIDDEN; j++) {
            hiddenErrors[j] = error * second[j] * (1 - hidden[j] * hidden[j]);
        }
        for (int j = 0; j < HIDDEN; j++) {
            second[j] -= lr * (error * hidden[j] + 0.0001 * second[j]);
            for (int i = 0; i < INPUT; i++) {
                first[j][i] -= lr * (hiddenErrors[j] * x[i] + 0.0001 * first[j][i]);
            }
            hiddenBias[j] -= lr * hiddenErrors[j];
        }
        outputBias -= lr * error;
    }

    private static double logistic(double x) {
        if (x >= 0) return 1.0 / (1.0 + Math.exp(-x));
        double ex = Math.exp(x);
        return ex / (1 + ex);
    }

    public double[][] getFirst() { return first; }
    public double[] getHiddenBias() { return hiddenBias; }
    public double[] getSecond() { return second; }
    public double getOutputBias() { return outputBias; }

    public static TinyNeuralNet fromParameters(double[][] w1, double[] b1,
                                               double[] w2, double b2) {
        if (w1.length != HIDDEN || b1.length != HIDDEN || w2.length != HIDDEN) {
            throw new IllegalArgumentException("Incompatible model");
        }
        TinyNeuralNet model = new TinyNeuralNet();
        for (int j = 0; j < HIDDEN; j++) {
            if (w1[j].length != INPUT) throw new IllegalArgumentException("Incompatible weights");
            System.arraycopy(w1[j], 0, model.first[j], 0, INPUT);
            model.hiddenBias[j] = b1[j];
            model.second[j] = w2[j];
        }
        model.outputBias = b2;
        return model;
    }
}
