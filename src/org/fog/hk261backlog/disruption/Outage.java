package org.fog.hk261backlog.disruption;

public record Outage(int edgeKey, long cycle, double downAt, double upAt) {

    public Outage {
        if (edgeKey < 0 || cycle < 0
            || !Double.isFinite(downAt) || !Double.isFinite(upAt)
            || downAt < 0 || upAt <= downAt)
            { throw new IllegalArgumentException("Tham số truyền vào không hợp lệ."); }
        // -0.0 and +0.0 denote the same instant and must use the same sort key.
        if (downAt == 0.0) downAt = 0.0;
    }

    public boolean covers(double time) {
        return (time >= downAt && time < upAt);
    }

    public double plannedDuration() {
        return upAt - downAt;
    }

    /** @deprecated Use plannedDuration(). Retained for existing callers. */
    @Deprecated
    public double planneDuration() {
        return plannedDuration();
    }

    public double observedDuration(double horizon) {
        return Math.max(
            0.0,
            Math.min(upAt, horizon) - Math.max(downAt, 0.0)
        );
    }

    public boolean recoversBefore(double horizon) {
        return upAt < horizon;
    }
}
