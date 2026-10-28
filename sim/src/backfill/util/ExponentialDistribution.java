package backfill.util;

import java.util.Random;

import org.fog.utils.distribution.Distribution;

/**
 * Exponential (Poisson) inter-arrival time distribution with an injectable, reproducible RNG.
 * A Distribution whose {@code random} is set externally so that every simulation run of the
 * same scenario with the same seed produces byte-identical arrival streams.
 */
public class ExponentialDistribution extends Distribution {

    private final double mean;

    public ExponentialDistribution(double mean) {
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
        double u = r.nextDouble();
        if (u <= 0.0) u = 1e-12;
        return -mean * Math.log(u);
    }

    @Override
    public int getDistributionType() { return 42; }

    @Override
    public double getMeanInterTransmitTime() { return mean; }
}
