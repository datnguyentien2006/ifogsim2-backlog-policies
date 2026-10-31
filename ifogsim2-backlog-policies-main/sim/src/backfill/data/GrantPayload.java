package backfill.data;
import java.util.List;
/*Contain the exact list of data units (in order which is
 * decided by policies) that the edge should transmit upstream
 * This GrantPayLoad actually plays the role as a packet for rightful data units' order
 */
public final class GrantPayload {
    public final int edgeIndex;
    public final List<EgressUnit> units;
    public GrantPayload(int edgeIndex, List<EgressUnit> units) {
        this.edgeIndex = edgeIndex;
        this.units = units;
    }
}