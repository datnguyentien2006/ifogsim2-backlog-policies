package backfill.util;

import java.util.Random;

import org.fog.utils.distribution.Distribution;

/**
 * Exponential (Poisson) inter-arrival time distribution with an injectable, reproducible RNG.
 * A Distribution whose {@code random} is set externally so that every simulation run of the
 * same scenario with the same seed produces byte-identical arrival streams.
 */
public class ExponentialDistribution extends Distribution {

    /** Distribution type id, distinct from the vendor ones (NORMAL = 1, DETERMINISTIC = 2, UNIFORM = 3). */
    public static final int EXPONENTIAL = 42;

    private final double mean;

    /** @param mean mean inter-arrival time in seconds (1 / rate); must be > 0. */
    public ExponentialDistribution(double mean) {
        if (Double.isNaN(mean) || mean <= 0)
            throw new IllegalArgumentException("mean must be > 0, got " + mean);
        this.mean = Math.max(1e-6, mean);
    }

    /** Convenience factory: seeds the distribution's RNG so draws are reproducible per sensor. */
    public static ExponentialDistribution seeded(double mean, long seed) {
        ExponentialDistribution d = new ExponentialDistribution(mean);
        d.setRandom(new Random(seed));
        return d;
    }

    @Override
    public double getNextValue() {
        Random r = getRandom();
        if (r == null)
            throw new IllegalStateException("RNG not set: use seeded(...) or setRandom(...)");
        double u = r.nextDouble();
        if (u <= 0.0) u = 1e-12;
        return -mean * Math.log(u);
    }

    @Override
    public int getDistributionType() { return EXPONENTIAL; }

    @Override
    public double getMeanInterTransmitTime() { return mean; }
}
