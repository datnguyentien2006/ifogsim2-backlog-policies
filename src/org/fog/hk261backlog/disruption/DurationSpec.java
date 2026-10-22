package org.fog.hk261backlog.disruption;

import java.util.Objects;
import java.util.Random;

public record DurationSpec(FAMILY family, double minimum, double maximum, double meanExcess) {

    public enum FAMILY {FIXED, UNIFORM, SHIFTED_EXPONENTIAL}

    public DurationSpec {
        Objects.requireNonNull(family, "family");
        Validation.requirePositive(minimum, "minimum");

        switch (family) {
            case FIXED -> {if (maximum != minimum || meanExcess != 0) throw new IllegalArgumentException();}
            case UNIFORM -> {if (!Double.isFinite(maximum) || maximum < minimum || meanExcess != 0) throw new IllegalArgumentException();}
            case SHIFTED_EXPONENTIAL -> {
                Validation.requirePositive(meanExcess, "meanExcess");
                if (maximum != Double.POSITIVE_INFINITY)
                    throw new IllegalArgumentException("SHIFTED_EXPONENTIAL must be unbounded");
                Validation.requirePositive(minimum + meanExcess, "mean duration");
            }
        }
    }

    public static DurationSpec fixed(double seconds) {
        return new DurationSpec(FAMILY.FIXED, seconds, seconds, 0);
    }

    public static DurationSpec uniform(double minimum, double maximum) {
        return new DurationSpec(FAMILY.UNIFORM, minimum, maximum, 0);
    }

    public static DurationSpec shiftedExponential(double minimum, double meanExcess) {
        return new DurationSpec(FAMILY.SHIFTED_EXPONENTIAL, minimum, Double.POSITIVE_INFINITY, meanExcess);
    }

    public double sample(Random rng) {
        Objects.requireNonNull(rng, "rng");
        double r = rng.nextDouble();
        double val = 0;

        switch (family) {
            case FIXED -> {val = minimum;}
            case UNIFORM -> {val = minimum + (maximum - minimum) * r;}
            case SHIFTED_EXPONENTIAL -> {val = minimum - meanExcess * Math.log1p(-r);}
        }

        Validation.requirePositive(val, "sample duration");
        return val;
    }

    public double mean() {
        double val = 0;

        switch (family) {
            case FIXED -> {val = minimum;}
            case UNIFORM -> {val = minimum + (maximum - minimum) / 2.0;}
            case SHIFTED_EXPONENTIAL -> {val = minimum + meanExcess;}
        }

        return val;
    }

    public String describe() {
        return switch (family) {
            case FIXED -> "FIXED(" + minimum + ")";
            case UNIFORM -> "UNIFORM(min=" + minimum + ",max=" + maximum + ")";
            case SHIFTED_EXPONENTIAL -> "SHIFTED_EXPONENTIAL(min=" + minimum + ", meanExcess=" + meanExcess + ")";
        };
    }
}
