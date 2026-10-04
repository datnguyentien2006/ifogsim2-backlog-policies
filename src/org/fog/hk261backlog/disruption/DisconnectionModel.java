
package org.fog.hk261backlog.disruption;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

public final class DisconnectionModel {

    enum RandomStream {ONLINE_DURATION, OFFLINE_DURATION, INCIDENT_GAP, INCIDENT_DOWNTIME, RECOVERY_JITTER}

    public static final int SEED_SCHEME_VERSION = 1;
    static final int MAX_OUTAGES = 1_000_000;
    private static final int SHARED_STREAM = -1;

    private final long rootSeed;
    private final DurationSpec onlineDuration;
    private final DurationSpec offlineDuration;

    public DisconnectionModel(long rootSeed, DurationSpec onlineDuration, DurationSpec offlineDuration) {
        this.rootSeed = rootSeed;
        this.onlineDuration = Objects.requireNonNull(onlineDuration, "onlineDuration");
        this.offlineDuration = Objects.requireNonNull(offlineDuration, "offlineDuration");
    }

    public DisruptionPlan generateScattered(Collection<Integer> edgeKeys, double horizon) {
        Validation.requirePositive(horizon, "horizon");
        List<Integer> keys = Validation.edgeKeys(edgeKeys, "edgeKeys");
        List<Outage> outages = new ArrayList<>();

        for (Integer edge : keys) {
            Random onlineRng = new Random(streamSeed(rootSeed, edge, RandomStream.ONLINE_DURATION));
            Random offlineRng = new Random(streamSeed(rootSeed, edge, RandomStream.OFFLINE_DURATION));
            double previousRecovery = 0;
            long cycle = 0;

            while (previousRecovery < horizon) {
                double onlineGap = onlineDuration.sample(onlineRng);
                // A next start outside the observation window need not be representable.
                if (onlineGap >= horizon - previousRecovery) break;
                double downAt = addDuration(previousRecovery, onlineGap);
                if (downAt >= horizon) break;

                double upAt = addDuration(downAt, offlineDuration.sample(offlineRng));
                outages.add(new Outage(edge, cycle, downAt, upAt));
                cycle++;
                requireReasonableSize(outages);
                previousRecovery = upAt;
            }
        }

        return new DisruptionPlan(DisruptionPlan.Mode.SCATTERED, keys, horizon, outages);
    }

    public DisruptionPlan generateClustered(Collection<Integer> edgeKeys, Collection<Integer> participants, double horizon, double recoveryWindow) {
        Validation.requirePositive(horizon, "horizon");
        List<Integer> keys = Validation.edgeKeys(edgeKeys, "edgeKeys");
        List<Integer> participantSet = Validation.edgeKeys(participants, "participants");
        if (!Double.isFinite(recoveryWindow) || recoveryWindow < 0)
            throw new IllegalArgumentException("Invalid recovery window");

        for (int edge : participantSet)
            if (Collections.binarySearch(keys, edge) < 0)
                throw new IllegalArgumentException("participants must be a subset of edgeKeys");

        List<Outage> outages = new ArrayList<>();

        Random incidentGapRng = new Random(streamSeed(rootSeed, SHARED_STREAM, RandomStream.INCIDENT_GAP));
        Random incidentDowntimeRng = new Random(streamSeed(rootSeed, SHARED_STREAM, RandomStream.INCIDENT_DOWNTIME));

        Map<Integer, Random> jitterRngs = new TreeMap<>();
        for (int edge : participantSet)
            jitterRngs.put(edge, new Random(streamSeed(rootSeed,  edge, RandomStream.RECOVERY_JITTER)));

        double groupRecovery = 0.0;
        long cycle = 0;

        while (groupRecovery < horizon) {
            double U = onlineDuration.sample(incidentGapRng);
            if (U >= horizon - groupRecovery) break;
            double downAt = addDuration(groupRecovery, U);

            if (downAt >= horizon) break;

            double B = offlineDuration.sample(incidentDowntimeRng);
            double baseRecovery = addDuration(downAt, B);
            double maxUpAt = baseRecovery;

            for (int edge : participantSet) {
                Random jitterRng = jitterRngs.get(edge);
                double jitter = recoveryWindow * jitterRng.nextDouble();

                // Jitter is optional. Rounding it to zero cannot prevent clock progress
                // because baseRecovery already includes a strictly positive downtime.
                double upAt = baseRecovery + jitter;
                if (!Double.isFinite(upAt))
                    throw new IllegalArgumentException("Recovery time overflow");

                outages.add(new Outage(edge, cycle, downAt,upAt));

                requireReasonableSize(outages);
                maxUpAt = Math.max(maxUpAt, upAt);
            }

            groupRecovery = maxUpAt;
            cycle++;
        }

        return new DisruptionPlan(DisruptionPlan.Mode.CLUSTERED, keys, horizon, outages);
    }


    public long rootSeed() {
        return rootSeed;
    }

    public DurationSpec onlineDuration() {
        return onlineDuration;
    }

    public DurationSpec offlineDuration() {
        return offlineDuration;
    }

    static long streamSeed(long rootSeed, int edgeKey, RandomStream stream) {
        long z = mix64(rootSeed);
        z = mix64(z ^ edgeKey);
        return mix64(z ^ (stream.ordinal() + 1L) * 0x9E3779B97F4A7C15L);
    }

    private static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double addDuration(double time, double duration) {
        double result = time + duration;
        if (!Double.isFinite(result) || result <= time)
            throw new IllegalArgumentException("Time overflow or duration below clock precision at t=" + time);
        return result;
    }

    private static void requireReasonableSize(List<Outage> outages) {
        if (outages.size() > MAX_OUTAGES)
            throw new IllegalArgumentException("Too many outages: check duration units (seconds?)");
    }
}
