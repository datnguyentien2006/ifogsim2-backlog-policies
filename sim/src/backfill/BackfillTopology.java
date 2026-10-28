package backfill;

import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.cloudbus.cloudsim.sdn.overbooking.BwProvisionerOverbooking;
import org.cloudbus.cloudsim.sdn.overbooking.PeProvisionerOverbooking;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.application.selectivity.FractionalSelectivity;
import org.fog.entities.FogDevice;
import org.fog.entities.FogDeviceCharacteristics;
import org.fog.entities.Sensor;
import org.fog.entities.Tuple;
import org.fog.policy.AppModuleAllocationPolicy;
import org.fog.scheduler.StreamOperatorScheduler;
import org.fog.utils.FogLinearPowerModel;
import org.fog.utils.FogUtils;

import backfill.devices.CentralReceiverDevice;
import backfill.devices.EdgeServerDevice;
import backfill.util.ExponentialDistribution;

/**
 * Physical topology + application model of the backfill case study, built with the standard
 * iFogSim2 construction pattern (fog device hierarchy + application graph + sensors):
 *
 * <pre>
 *   [sensor_i] --GEN_DATA--> [ingest @ edge_i] --PAYLOAD--> [collector @ center]
 * </pre>
 *
 * Per-edge Poisson generation rates; outage events cut the edge_i uplink. The {@code ingest}
 * module executes instantly on the edges, upstream flow is intercepted at
 * {@link EdgeServerDevice#sendUp} (buffer + grant release), and the shared receiving capacity
 * is enforced by the scheduler's token bucket.
 */
public final class BackfillTopology {

    public static final String APP_ID = "backfill_service";
    public static final String MODULE_EDGE = "ingest";
    public static final String MODULE_CENTER = "collector";
    public static final String SENSOR_TUPLE = "GEN_DATA";

    private BackfillTopology() {}

    // ------------------------------------------------------------------ topology

    /** Builds the receiving center plus {@code p.numEdges} edge servers. */
    public static List<FogDevice> buildDevices(SimParams p) throws Exception {
        List<FogDevice> all = new ArrayList<>();

        CentralReceiverDevice center;
        {
            PowerHost host = makeHost(p.centerMips, p.centerRamMb, p.centerBusyPowerW, p.centerIdlePowerW);
            FogDeviceCharacteristics ch = characteristics(host);
            center = new CentralReceiverDevice("center",
                    ch,
                    new AppModuleAllocationPolicy(hostList(host)),
                    storageList(), 10,
                    p.uplinkBandwidth, p.downlinkBandwidth, 0, 0);
            center.setParentId(-1);
            center.setLevel(0);
            all.add(center);
        }

        for (int i = 0; i < p.numEdges; i++) {
            PowerHost host = makeHost(p.edgeMips, p.edgeRamMb, p.edgeBusyPowerW, p.edgeIdlePowerW);
            FogDeviceCharacteristics ch = characteristics(host);
            EdgeServerDevice edge = new EdgeServerDevice("edge-" + i, i,
                    ch,
                    new AppModuleAllocationPolicy(hostList(host)),
                    storageList(), 10,
                    p.uplinkBandwidth, p.downlinkBandwidth, 0, 0);
            edge.setParentId(center.getId());
            edge.setUplinkLatency(p.edgeUplinkLatency);
            edge.setLevel(1);
            center.addChild(edge.getId());
            all.add(edge);
        }
        return all;
    }

    private static PowerHost makeHost(double mips, int ramMb, double busyPower, double idlePower) {
        return new PowerHost(
                FogUtils.generateEntityId(),
                new RamProvisionerSimple(ramMb),
                new BwProvisionerOverbooking(10_000),
                1_000_000L,
                peList(mips),
                new StreamOperatorScheduler(peList(mips)),
                new FogLinearPowerModel(busyPower, idlePower));
    }

    private static FogDeviceCharacteristics characteristics(PowerHost host) {
        return new FogDeviceCharacteristics("x86", "Linux", "Xen", host, 10.0, 3.0, 0.05, 0.001, 0.0);
    }

    private static List<Pe> peList(double mips) {
        List<Pe> pes = new ArrayList<>();
        pes.add(new Pe(0, new PeProvisionerOverbooking(mips)));
        return pes;
    }

    private static List<Host> hostList(PowerHost host) {
        List<Host> hosts = new ArrayList<>();
        hosts.add(host);
        return hosts;
    }

    private static List<Storage> storageList() {
        return new ArrayList<>();
    }

    // ------------------------------------------------------------------ application

    public static Application createApplication(int userId, SimParams p) {
        Application app = Application.createApplication(APP_ID, userId);

        app.addAppModule(MODULE_EDGE, 500, (int) Math.min(10_000, p.edgeMips), 10_000);
        app.addAppModule(MODULE_CENTER, 1000, (int) Math.min(60_000, p.centerMips), 20_000);

        // sensor -> edge module (sensor edge type)
        app.addAppEdge(SENSOR_TUPLE, MODULE_EDGE,
                p.tupleCpuLength, p.tupleNwLength, SENSOR_TUPLE, Tuple.UP, AppEdge.SENSOR);
        // edge module -> center module (the "data unit" PAYLOAD edge)
        app.addAppEdge(MODULE_EDGE, MODULE_CENTER,
                p.tupleCpuLength, p.tupleNwLength, CentralReceiverDevice.PAYLOAD_TUPLE_TYPE, Tuple.UP, AppEdge.MODULE);

        app.addTupleMapping(MODULE_EDGE, SENSOR_TUPLE, CentralReceiverDevice.PAYLOAD_TUPLE_TYPE,
                new FractionalSelectivity(1.0));

        AppLoop loop = new AppLoop(new ArrayList<>(List.of(SENSOR_TUPLE, MODULE_EDGE, MODULE_CENTER)));
        app.setLoops(new ArrayList<>(List.of(loop)));

        return app;
    }

    /** One Poisson data source per edge, seeded deterministically per (scenario, edge). */
    public static Sensor addSensor(SimParams p, int edgeIndex, EdgeServerDevice dev, int userId) {
        double meanInterArrival = 1.0 / p.rateOfEdge(edgeIndex);
        long sensorSeed = p.seed * 1_000_003L + edgeIndex * 131L + 17L;
        ExponentialDistribution dist = ExponentialDistribution.seeded(meanInterArrival, sensorSeed);
        Sensor sensor = new Sensor("sensor-" + edgeIndex, SENSOR_TUPLE, userId, APP_ID, dist);
        sensor.setGatewayDeviceId(dev.getId());
        sensor.setLatency(p.sensorLatency);
        return sensor;
    }
}
