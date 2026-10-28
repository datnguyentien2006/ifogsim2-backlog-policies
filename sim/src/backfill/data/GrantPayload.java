package backfill.data;

import java.util.List;

/**
 * A scheduler decision delivered to one edge server: the exact list of data units the edge may
 * transmit upstream, in transmission order. The scheduler executes only what a policy decided.
 */
public final class GrantPayload {

    public final int edgeIndex;
    public final List<EgressUnit> units;

    public GrantPayload(int edgeIndex, List<EgressUnit> units) {
        this.edgeIndex = edgeIndex;
        this.units = units;
    }
}
