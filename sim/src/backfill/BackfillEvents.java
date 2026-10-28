package backfill;

import org.cloudbus.cloudsim.core.CloudSimTags;

/**
 * Custom simulation event tags of the backfill framework, following the iFogSim2 convention
 * (an enum implementing {@link CloudSimTags}, dispatched via Java 21 enum patterns).
 */
public enum BackfillEvents implements CloudSimTags {
    /** Scheduler chip: recurring decision tick. */
    TICK,
    /** Connectivity lost for a child edge device. */
    LINK_DOWN,
    /** Connectivity restored for an edge device. */
    LINK_UP,
    /** Scheduler hand-off: transmit up to the granted list of data units. Data = {@link backfill.data.GrantPayload}. */
    EGRESS_GRANT,
    /** Outage controller bookkeeping event (start / stop an outage spell). */
    OUTAGE_MOVE,
    /** Cohort storm maintenance event. */
    STORM_MOVE
}
