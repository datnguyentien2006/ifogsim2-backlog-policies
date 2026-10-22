package org.fog.hk261backlog.disruption;
import java.util.Objects;

public record Transition(Outage outage, ConnectionState newState) {

    public Transition {
        Objects.requireNonNull(outage, "outage");
        Objects.requireNonNull(newState, "newState");
    }

    public int edgeKey() {
        return outage.edgeKey();
    }

    public double at() {
        return newState == ConnectionState.OFFLINE ? outage.downAt() : outage.upAt();
    }
}
