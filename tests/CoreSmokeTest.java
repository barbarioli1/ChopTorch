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
    public static void main(String[] args) {
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
