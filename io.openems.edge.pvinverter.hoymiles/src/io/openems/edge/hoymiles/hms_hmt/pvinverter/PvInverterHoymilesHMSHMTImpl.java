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
import io.openems.edge.bridge.modbus.sunspec.pvinverter.SunSpecPvInverter;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.SymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component(//
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
         * Typisches OpenEMS-Muster für id/alias/modbusId/unitId.
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
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.CONFIGURED_PHASE.id())
                .setNextValue(config.phase().name());
    }

    @Override
    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    /**
     * Zyklisches Logging von TOTAL_PRODUCTION_WH, damit du den Wert im Log siehst.
     */
    @Override
    public void handleEvent(Event event) {
        // Nur auf CYCLE_EXECUTE_WRITE reagieren
        if (!TOPIC_CYCLE_EXECUTE_WRITE.equals(event.getTopic())) {
            return;
        }

        // Standard-PV-Channel aus SymmetricPvInverter
        Optional<?> totalWhOpt = this.channel(SunSpecPvInverter.ChannelId.TOTAL_PRODUCTION_WH) //
                .value() //
                .asOptional();

        if (!totalWhOpt.isPresent()) {
            return;
        }

        Object totalWhObj = totalWhOpt.get();
        if (!(totalWhObj instanceof Number)) {
            return;
        }

        long totalWh = ((Number) totalWhObj).longValue();

        String idForLog = (this.config != null && this.config.id() != null && !this.config.id().isBlank())
                ? this.config.id()
                : "pvInverter";

        this.logger.info("[{}] PV TOTAL_PRODUCTION_WH = {} Wh", idForLog, totalWh);

        // Optional zusätzlich der MI1-spezifische Rohwert
        Optional<?> mi1TotalWhOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH) //
                .value() //
                .asOptional();

        mi1TotalWhOpt.ifPresent(v -> {
            if (v instanceof Number) {
                long mi1Wh = ((Number) v).longValue();
                this.logger.info("[{}] Hoymiles MI1_TOTAL_PRODUCTION_WH = {} Wh", idForLog, mi1Wh);
            }
        });
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Modbus / Meter
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        /*
         * Realtime Microinverter 1 – Kapitel 4.4.4 der Hoymiles-Doku.
         *
         * Basisadresse: 0x38E0
         * Wir lesen einen zusammenhängenden Block, auch wenn wir nicht alle Register
         * sofort nutzen. Wichtig: erste Elementadresse == Task-Startadresse, sonst
         * wirft AbstractTask die Exception "StartAddress for Modbus Element wrong".
         */
        return new ModbusProtocol(this,

                new FC4ReadInputRegistersTask(0x38E0,
                        // Seriennummer MI1 – Länge aus Doku (hier Beispiel: 4 WORDs)
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL,
                                new StringWordElement(0x38E0, 4)),

                        /*
                         * Total Production MI1 – 0x38E1
                         * Rohwert laut Doku: 0.1 kWh/bit.
                         * Umrechnung auf Wh erfolgt später (z.B. im Controller oder mit
                         * ElementToChannelConverter, wenn wir das fein machen wollen).
                         */
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH,
                                new SignedWordElement(0x38E1)),

                        /*
                         * Today Production MI1 – Beispieladresse 0x38E4 (genaue Adresse in der Doku prüfen).
                         */
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TODAY_PRODUCTION_WH,
                                new SignedWordElement(0x38E4)),

                        /*
                         * Active Power Phase A – 0x38E7, 0.1 W/bit.
                         * Wir mappen sie auf den Standard-PV-Channel ACTIVE_POWER und zusätzlich
                         * auf einen Hoymiles-spezifischen Channel.
                         */

                        // Standard-PV-Channel
                        this.m(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER,
                                new SignedWordElement(0x38E7)),

                        // Hoymiles-spezifischer Channel
                        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W,
                                new SignedWordElement(0x38E7));

    

        /*
         * Falls du zusätzlich noch den DTU-Meter-Block (0x3100...) brauchst, kannst du
         * hier einen zweiten Task anhängen, z.B.:
         *
         * , new FC4ReadInputRegistersTask(0x3100,
         *      this.m(...))
         */
        ;
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
