import com.example.choptorch.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Pure Java synthetic tests. They don't substitute for testing on a real phone. */
public final class CoreSmokeTest {
    static List<MotionSample> makeRecording(boolean doubleChop, int seed) {
        Random r = new Random(seed);
        List<MotionSample> recordings = new ArrayList<>();
        for (int i = 0; i < 95; i++) {
            double t = i * .02;
            double p1 = Math.exp(-Math.pow((t - .38) / .085, 2));
            double p2 = doubleChop ? Math.exp(-Math.pow((t - 1.12) / .09, 2)) : 0;
            double a = p1 + p2;
            double noise = r.nextGaussian() * .065;
            double gX = 3.7 * (p1 - .8 * p2) + noise;
            double gY = .9 * a + noise;
            double gZ = .6 * (p1 - p2) + noise;
            recordings.add(new MotionSample(i*20,
                    11 * a + noise, 2*a, 3*a, gX,gY,gZ,
                    .1*a, .05*a));
        }
        return recordings;
    }

    private static List<TinyNeuralNet.Example> trainingExamples(int positives, int negatives) {
        ArrayList<TinyNeuralNet.Example> examples = new ArrayList<>();
        for (int index = 0; index < positives; index++) {
            examples.add(new TinyNeuralNet.Example(new double[TinyNeuralNet.INPUT], true));
        }
        for (int index = 0; index < negatives; index++) {
            examples.add(new TinyNeuralNet.Example(new double[TinyNeuralNet.INPUT], false));
        }
        return examples;
    }

    private static void assertTrainingRejected(int positives, int negatives) {
        try {
            new TinyNeuralNet().train(trainingExamples(positives, negatives));
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected training rejection for " + positives + "/" + negatives);
    }

    private static void verifyTrainingClassMinimums() {
        assertTrainingRejected(5, 6);
        assertTrainingRejected(6, 5);
        TinyNeuralNet.Metrics metrics = new TinyNeuralNet().train(trainingExamples(6, 6));
        if (metrics.positives != 6 || metrics.negatives != 6) {
            throw new AssertionError("Training should accept exactly six examples per class");
        }
    }

    private static GestureFeatures.Result makePeakPair(long gapMs) {
        ArrayList<MotionSample> samples = new ArrayList<>();
        long firstPeakMs = 300;
        long secondPeakMs = firstPeakMs + gapMs;
        for (long timeMs = 0; timeMs <= 1800; timeMs += 10) {
            double first = Math.exp(-Math.pow((timeMs - firstPeakMs) / 40.0, 2));
            double second = Math.exp(-Math.pow((timeMs - secondPeakMs) / 40.0, 2));
            samples.add(new MotionSample(timeMs, 0, 0, 0, 4 * (first + second), 0, 0, 0, 0));
        }
        return GestureFeatures.extract(samples);
    }

    private static void assertPeakPairGap(long gapMs, boolean expectedShape) {
        GestureFeatures.Result result = makePeakPair(gapMs);
        long expectedReportedGap = expectedShape ? gapMs : 0;
        if (result == null || result.hasDoubleChopShape != expectedShape
                || result.peakGapMs != expectedReportedGap) {
            throw new AssertionError("Unexpected peak-pair result for " + gapMs + " ms: shape="
                    + (result != null && result.hasDoubleChopShape) + ", gap="
                    + (result == null ? "null" : result.peakGapMs));
        }
    }

    private static void verifyPeakPairBoundaries() {
        assertPeakPairGap(180, false);
        assertPeakPairGap(190, true);
        assertPeakPairGap(200, true);
        assertPeakPairGap(1100, true);
        assertPeakPairGap(1110, false);
    }

    private static void verifyTimestampDiscontinuities() {
        MotionProcessor processor = new MotionProcessor();
        processor.onGyroscope(1.0, 2.0, 3.0);
        MotionSample initial = processor.onAccelerometer(2_000_000_000L, 0, 0, 9.80665);
        MotionSample nonmonotonic = processor.onAccelerometer(1_500_000_000L, 0, 0, 9.80665);
        processor.onGyroscope(1.0, 2.0, 3.0);
        MotionSample afterGap = processor.onAccelerometer(2_100_000_001L, 0, 0, 9.80665);
        if (initial.gx != 1.0 || nonmonotonic.gx != 0.0 || afterGap.gx != 0.0
                || !Double.isFinite(nonmonotonic.roll) || !Double.isFinite(afterGap.pitch)) {
            throw new AssertionError("Motion fusion did not safely reset across timestamp discontinuities");
        }
    }

    public static void main(String[] args) {
        verifyTrainingClassMinimums();
        verifyPeakPairBoundaries();
        verifyTimestampDiscontinuities();
        GestureFeatures.Result doubleMotion = GestureFeatures.extract(makeRecording(true, 1));
        GestureFeatures.Result singleMotion = GestureFeatures.extract(makeRecording(false, 2));
        if (doubleMotion == null || !doubleMotion.hasDoubleChopShape || doubleMotion.strongPeaks < 2) {
            throw new AssertionError("Synthetic double-chop gate didn't detect both peaks");
        }
        if (singleMotion == null || singleMotion.hasDoubleChopShape) {
            throw new AssertionError("Single chop mistaken for double chop");
        }
        ArrayList<TinyNeuralNet.Example> training = new ArrayList<>();
        for (int i=0;i<15;i++) {
            training.add(new TinyNeuralNet.Example(GestureFeatures.extract(makeRecording(true, 20+i)).values, true));
            training.add(new TinyNeuralNet.Example(GestureFeatures.extract(makeRecording(false, 80+i)).values, false));
        }
        TinyNeuralNet net = new TinyNeuralNet();
        TinyNeuralNet.Metrics m = net.train(training);
        double p1 = net.probability(GestureFeatures.extract(makeRecording(true, 222)).values);
        double p0 = net.probability(GestureFeatures.extract(makeRecording(false, 333)).values);
        if (p1 < .8 || p0 > .2) {
            throw new AssertionError("Failed synthetic train/test: " + p1 + ", " + p0);
        }
        MotionProcessor processor = new MotionProcessor();
        processor.onGyroscope(0, 0, 0);
        MotionSample stationary = null;
        for (int i=1;i<250;i++) {
            stationary = processor.onAccelerometer(i*20_000_000L, 0, 0, 9.80665);
        }
        if (stationary == null || Math.abs(stationary.az)>0.05 ||
                Math.abs(stationary.roll)>0.03 || Math.abs(stationary.pitch)>0.03) {
            throw new AssertionError("Stationary fused gravity should be close to zero linear acceleration");
        }
        System.out.printf("PASS: %d gesture features, peaks=%d, gap=%d ms, training accuracy=%.0f%%, held-out positive=%.3f, negative=%.3f%n",
                doubleMotion.values.length,doubleMotion.strongPeaks,doubleMotion.peakGapMs,m.accuracy*100,p1,p0);
    }
}
