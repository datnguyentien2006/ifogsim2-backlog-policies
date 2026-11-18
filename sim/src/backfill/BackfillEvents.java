package backfill;

import org.cloudbus.cloudsim.core.CloudSimTags;

/**
 * Custom simulation event tags of the backfill framework, following the iFogSim2 convention
 * (an enum implementing {@link CloudSimTags}, dispatched via Java 21 enum patterns).
 */
public enum BackfillEvents implements CloudSimTags {
    /** SchedulerEntity -> itself: recurring decision tick. */
    TICK,
    /** OutageController -> EdgeServerDevice: link to the center lost. Data = null. */
    LINK_DOWN,
    /** OutageController -> EdgeServerDevice: link to the center restored. Data = null. */
    LINK_UP,
    /** SchedulerEntity -> EdgeServerDevice: transmit the granted units. Data = {@link backfill.data.GrantPayload}. */
    EGRESS_GRANT,
    /** OutageController -> itself: one edge changes state. Data = {@link backfill.controllers.OutageController.OutageMove}. */
    OUTAGE_MOVE,
    /** OutageController -> itself: next storm (COHORT only). Data = null. */
    STORM_MOVE
}
