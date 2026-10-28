package backfill.devices;

import org.fog.entities.FogDevice;
import org.fog.entities.Tuple;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.Storage;
import org.fog.entities.FogDeviceCharacteristics;

import backfill.metrics.UnitRegistry;

/**
 * The single receiving center. Identical to a stock FogDevice except that the arrival of a data
 * unit (a payload tuple) is instrumented: this exact instant defines the "delivered" time for
 * every measurement (freshness delay of new data, backfill delay, center throughput).
 */
public class CentralReceiverDevice extends FogDevice {

    public static final String PAYLOAD_TUPLE_TYPE = "PAYLOAD";

    public CentralReceiverDevice(String name,
                                 FogDeviceCharacteristics characteristics,
                                 VmAllocationPolicy vmAllocationPolicy,
                                 java.util.List<Storage> storageList,
                                 double schedulingInterval,
                                 double uplinkBandwidth, double downlinkBandwidth,
                                 double uplinkLatency, double ratePerMips) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval,
                uplinkBandwidth, downlinkBandwidth, uplinkLatency, ratePerMips);
    }

    @Override
    protected void processTupleArrival(SimEvent ev) {
        if (ev.getData() instanceof Tuple t
                && PAYLOAD_TUPLE_TYPE.equals(t.getTupleType())) {
            // Instrument BEFORE the stock forwarding/execution: "delivered at the center" moment.
            UnitRegistry.onArrived(t.getCloudletId());
        }
        super.processTupleArrival(ev);
    }
}
