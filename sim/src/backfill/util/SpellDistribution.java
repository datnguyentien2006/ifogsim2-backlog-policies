package backfill.util;

import java.util.Random;

/**
 * Probability distribution of one link spell in the outage model: an up-spell (time until the
 * edge's next drop) or a down-spell (outage duration). Every family is parametrised by the
 * spell's mean and coefficient of variation, so switching family keeps the configured mean.
 *
 * <p>EXPONENTIAL and LOGNORMAL make exactly the random draws the outage model has always made
 * (same RNG calls, same clamps), so the default configuration reproduces earlier runs byte for
 * byte.</p>
 */
public enum SpellDistribution {
    /** Memoryless: drops form a Poisson process at rate 1/mean. The cv is always 1 and ignored. */
    EXPONENTIAL,
    /** Right-skewed: mostly short spells with occasional very long ones. */
    LOGNORMAL,
    /** Uniform on mean·[1 − √3·cv, 1 + √3·cv]; needs cv ≤ {@link #UNIFORM_MAX_CV}. */
    UNIFORM,
    /** Every spell lasts exactly the mean. The cv is ignored and no random number is drawn. */
    DETERMINISTIC;

    /** Largest cv a UNIFORM spell supports: its lower end reaches 0. */
    public static final double UNIFORM_MAX_CV = 1.0 / Math.sqrt(3.0);

    /** Draws one spell length in seconds (at least 1e-6). */
    public double draw(Random rng, double mean, double cv) {
        return switch (this) {
            case EXPONENTIAL -> {
                double u = rng.nextDouble();
                if (u <= 0.0) u = 1e-12;
                yield Math.max(1e-6, -mean * Math.log(u));
            }
            case LOGNORMAL -> {
                double c = Math.max(0.01, cv);
                double sigma2 = Math.log(1.0 + c * c);
                double mu = Math.log(Math.max(1e-9, mean)) - sigma2 / 2.0;
                double z = rng.nextGaussian();
                yield Math.max(1e-6, Math.exp(mu + Math.sqrt(sigma2) * z));
            }
            case UNIFORM -> {
                double halfWidth = Math.sqrt(3.0) * cv * mean;
                yield Math.max(1e-6, mean - halfWidth + 2.0 * halfWidth * rng.nextDouble());
            }
            case DETERMINISTIC -> Math.max(1e-6, mean);
        };
    }

    /** Whether {@code cv} is a usable coefficient of variation for this family. */
    public boolean acceptsCv(double cv) {
        if (!Double.isFinite(cv) || cv < 0) return false;
        return this != UNIFORM || cv <= UNIFORM_MAX_CV;
    }
}
