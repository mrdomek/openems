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
        // React only on cycle write events
        if (!TOPIC_CYCLE_EXECUTE_WRITE.equals(event.getTopic())) {
            return;
        }

        if (this.config == null) {
            return;
        }

        /*
         * Get total AC active power from microinverter block.
         * This is already scaled to W via Modbus mapping.
         */
        Optional<?> pOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W)
                .value()
                .asOptional();

        if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
            return;
        }

        int pTotal = ((Number) pOpt.get()).intValue();

        // NEW: derive health + alarm flag from status & alarm registers
        this.updateHealthFromStatusAndAlarms();

        /*
         * Determine if the selected device model is three-phase (HMT)
         * or single-phase (HMS).
         */
        boolean threePhaseDevice = false;
        try {
            threePhaseDevice = this.config.deviceModel() != null
                    && this.config.deviceModel().isThreePhase();
        } catch (Exception e) {
            // defensive fallback: treat as single-phase on L1
            threePhaseDevice = false;
        }

        // Split total power to phases according to device type + configured phase
        int[] phases = splitPowerByPhase(threePhaseDevice, this.config.phase(), pTotal);
        int pL1 = phases[0];
        int pL2 = phases[1];
        int pL3 = phases[2];

        // Set phase powers
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L1).setNextValue(pL1);
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L2).setNextValue(pL2);
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L3).setNextValue(pL3);

        // Total meter power = sum of phases
        this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER).setNextValue(pL1 + pL2 + pL3);

        /*
         * DC utilization per PV input:
         * utilization[%] = (PVx_POWER_W / configured_module_peak_W) * 100
         * If peak = 0 or missing power -> channel is set to null.
         */
        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_UTILIZATION_PERCENT,
                this.config.pv1ModulePeakPowerW());

        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_UTILIZATION_PERCENT,
                this.config.pv2ModulePeakPowerW());

        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_UTILIZATION_PERCENT,
                this.config.pv3ModulePeakPowerW());

        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_UTILIZATION_PERCENT,
                this.config.pv4ModulePeakPowerW());

        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_UTILIZATION_PERCENT,
                this.config.pv5ModulePeakPowerW());

        updatePvUtilization(
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_UTILIZATION_PERCENT,
                this.config.pv6ModulePeakPowerW());
    }

    /**
     * Split total active power to phase powers according to device type and
     * configured phase.
     *
     * threePhaseDevice == false (HMS, single-phase):
     *   - L1/L2/L3 -> all power on the selected phase.
     *
     * threePhaseDevice == true (HMT, three-phase):
     *   - power is evenly distributed to all three phases;
     *     rounding differences are applied to L3.
     */
    private static int[] splitPowerByPhase(boolean threePhaseDevice,
            PvInverterHoymilesHMSHMT.Phase phase, int totalPower) {

        int pL1 = 0;
        int pL2 = 0;
        int pL3 = 0;

        if (threePhaseDevice) {
            // Three-phase HMT: always distribute across all three phases
            int perPhase = totalPower / 3;
            pL1 = perPhase;
            pL2 = perPhase;
            pL3 = totalPower - pL1 - pL2; // carry rounding to L3
            return new int[] { pL1, pL2, pL3 };
        }

        // Single-phase HMS: respect configured phase
        if (phase == null) {
            phase = PvInverterHoymilesHMSHMT.Phase.L1;
        }

        switch (phase) {
        case L1:
            pL1 = totalPower;
            break;

        case L2:
            pL2 = totalPower;
            break;

        case L3:
            pL3 = totalPower;
            break;

        default:
            // Fallback: everything on L1
            pL1 = totalPower;
            break;
        }

        return new int[] { pL1, pL2, pL3 };
    }

    
    /**
     * Calculate DC utilization for a PV input:
     * utilization[%] = (PV_power_W / module_peak_W) * 100.
     *
     * If no peak is configured (<= 0) or no valid power is available,
     * the utilization channel is set to null.
     */
    private void updatePvUtilization(PvInverterHoymilesHMSHMT.ChannelId powerChannelId,
            PvInverterHoymilesHMSHMT.ChannelId utilizationChannelId, int modulePeakPowerW) {

        // No module configured -> no utilization value
        if (modulePeakPowerW <= 0) {
            this.channel(utilizationChannelId).setNextValue(null);
            return;
        }

        Optional<?> pOpt = this.channel(powerChannelId).value().asOptional();
        if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
            this.channel(utilizationChannelId).setNextValue(null);
            return;
        }

        double powerW = ((Number) pOpt.get()).doubleValue();

        // Protect against negative values; clamp at 0
        if (powerW < 0) {
            powerW = 0;
        }

        double percent = (powerW / (double) modulePeakPowerW) * 100.0;
        int percentRounded = (int) Math.round(percent);

        this.channel(utilizationChannelId).setNextValue(percentRounded);
    }

    /**
     * Derive a simple health state and aggregated alarm flag from the
     * status and alarm registers.
     *
     * - NO_DATA: no valid status/alarm values available
     * - FAULT  : at least one alarm register != 0
     * - WARNING: no alarm, but status code != 0
     * - OK     : status == 0 and all alarm registers == 0
     */
    private void updateHealthFromStatusAndAlarms() {
        boolean hasData = false;
        boolean hasAlarm = false;
        Integer status = null;

        // Read status code
        Optional<?> statusOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE)
                .value()
                .asOptional();
        if (statusOpt.isPresent() && statusOpt.get() instanceof Number) {
            status = ((Number) statusOpt.get()).intValue();
            hasData = true;
        }

        // Read alarm registers 1..6
        PvInverterHoymilesHMSHMT.ChannelId[] alarmIds = new PvInverterHoymilesHMSHMT.ChannelId[] {
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE,
                PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE
        };

        for (PvInverterHoymilesHMSHMT.ChannelId alarmId : alarmIds) {
            Optional<?> alarmOpt = this.channel(alarmId).value().asOptional();
            if (alarmOpt.isPresent() && alarmOpt.get() instanceof Number) {
                int alarmValue = ((Number) alarmOpt.get()).intValue();
                hasData = true;
                if (alarmValue != 0) {
                    hasAlarm = true;
                    // kein break: wir lesen alle, um Cache aktuell zu halten
                }
            }
        }

        String health;
        if (!hasData) {
            health = "NO_DATA";
        } else if (hasAlarm) {
            health = "FAULT";
        } else if (status != null && status != 0) {
            // Kann später verfeinert werden, wenn Status-Code-Mapping bekannt ist.
            health = "WARNING";
        } else {
            health = "OK";
        }

        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HAS_ALARM).setNextValue(hasAlarm);
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HEALTH_STATE).setNextValue(health);
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
