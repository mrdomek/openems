package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Map;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;

import com.google.common.collect.ImmutableMap;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.sunspec.DefaultSunSpecModel;
import io.openems.edge.bridge.modbus.sunspec.SunSpecModel;
import io.openems.edge.bridge.modbus.sunspec.pvinverter.AbstractSunSpecPvInverter;
import io.openems.edge.bridge.modbus.sunspec.pvinverter.SunSpecPvInverter;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
        name = "PV-Inverter.Hoymiles.HMS-HMT", //
        immediate = true, //
        configurationPolicy = REQUIRE, //
        property = { //
                "type=PRODUCTION" //
        })
@EventTopics({ //
        TOPIC_CYCLE_EXECUTE_WRITE })
public class PvInverterHoymilesHMSHMTImpl extends AbstractSunSpecPvInverter
        implements PvInverterHoymilesHMSHMT, SunSpecPvInverter, ManagedSymmetricPvInverter, ElectricityMeter,
        ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

    /*
     * Active SunSpec models that shall be read from the inverter.
     *
     * According to the SunSpec certification for Hoymiles microinverters the
     * following models are implemented: 1, 101, 103, 111, 113, 123.
     *
     *  - 1   : Common model
     *  - 101 : Single-phase inverter (int)
     *  - 103 : Three-phase inverter (int)
     *  - 111 : Single-phase inverter (float)
     *  - 113 : Three-phase inverter (float)
     *  - 123 : Immediate controls (active power limit etc.)
     *
     * We register all of them here. The AbstractSunSpecPvInverter base class will
     * automatically discover which models are actually present on the device and
     * only create tasks for those.
     */
    private static final Map<SunSpecModel, Priority> ACTIVE_MODELS = ImmutableMap.<SunSpecModel, Priority>builder()
            .put(DefaultSunSpecModel.S_1, Priority.LOW)   // Common
            .put(DefaultSunSpecModel.S_101, Priority.HIGH) // Single-phase (int)
            .put(DefaultSunSpecModel.S_103, Priority.HIGH) // Three-phase (int)
            .put(DefaultSunSpecModel.S_111, Priority.HIGH) // Single-phase (float)
            .put(DefaultSunSpecModel.S_113, Priority.HIGH) // Three-phase (float)
            .put(DefaultSunSpecModel.S_123, Priority.HIGH) // Immediate controls
            .build();

    /*
     * SunSpec "block" index to start reading from. This value is forwarded to the
     * AbstractSunSpecPvInverter base class, which takes care of scanning the
     * SunSpec header and model chain.
     */
    private static final int READ_FROM_MODBUS_BLOCK = 1;

    @Reference
    private ConfigurationAdmin cm;

    @Override
    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    public PvInverterHoymilesHMSHMTImpl() {
        super(//
                ACTIVE_MODELS, //
                OpenemsComponent.ChannelId.values(), //
                ModbusComponent.ChannelId.values(), //
                ElectricityMeter.ChannelId.values(), //
                ManagedSymmetricPvInverter.ChannelId.values(), //
                SunSpecPvInverter.ChannelId.values(), //
                PvInverterHoymilesHMSHMT.ChannelId.values() //
        );
    }

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsException {
        /*
         * Basic activation and channel setup are handled by the
         * AbstractSunSpecPvInverter base class.
         *
         * Additional Hoymiles-specific logic (e.g. handling of useAsProductionMeter,
         * SetOutputPower based on enableSetOutputPower, per-input channels using
         * proprietary registers) will be added in later steps.
         */
        if (super.activate(context, //
                config.id(), //
                config.alias(), //
                config.enabled(), //
                config.readOnly(), //
                config.modbusUnitId(), //
                this.cm, //
                "Modbus", //
                config.modbus_id(), //
                READ_FROM_MODBUS_BLOCK, //
                config.phase())) {
            return;
        }
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    public void handleEvent(Event event) {
        super.handleEvent(event);
    }

    @Override
    public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
        return new ModbusSlaveTable(//
                OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
                ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
                ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
    }
}
