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
     * This list is still based on the original SMA implementation and will be
     * adapted to the exact Hoymiles SunSpec model set in a later step.
     */
    private static final Map<SunSpecModel, Priority> ACTIVE_MODELS = ImmutableMap.<SunSpecModel, Priority>builder()
            // common + basic inverter models
            .put(DefaultSunSpecModel.S_1, Priority.LOW)
            .put(DefaultSunSpecModel.S_101, Priority.LOW)
            // reactive / power factor model
            .put(DefaultSunSpecModel.S_103, Priority.HIGH)
            // basic nameplate / settings
            .put(DefaultSunSpecModel.S_120, Priority.LOW)
            .put(DefaultSunSpecModel.S_121, Priority.LOW)
            .put(DefaultSunSpecModel.S_122, Priority.LOW)
            // immediate controls (active power limitation etc.)
            .put(DefaultSunSpecModel.S_123, Priority.LOW)
            // alternative model set (since 2023 in original SMA implementation)
            .put(DefaultSunSpecModel.S_701, Priority.HIGH)
            .put(DefaultSunSpecModel.S_702, Priority.LOW)
            .build();

    /*
     * SunSpec "block" to start reading from. This value is forwarded to the
     * AbstractSunSpecPvInverter base class.
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
         * AbstractSunSpecPvInverter base class. Additional Hoymiles-specific logic
         * (e.g. SetOutputPower handling, per-input channels) will be added in later
         * steps.
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
