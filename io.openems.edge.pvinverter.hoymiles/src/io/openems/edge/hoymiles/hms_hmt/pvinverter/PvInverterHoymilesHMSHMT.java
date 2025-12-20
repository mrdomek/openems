package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.Level;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

public interface PvInverterHoymilesHMSHMT extends ManagedSymmetricPvInverter, ElectricityMeter,
        ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

    /**
     * AC-Phase, an der der HMS/HMT hängt.
     *
     * L1/L2/L3 = einphasig an der jeweiligen Phase.
     * (Für HMT-Modelle wird die reale 3-Phasen-Verteilung später modellabhängig
     *  gehandhabt; dieses Enum dient nur noch der HMS-Konfiguration.)
     */
    public static enum Phase {
        L1, L2, L3;
    }


        /**
         * Zusätzliche Hoymiles-spezifische Channels.
         */
        public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

            MI1_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .text("Serial number of Microinverter 1 (register 0x38E0 ff.)")),

            MI1_SERIAL_WORD_0( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Serial number word 0 (uint16) of Microinverter 1 (register 0x38E0).")),

            MI1_SERIAL_WORD_1( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Serial number word 1 (uint16) of Microinverter 1 (register 0x38E1).")),

            MI1_SERIAL_WORD_2( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Serial number word 2 (uint16) of Microinverter 1 (register 0x38E2).")),
                       
            MI1_TOTAL_PRODUCTION_WH( //
                    Doc.of(OpenemsType.LONG) //
                            .unit(Unit.WATT_HOURS) //
                            .text("Total production of Microinverter 1 in Wh (from 0x38E1; 0.1 kWh/bit → *100 Wh)")),

            MI1_TODAY_PRODUCTION_WH( //
                    Doc.of(OpenemsType.LONG) //
                            .unit(Unit.WATT_HOURS) //
                            .text("Today production of Microinverter 1 in Wh (scale factor according to documentation)")),

            MI1_ACTIVE_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("AC active power of Microinverter 1 in W (0x38E7, 0.1 W/bit → scaled to W)")),

            CONFIGURED_PHASE( //
                    Doc.of(OpenemsType.STRING) //
                            .text("Configured AC phase (L1/L2/L3) where the Hoymiles inverter is connected (HMS only).")),

            MI1_REACTIVE_POWER_VAR( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.VOLT_AMPERE_REACTIVE) //
                            .text("Reactive power of selected Hoymiles microinverter in var (register 0x38E8, 0.1 var/bit).")),

            MI1_POWER_FACTOR( //
                    Doc.of(OpenemsType.DOUBLE) //
                            .unit(Unit.NONE) //
                            .text("Power factor of selected Hoymiles microinverter (register 0x38E9, 0.01/bit).")),

            // --- GRID VOLTAGES (Converted to Millivolt for precision) ---
            
            MI1_AC_VOLTAGE_L1_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Grid phase voltage VphA [mV] (0x38EA, 0.1 V/bit).")),

            MI1_AC_VOLTAGE_L2_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Grid phase voltage VphB [mV] (0x38EB, 0.1 V/bit).")),

            MI1_AC_VOLTAGE_L3_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Grid phase voltage VphC [mV] (0x38EC, 0.1 V/bit).")),

            MI1_AC_VOLTAGE_L1_L2_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Line-to-line voltage Uab [mV] (0x38ED, 0.1 V/bit).")),

            MI1_AC_VOLTAGE_L2_L3_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Line-to-line voltage Ubc [mV] (0x38EE, 0.1 V/bit).")),

            MI1_AC_VOLTAGE_L3_L1_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("Line-to-line voltage Uca [mV] (0x38EF, 0.1 V/bit).")),

            // --- GRID CURRENTS (Converted to Milliampere for precision) ---

            MI1_AC_CURRENT_L1_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("Phase current IphA [mA] (0x38F0, 0.01 A/bit).")),

            MI1_AC_CURRENT_L2_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("Phase current IphB [mA] (0x38F1, 0.01 A/bit).")),

            MI1_AC_CURRENT_L3_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("Phase current IphC [mA] (0x38F2, 0.01 A/bit).")),

            MI1_GRID_FREQUENCY_mHz( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIHERTZ) //
                            .text("Grid frequency [mHz] (0x38F3, 0.01 Hz/bit).")),

            MI1_TEMPERATURE_C( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.DEGREE_CELSIUS) //
                            .text("Microinverter temperature [°C] (0x38F4, 0.1 °C/bit).")),

            // --- PV VOLTAGES (Converted to Millivolt) ---

            MI1_PV1_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV1 input voltage [mV] (0x38F5, 0.1 V/bit).")),

            MI1_PV2_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV2 input voltage [mV] (0x38F6, 0.1 V/bit).")),

            MI1_PV3_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV3 input voltage [mV] (0x38F7, 0.1 V/bit).")),

            MI1_PV4_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV4 input voltage [mV] (0x38F8, 0.1 V/bit).")),

            MI1_PV5_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV5 input voltage [mV] (0x38F9, 0.1 V/bit).")),

            MI1_PV6_VOLTAGE_mV( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIVOLT) //
                            .text("PV6 input voltage [mV] (0x38FA, 0.1 V/bit).")),

            // --- PV CURRENTS (Already Milliampere) ---

            MI1_PV1_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV1 input current [mA] (0x38FB, 0.01 A/bit).")),

            MI1_PV2_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV2 input current [mA] (0x38FC, 0.01 A/bit).")),

            MI1_PV3_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV3 input current [mA] (0x38FD, 0.01 A/bit).")),

            MI1_PV4_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV4 input current [mA] (0x38FE, 0.01 A/bit).")),

            MI1_PV5_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV5 input current [mA] (0x38FF, 0.01 A/bit).")),

            MI1_PV6_CURRENT_mA( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.MILLIAMPERE) //
                            .text("PV6 input current [mA] (0x3900, 0.01 A/bit).")),

            // --- PV POWER (Remaining Watt / Integer) ---

            MI1_PV1_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV1 input power [W] (0x3901, 0.1 W/bit).")),

            MI1_PV2_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV2 input power [W] (0x3902, 0.1 W/bit).")),

            MI1_PV3_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV3 input power [W] (0x3903, 0.1 W/bit).")),

            MI1_PV4_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV4 input power [W] (0x3904, 0.1 W/bit).")),

            MI1_PV5_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV5 input power [W] (0x3905, 0.1 W/bit).")),

            MI1_PV6_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("PV6 input power [W] (0x3906, 0.1 W/bit).")),

            // ---- UTILIZATION ---

            MI1_PV1_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV1 input in % of configured module peak power.")),

            MI1_PV2_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV2 input in % of configured module peak power.")),

            MI1_PV3_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV3 input in % of configured module peak power.")),

            MI1_PV4_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV4 input in % of configured module peak power.")),

            MI1_PV5_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV5 input in % of configured module peak power.")),

            MI1_PV6_UTILIZATION_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Relative DC loading of PV6 input in % of configured module peak power.")),

            // --- STATUS & ALARMS ---

            MI1_STATUS_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Microinverter status code (0x3907).")),

            MI1_ALARM1_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 1 code (0x3908).")),

            MI1_ALARM2_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 2 code (0x3909).")),

            MI1_ALARM3_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 3 code (0x390A).")),

            MI1_ALARM4_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 4 code (0x390B).")),

            MI1_ALARM5_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 5 code (0x390C).")),

            MI1_ALARM6_CODE( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("Alarm 6 code (0x390D).")),

            MI1_HAS_ALARM( //
                    Doc.of(OpenemsType.BOOLEAN) //
                            .unit(Unit.NONE) //
                            .text("True if any of the alarm registers (0x3908..0x390D) is non-zero.")),

            /*
             * OpenEMS StateChannels:
             * //mrdomek These must use Level.* so OpenEMS can propagate WARNING/FAULT into component STATE.
             */
            MI1_FAULT( //
                    Doc.of(Level.FAULT) //
                            .text("Microinverter is in FAULT state (any alarm register non-zero).")),

            MI1_WARNING( //
                    Doc.of(Level.WARNING) //
                            .text("Microinverter is in WARNING state (status != 0, but no alarms).")),

            
            MI1_HEALTH_STATE( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("Derived health state of the microinverter: OK/WARNING/FAULT/NO_DATA.")),
            
            MI1_INTERPRETED_STATUS( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("Coarse interpreted status of Microinverter 1 (e.g. PRODUCING / STANDBY / ERROR).")),
            
            MI1_ALARM_SUMMARY( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("Combined alarm/status bits for Microinverter 1, "
                                    + "derived from status and alarm codes 1–6.")),
            
            // --- LIMIT CONTROL ---
            
            MI1_LIMIT_ACTIVE_POWER_W( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.WATT) //
                            .text("Target active power limit in W for this microinverter port.")),

            MI1_LIMIT_ACTIVE_POWER_PERCENT( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.PERCENT) //
                            .text("Last active power limit actually sent to the microinverter in percent [2..100]."));
        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }
    }


    @Override
    default String debugLog() {
        // Kann später mit mehr Infos gefüllt werden
        return "";
    }
}
