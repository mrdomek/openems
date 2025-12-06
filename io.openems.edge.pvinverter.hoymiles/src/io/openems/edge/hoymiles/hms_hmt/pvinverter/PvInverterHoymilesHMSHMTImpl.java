package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Optional;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component( //
        name = "PV-Inverter.Hoymiles.HMS-HMT", //
        immediate = true, //
        configurationPolicy = REQUIRE, //
        property = { //
                "type=PRODUCTION" //
        })
@EventTopics({ //
        TOPIC_CYCLE_EXECUTE_WRITE //
})
public class PvInverterHoymilesHMSHMTImpl extends AbstractOpenemsModbusComponent //
        implements PvInverterHoymilesHMSHMT, ManagedSymmetricPvInverter, ElectricityMeter, //
        ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

    /*
     * Configuration as provided by OSGi / Felix WebConsole.
     */
    private Config config;

    /*
     * Own logger for this component.
     */
    private final Logger logger = LoggerFactory.getLogger(PvInverterHoymilesHMSHMTImpl.class);

    public PvInverterHoymilesHMSHMTImpl() {
        super(//
                OpenemsComponent.ChannelId.values(), //
                ModbusComponent.ChannelId.values(), //
                ElectricityMeter.ChannelId.values(), //
                ManagedSymmetricPvInverter.ChannelId.values(), //
                PvInverterHoymilesHMSHMT.ChannelId.values() //
        );
    }

    @Override
    @Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
    protected void setModbus(BridgeModbus modbus) {
        super.setModbus(modbus);
    }

    @Reference
    private ConfigurationAdmin cm;

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsException {
        this.config = config;

        /*
         * Typischer Fallback:
         * - Wenn id leer -> Alias oder "pvInverter0"
         * - Wenn Alias leer -> id
         * - Wenn modbus_id leer -> "modbus0"
         * - Wenn Unit-ID <= 0 -> 201
         */
        String id = config.id();
        String alias = config.alias();
        String modbusId = config.modbus_id();
        int unitId = config.modbusUnitId();

        if (id == null || id.isBlank()) {
            if (alias != null && !alias.isBlank()) {
                id = alias;
            } else {
                id = "pvInverter0";
            }
        }

        if (alias == null || alias.isBlank()) {
            alias = id;
        }

        if (modbusId == null || modbusId.isBlank()) {
            modbusId = "modbus0";
        }

        if (unitId <= 0) {
            unitId = 201;
        }

        super.activate(context, //
                id, //
                alias, //
                config.enabled(), //
                unitId, //
                this.cm, //
                "Modbus", //
                modbusId);

        // Konfigurierte Phase ins Channel-Model schreiben
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.CONFIGURED_PHASE) //
                .setNextValue(config.phase().name());
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    public void handleEvent(Event event) {
        // nur auf Zyklus-Write reagieren
        if (!TOPIC_CYCLE_EXECUTE_WRITE.equals(event.getTopic())) {
            return;
        }

        // Wert von MI1_TOTAL_PRODUCTION_WH holen und ins Log schreiben
        Optional<?> valueOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH) //
                .value() //
                .asOptional();

        if (!valueOpt.isPresent()) {
            return;
        }

        Object value = valueOpt.get();
        if (!(value instanceof Number)) {
            return;
        }

        long wh = ((Number) value).longValue();

        String idForLog = (this.config != null && this.config.id() != null && !this.config.id().isBlank())
                ? this.config.id()
                : "pvInverter";

        this.logger.info("[{}] Hoymiles MI1_TOTAL_PRODUCTION_WH = {} Wh", idForLog, wh);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Modbus / Meter
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        return new ModbusProtocol(this,

                /*
                 * Realtime Microinverter 1 – laut Hoymiles-Doku
                 *
                 * Basisadresse: 0x38E0
                 */
                new FC4ReadInputRegistersTask(0x38E0, Priority.HIGH,

                        // Seriennummer MI1 – Länge (4 WORDs) ggf. an Doku anpassen
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL,
                                new StringWordElement(0x38E0, 4)),

                        /*
                         * Total Production – 0x38E1
                         * Doku: 0.1 kWh/bit → 1 kWh = 10
                         * Skalierung auf Wh kann später über Converter / Controller erfolgen.
                         */
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH,
                                new SignedWordElement(0x38E1)),

                        /*
                         * Today Production – Beispieladresse 0x38E4 (nach Doku anpassen)
                         */
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TODAY_PRODUCTION_WH,
                                new SignedWordElement(0x38E4)),

                        /*
                         * Active Power – 0x38E7, 0.1 W/bit
                         *
                         * Mappen:
                         *  - auf Meter.ACTIVE_POWER
                         *  - auf MI1_ACTIVE_POWER_W
                         */
                        this.m(ElectricityMeter.ChannelId.ACTIVE_POWER,
                                new SignedWordElement(0x38E7)),

                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W,
                                new SignedWordElement(0x38E7))

                ));
    }

    @Override
    public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
        return new ModbusSlaveTable(//
                OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
                ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
                ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
    }

    @Override
    public MeterType getMeterType() {
        /*
         * If configured as "production meter", the inverter power will be taken
         * into account for production sums. Otherwise it is only informational.
         */
        return this.config != null && this.config.useAsProductionMeter()
                ? MeterType.PRODUCTION
                : MeterType.CONSUMPTION_NOT_METERED;
    }
}
