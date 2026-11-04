package com.example.choptorch;

import java.util.ArrayList;
import java.util.List;

/** Guards against the v2 first-launch duplicate seed regression. */
public final class SeedDedupTest {
    public static void main(String[] args) {
        List<TinyNeuralNet.Example> seed = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            double[] x = new double[TinyNeuralNet.INPUT];
            x[0] = i + 0.001;
            seed.add(new TinyNeuralNet.Example(x, i < 7));
        }
        List<TinyNeuralNet.Example> firstLoad = new ArrayList<>(seed);
        if (RecoveredExampleDeduper.removeDuplicatedSeed(firstLoad, seed) != 0
                || firstLoad.size() != 19)
            throw new AssertionError("Valid 19 example seed unexpectedly modified");

        List<TinyNeuralNet.Example> broken = new ArrayList<>(seed);
        broken.addAll(seed);
        double[] extraX = new double[TinyNeuralNet.INPUT];
        extraX[0] = 12345;
        broken.add(new TinyNeuralNet.Example(extraX, false));
        if (RecoveredExampleDeduper.removeDuplicatedSeed(broken, seed) != 19
                || broken.size() != 20 || broken.get(19).features[0] != 12345)
            throw new AssertionError("Could not safely repair duplicate recovered dataset");
        if (RecoveredExampleDeduper.removeDuplicatedSeed(broken, seed) != 0)
            throw new AssertionError("Repeated repair changed independent examples");
        System.out.println("PASS: restored dataset and duplicate-seed repair");
    }
}
