package com.example.choptorch;

import java.util.Arrays;
import java.util.List;

/** Only deduplicates the exact known seed, never new recordings or feedback. */
public final class RecoveredExampleDeduper {
    private RecoveredExampleDeduper() { }

    public static int removeDuplicatedSeed(List<TinyNeuralNet.Example> examples,
                                           List<TinyNeuralNet.Example> seed) {
        int n = seed.size();
        if (n == 0 || examples.size() < n * 2) return 0;
        for (int i = 0; i < n; i++) {
            TinyNeuralNet.Example ref = seed.get(i);
            TinyNeuralNet.Example first = examples.get(i);
            TinyNeuralNet.Example repeated = examples.get(i + n);
            if (ref.positive != first.positive || ref.positive != repeated.positive
                    || !Arrays.equals(ref.features, first.features)
                    || !Arrays.equals(ref.features, repeated.features)) return 0;
        }
        examples.subList(n, n * 2).clear();
        return n;
    }
}
