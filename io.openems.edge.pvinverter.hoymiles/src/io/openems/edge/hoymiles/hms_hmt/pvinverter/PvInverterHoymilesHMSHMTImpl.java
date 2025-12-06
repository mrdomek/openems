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
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
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

    /**
     * Size of one microinverter register block.
     *
     * According to Hoymiles Modbus documentation:
     * MI1 base: 0x38E0
     * MI2 base: 0x3940
     * → block size: 0x60 (96 words) per microinverter.
     */
    private static final int MI_REGISTER_BLOCK_SIZE = 0x60; // 96 registers per inverter block

    // Selected microinverter number (1..99); used to shift the register block.
    private int microinverterNumber = 1;

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

        // Clamp microinverter number to [1..99]
        this.microinverterNumber = Math.max(1, Math.min(config.microinverterNumber(), 99));
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

        if (this.config == null) {
            return;
        }

        /*
         * 1) Total-Wh ins Log schreiben (nur wenn verfügbar)
         *    Hinweis: Wenn du die Totals gar nicht nutzen willst, kannst du
         *    diesen Block später noch entfernen.
         */
        Optional<?> totalOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH) //
                .value() //
                .asOptional();

        if (totalOpt.isPresent() && totalOpt.get() instanceof Number) {
            long wh = ((Number) totalOpt.get()).longValue();

            String idForLog = (this.config.id() != null && !this.config.id().isBlank())
                    ? this.config.id()
                    : "pvInverter";

            this.logger.info("[{}] Hoymiles MI1_TOTAL_PRODUCTION_WH = {} Wh", idForLog, wh);
        }

        /*
         * 2) Phasenumschaltung:
         *    MI1_ACTIVE_POWER_W → ACTIVE_POWER_L1/L2/L3 + ACTIVE_POWER
         */
        Optional<?> pOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W) //
                .value() //
                .asOptional();

        if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
            return;
        }

        int pTotal = ((Number) pOpt.get()).intValue();

        int pL1 = 0;
        int pL2 = 0;
        int pL3 = 0;

        switch (this.config.phase()) {
        case L1:
            pL1 = pTotal;
            break;

        case L2:
            pL2 = pTotal;
            break;

        case L3:
            pL3 = pTotal;
            break;

        case ALL:
            // gleichmäßig auf alle drei Phasen verteilen; Rundungsfehler auf L3 auffangen
            int perPhase = pTotal / 3;
            pL1 = perPhase;
            pL2 = perPhase;
            pL3 = pTotal - pL1 - pL2;
            break;

        default:
            // Fallback: alles auf L1
            pL1 = pTotal;
            break;
        }

        // Phasenleistungen setzen
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L1).setNextValue(pL1);
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L2).setNextValue(pL2);
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L3).setNextValue(pL3);

        // Gesamtleistung = Summe der Phasen
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER).setNextValue(pL1 + pL2 + pL3);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Modbus / Meter
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        /*
         * Hoymiles "Realtime microinverter data" block:
         *
         * MI1 base address: 0x38E0
         * MI2 base address: 0x3940
         * -> Block size per MI: 0x60 (96 words)
         *
         * Wir wählen über microinverterNumber (1..99) den Block und lesen:
         * - Seriennummer (3 Words) separat
         * - Realtime-Daten 0x38E7..0x390D in einem Block
         */

        final int base = 0x38E0 + (this.microinverterNumber - 1) * MI_REGISTER_BLOCK_SIZE;

        /*
         * Modbus elements
         */

        // Serial number: 3 words at base (0x38E0..0x38E2)
        final StringWordElement serial = new StringWordElement(base, 3);

        // Realtime block starting at 0x38E7 (Active power)
        final SignedWordElement activePower = new SignedWordElement(base + 0x07);   // 0x38E7
        final SignedWordElement reactivePower = new SignedWordElement(base + 0x08); // 0x38E8
        final SignedWordElement powerFactor = new SignedWordElement(base + 0x09);   // 0x38E9

        final SignedWordElement vphA = new SignedWordElement(base + 0x0A);          // 0x38EA
        final SignedWordElement vphB = new SignedWordElement(base + 0x0B);          // 0x38EB
        final SignedWordElement vphC = new SignedWordElement(base + 0x0C);          // 0x38EC

        final SignedWordElement uab = new SignedWordElement(base + 0x0D);           // 0x38ED
        final SignedWordElement ubc = new SignedWordElement(base + 0x0E);           // 0x38EE
        final SignedWordElement uca = new SignedWordElement(base + 0x0F);           // 0x38EF

        final SignedWordElement iphA = new SignedWordElement(base + 0x10);          // 0x38F0
        final SignedWordElement iphB = new SignedWordElement(base + 0x11);          // 0x38F1
        final SignedWordElement iphC = new SignedWordElement(base + 0x12);          // 0x38F2

        final SignedWordElement frequency = new SignedWordElement(base + 0x13);     // 0x38F3
        final SignedWordElement temperature = new SignedWordElement(base + 0x14);   // 0x38F4

        final SignedWordElement pv1Voltage = new SignedWordElement(base + 0x15);    // 0x38F5
        final SignedWordElement pv2Voltage = new SignedWordElement(base + 0x16);    // 0x38F6
        final SignedWordElement pv3Voltage = new SignedWordElement(base + 0x17);    // 0x38F7
        final SignedWordElement pv4Voltage = new SignedWordElement(base + 0x18);    // 0x38F8
        final SignedWordElement pv5Voltage = new SignedWordElement(base + 0x19);    // 0x38F9
        final SignedWordElement pv6Voltage = new SignedWordElement(base + 0x1A);    // 0x38FA

        final SignedWordElement pv1Current = new SignedWordElement(base + 0x1B);    // 0x38FB
        final SignedWordElement pv2Current = new SignedWordElement(base + 0x1C);    // 0x38FC
        final SignedWordElement pv3Current = new SignedWordElement(base + 0x1D);    // 0x38FD
        final SignedWordElement pv4Current = new SignedWordElement(base + 0x1E);    // 0x38FE
        final SignedWordElement pv5Current = new SignedWordElement(base + 0x1F);    // 0x38FF
        final SignedWordElement pv6Current = new SignedWordElement(base + 0x20);    // 0x3900

        final SignedWordElement pv1Power = new SignedWordElement(base + 0x21);      // 0x3901
        final SignedWordElement pv2Power = new SignedWordElement(base + 0x22);      // 0x3902
        final SignedWordElement pv3Power = new SignedWordElement(base + 0x23);      // 0x3903
        final SignedWordElement pv4Power = new SignedWordElement(base + 0x24);      // 0x3904
        final SignedWordElement pv5Power = new SignedWordElement(base + 0x25);      // 0x3905
        final SignedWordElement pv6Power = new SignedWordElement(base + 0x26);      // 0x3906

        final SignedWordElement status = new SignedWordElement(base + 0x27);        // 0x3907

        final SignedWordElement alarm1 = new SignedWordElement(base + 0x28);        // 0x3908
        final SignedWordElement alarm2 = new SignedWordElement(base + 0x29);        // 0x3909
        final SignedWordElement alarm3 = new SignedWordElement(base + 0x2A);        // 0x390A
        final SignedWordElement alarm4 = new SignedWordElement(base + 0x2B);        // 0x390B
        final SignedWordElement alarm5 = new SignedWordElement(base + 0x2C);        // 0x390C
        final SignedWordElement alarm6 = new SignedWordElement(base + 0x2D);        // 0x390D

        /*
         * Channel mapping
         */

        // Serial
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL, serial);

     // Active power: 0.1 W/bit -> W
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W, activePower,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Reactive power: 0.1 var/bit -> var
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_REACTIVE_POWER_VAR, reactivePower,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Power factor: 0.01/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_POWER_FACTOR, powerFactor,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);

        // Voltages 0.1 V/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_V, vphA,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_V, vphB,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_V, vphC,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_L2_V, uab,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_L3_V, ubc,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_L1_V, uca,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Currents 0.01 A/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L1_A, iphA,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L2_A, iphB,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L3_A, iphC,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);

        // Frequency 0.01 Hz/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_GRID_FREQUENCY_HZ, frequency,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);

        // Temperature 0.1 °C/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TEMPERATURE_C, temperature,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // PV voltages 0.1 V/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_VOLTAGE_V, pv1Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_VOLTAGE_V, pv2Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_VOLTAGE_V, pv3Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_VOLTAGE_V, pv4Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_VOLTAGE_V, pv5Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_VOLTAGE_V, pv6Voltage,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // PV currents 0.01 A/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_CURRENT_A, pv1Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_CURRENT_A, pv2Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_CURRENT_A, pv3Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_CURRENT_A, pv4Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_CURRENT_A, pv5Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_CURRENT_A, pv6Current,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_2);

        // PV power 0.1 W/bit
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W, pv1Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W, pv2Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W, pv3Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W, pv4Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W, pv5Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W, pv6Power,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Status + alarms
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE, status);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE, alarm1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE, alarm2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE, alarm3);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE, alarm4);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE, alarm5);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE, alarm6);

        /*
         * Tasks
         */

        return new ModbusProtocol(this,

                // Serial number: small, low priority
                new FC4ReadInputRegistersTask(base, Priority.LOW, serial),

                // Realtime block 0x38E7..0x390D in einem Task
                new FC4ReadInputRegistersTask(base + 0x07, Priority.HIGH,
                        activePower, reactivePower, powerFactor,
                        vphA, vphB, vphC,
                        uab, ubc, uca,
                        iphA, iphB, iphC,
                        frequency, temperature,
                        pv1Voltage, pv2Voltage, pv3Voltage, pv4Voltage, pv5Voltage, pv6Voltage,
                        pv1Current, pv2Current, pv3Current, pv4Current, pv5Current, pv6Current,
                        pv1Power, pv2Power, pv3Power, pv4Power, pv5Power, pv6Power,
                        status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6));
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
