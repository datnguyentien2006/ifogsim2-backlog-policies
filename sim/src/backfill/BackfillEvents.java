package backfill;

import org.cloudbus.cloudsim.core.CloudSimTags;

/** Internal event tags shared by SchedulerEntity, EdgeServerDevice and OutageController. */
public enum BackfillEvents implements CloudSimTags {
    /** SchedulerEntity -> itself: recurring decision tick. */
    TICK,
    /** SchedulerEntity -> EdgeServerDevice: transmit the granted units. Data = GrantPayload. */
    EGRESS_GRANT,
    /** OutageController -> itself: one edge changes state. Data = OutageController.Transition. */
    OUTAGE_MOVE,
    /** OutageController -> itself: next storm (COHORT only). Data = null. */
    STORM_MOVE,
    /** OutageController -> EdgeServerDevice: link to the center restored. Data = null. */
    LINK_UP,
    /** OutageController -> EdgeServerDevice: link to the center lost. Data = null. */
    LINK_DOWN
}
