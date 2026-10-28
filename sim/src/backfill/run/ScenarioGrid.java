package backfill.run;

import java.util.ArrayList;
import java.util.List;

import backfill.SimParams;
import backfill.SimParams.ImbalanceMode;
import backfill.SimParams.OutageMode;

/**
 * The experimental grid: a BASE scenario plus one-factor-at-a-time sweeps over the three
 * project dimensions (number of edge servers, workload vs. center capacity, backlog
 * imbalance) and the outage regime (scattered vs. simultaneous reconnection storms).
 */
public final class ScenarioGrid {

    private ScenarioGrid() {}

    /** BASE: N=6, rho=0.7, EVEN generation, scattered outages. All sweeps derive from this. */
    public static SimParams base() {
        return new SimParams(); // defaults are exactly the base values
    }

    /** All distinct scenarios of the experiment plan (base included once). */
    public static List<SimParams> grid() {
        List<SimParams> list = new ArrayList<>();
        list.add(base()); // "base" doubles as N6 / rho0.7 anchor of both sweeps

        // Dimension A — number of edge servers
        for (int n : new int[] {4, 10, 16}) {
            SimParams p = base();
            p.name = "N" + n;
            p.numEdges = n;
            list.add(p);
        }

        // Dimension B — workload rho = total new-rate / center capacity
        for (double rho : new double[] {0.4, 0.9, 1.1}) {
            SimParams p = base();
            p.name = "rho" + fmtRho(rho);
            p.loadFactor = rho;
            list.add(p);
        }

        // Dimension C — backlog imbalance among edge servers
        SimParams zipf = base();
        zipf.name = "imbalance_zipf";
        zipf.imbalance = ImbalanceMode.ZIPF;
        list.add(zipf);

        SimParams dom = base();
        dom.name = "imbalance_dominant";
        dom.imbalance = ImbalanceMode.DOMINANT;
        list.add(dom);

        // Dimension D — outage regime: many edges reconnect together
        SimParams storm = base();
        storm.name = "storm";
        storm.outageMode = OutageMode.COHORT;
        list.add(storm);

        return list;
    }

    /** Lookup by scenario name (RunMain / CLI). */
    public static SimParams byName(String name) {
        for (SimParams p : grid()) {
            if (p.name.equals(name)) return p;
        }
        throw new IllegalArgumentException("Unknown scenario: " + name + " (see ScenarioGrid.grid())");
    }

    public static String fmtRho(double rho) {
        return String.valueOf(Math.round(rho * 10.0) / 10.0).replace(".0", "");
    }
}
