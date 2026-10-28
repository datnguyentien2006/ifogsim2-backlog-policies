package backfill;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.fog.entities.FogDevice;
import org.fog.entities.FogBroker;
import org.fog.entities.Sensor;
import org.fog.placement.Controller;
import org.fog.placement.ModuleMapping;
import org.fog.placement.ModulePlacementMapping;
import org.fog.utils.TimeKeeper;

import backfill.devices.EdgeServerDevice;
import backfill.metrics.UnitRegistry;
import backfill.policy.SchedulingPolicy;

/**
 * Assembles and runs ONE complete backfill simulation (topology + sensors + outage controller +
 * scheduling controller + iFogSim2 engine) and finalizes the measurement CSVs.
 */
public final class BackfillSim {

    /**
     * Runs one scenario × policy × seed combination.
     *
     * @return concrete simulated end time (for summary bookkeeping)
     */
    public static double run(SimParams p, String policyKey, String outDir, String runName) throws Exception {
        SchedulingPolicy policy = SchedulingPolicy.byKey(policyKey);

        // debug hooks (unused in normal runs)
        boolean debug = "1".equals(System.getenv("BF_DEBUG"));
        if (!debug) Log.disable();
        String durEnv = System.getenv("BF_DUR");
        if (durEnv != null && !durEnv.isBlank()) {
            p = p.copy();
            p.simDuration = Double.parseDouble(durEnv);
        }

        Calendar calendar = Calendar.getInstance();
        // Fine event granularity: per-tuple transmit delays are sub-millisecond.
        CloudSim.init(1, calendar, false, 0.001);

        UnitRegistry.beginRun(p, policyKey, p.seed, outDir, runName);

        FogBroker broker = new FogBroker("broker");
        var app = BackfillTopology.createApplication(broker.getId(), p);
        List<FogDevice> devices = BackfillTopology.buildDevices(p);

        List<EdgeServerDevice> edgeDevs = new ArrayList<>();
        for (FogDevice d : devices) {
            if (d instanceof EdgeServerDevice e) edgeDevs.add(e);
        }

        // The scheduling controller owns the ACTIVE scheduling policy and executes its decisions.
        new backfill.controllers.SchedulerEntity("scheduler", p, edgeDevs, policy);
        // Connectivity-event controller (scenario step 2).
        new backfill.controllers.OutageController("outages", p, edgeDevs);

        List<Sensor> sensors = new ArrayList<>();
        for (FogDevice d : edgeDevs) {
            sensors.add(BackfillTopology.addSensor(p, ((EdgeServerDevice) d).edgeIndex, (EdgeServerDevice) d, broker.getId()));
        }

        Controller controller = new Controller("master-controller", devices, sensors, new ArrayList<>());

        ModuleMapping mapping = ModuleMapping.createModuleMapping();
        for (FogDevice d : edgeDevs) mapping.addModuleToDevice(BackfillTopology.MODULE_EDGE, d.getName());
        for (FogDevice d : devices) {
            if (d.getName().equals("center")) mapping.addModuleToDevice(BackfillTopology.MODULE_CENTER, d.getName());
        }
        controller.submitApplication(app, 0, new ModulePlacementMapping(devices, app, mapping));

        TimeKeeper.getInstance().setSimulationStartTime(Calendar.getInstance().getTimeInMillis());

        // Warmup: sensors start after Config.TRANSMISSION_START_DELAY (50 s); data window starts then.
        double stopAt = 50.0 + p.simDuration + 5.0;
        CloudSim.terminateSimulation(stopAt);

        double end = CloudSim.startSimulation();  // returns the final sim clock (clock is reset afterwards)
        CloudSim.stopSimulation();

        UnitRegistry.endRun(end);
        return end;
    }
}
