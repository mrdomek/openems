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
            MI1_WARNING( //
                    Doc.of(Level.WARNING) //
                            .text("Microinverter WARNING (derived): non-fatal condition, e.g. derating or missing PV inputs. "
                                    + "May be present together with MI1_HAS_ALARM depending on alarm-bit classification.")),

            MI1_FAULT( //
                    Doc.of(Level.FAULT) //
                            .text("Microinverter FAULT (derived): fatal condition that stops operation. "
                                    + "Derived from status and alarm bits; see MI1_ALARM_SUMMARY for details.")),
            
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
                            .text("Last active power limit actually sent to the microinverter in percent [2..100].")),

            // --- DTU TOPOLOGY ---
            DTU_REGISTERED_MICROINVERTERS( //
                    Doc.of(OpenemsType.INTEGER) //
                            .unit(Unit.NONE) //
                            .text("DTU: number of registered microinverters (register 0x3004, FC04).")),

            
            /*
             * DTU connected microinverter serials.
             * Value is the concatenation of 3x uint16 words from DTU serial list (FC03 @ 0x502B):
             * word0||word1||word2 as fixed width hex blocks, e.g. \"1164ABCD1234\" (12 hex chars).
             */
            DTU__CONNECTED_MI1_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI1 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI2_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI2 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI3_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI3 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI4_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI4 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI5_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI5 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI6_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI6 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI7_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI7 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI8_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI8 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI9_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI9 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI10_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI10 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI11_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI11 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI12_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI12 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI13_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI13 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI14_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI14 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI15_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI15 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI16_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI16 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI17_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI17 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI18_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI18 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI19_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI19 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI20_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI20 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI21_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI21 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI22_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI22 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI23_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI23 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI24_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI24 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI25_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI25 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI26_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI26 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI27_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI27 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI28_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI28 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI29_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI29 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI30_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI30 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI31_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI31 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI32_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI32 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI33_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI33 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI34_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI34 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI35_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI35 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI36_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI36 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI37_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI37 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI38_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI38 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI39_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI39 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI40_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI40 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI41_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI41 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI42_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI42 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI43_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI43 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI44_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI44 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI45_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI45 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI46_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI46 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI47_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI47 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI48_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI48 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI49_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI49 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI50_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI50 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI51_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI51 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI52_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI52 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI53_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI53 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI54_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI54 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI55_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI55 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI56_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI56 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI57_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI57 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI58_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI58 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI59_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI59 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI60_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI60 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI61_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI61 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI62_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI62 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI63_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI63 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI64_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI64 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI65_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI65 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI66_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI66 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI67_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI67 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI68_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI68 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI69_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI69 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI70_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI70 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI71_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI71 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI72_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI72 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI73_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI73 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI74_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI74 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI75_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI75 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI76_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI76 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI77_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI77 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI78_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI78 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI79_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI79 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI80_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI80 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI81_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI81 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI82_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI82 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI83_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI83 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI84_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI84 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI85_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI85 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI86_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI86 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI87_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI87 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI88_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI88 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI89_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI89 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI90_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI90 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI91_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI91 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI92_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI92 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI93_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI93 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI94_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI94 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI95_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI95 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI96_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI96 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI97_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI97 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI98_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI98 serial (word0..2 concatenated as hex).")),
            DTU__CONNECTED_MI99_SERIAL( //
                    Doc.of(OpenemsType.STRING) //
                            .unit(Unit.NONE) //
                            .text("DTU: connected MI99 serial (word0..2 concatenated as hex)."));

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
