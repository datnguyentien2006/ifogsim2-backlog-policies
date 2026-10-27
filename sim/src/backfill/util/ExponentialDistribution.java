package backfill.util;

import java.util.Random;

import org.fog.utils.distribution.Distribution;

/** Exponential distribution with a seeded RNG, usable as a Sensor inter-arrival distribution. */
public class ExponentialDistribution extends Distribution {

    /** Distribution type id, distinct from the vendor ones (NORMAL = 1, DETERMINISTIC = 2, UNIFORM = 3). */
    public static final int EXPONENTIAL = 42;

    private final double mean;

    public ExponentialDistribution(double mean) {
        requirePositive(mean, "mean");
        this.mean = mean;
    }

    /** Creates a distribution with its own Random(seed). */
    public static ExponentialDistribution seeded(double mean, long seed) {
        ExponentialDistribution d = new ExponentialDistribution(mean);
        d.setRandom(new Random(seed));
        return d;
    }

    /** Draws one inter-arrival time. */
    @Override
    public double getNextValue() {
        Random rng = getRandom();
        if (rng == null)
            throw new IllegalStateException("RNG not set: use seeded(...) or setRandom(...)");

        double u = rng.nextDouble();
        if (u <= 0.0) u = 1e-12;

        return -mean * Math.log(u);
    }

    @Override
    public int getDistributionType() { return EXPONENTIAL; }

    @Override
    public double getMeanInterTransmitTime() { return mean; }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and > 0, got " + value);
        }
    }
}
