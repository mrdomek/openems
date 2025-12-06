package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import org.osgi.service.event.EventHandler;

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
     * ALL      = symmetrischer 3-Phasen-Betrieb (Leistung wird auf L1–L3 verteilt).
     */
    public static enum Phase {
        L1, L2, L3, ALL;
    }

    /**
     * Zusätzliche Hoymiles-spezifische Channels.
     *
     * Die Standard-PV-/Meter-Channels kommen von:
     * - {@link ManagedSymmetricPvInverter.ChannelId}
     * - {@link ElectricityMeter.ChannelId}
     */
    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

        MI1_SERIAL( //
                Doc.of(OpenemsType.STRING) //
                        .text("Serial number of Microinverter 1 (register 0x38E0 ff.)")),

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
                        .text("Configured AC phase (L1/L2/L3/ALL) where the Hoymiles inverter is connected.")),

        MI1_REACTIVE_POWER_VAR( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT_AMPERE_REACTIVE) //
                        .text("Reactive power of selected Hoymiles microinverter in var (register 0x38E8, 0.1 var/bit).")),

        MI1_POWER_FACTOR( //
                Doc.of(OpenemsType.DOUBLE) //
                        .unit(Unit.NONE) //
                        .text("Power factor of selected Hoymiles microinverter (register 0x38E9, 0.01/bit).")),

        MI1_AC_VOLTAGE_L1_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Grid phase voltage VphA [V] (0x38EA, 0.1 V/bit).")),

        MI1_AC_VOLTAGE_L2_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Grid phase voltage VphB [V] (0x38EB, 0.1 V/bit).")),

        MI1_AC_VOLTAGE_L3_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Grid phase voltage VphC [V] (0x38EC, 0.1 V/bit).")),

        MI1_AC_VOLTAGE_L1_L2_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Line-to-line voltage Uab [V] (0x38ED, 0.1 V/bit).")),

        MI1_AC_VOLTAGE_L2_L3_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Line-to-line voltage Ubc [V] (0x38EE, 0.1 V/bit).")),

        MI1_AC_VOLTAGE_L3_L1_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("Line-to-line voltage Uca [V] (0x38EF, 0.1 V/bit).")),

        MI1_AC_CURRENT_L1_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("Phase current IphA [A] (0x38F0, 0.01 A/bit).")),

        MI1_AC_CURRENT_L2_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("Phase current IphB [A] (0x38F1, 0.01 A/bit).")),

        MI1_AC_CURRENT_L3_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("Phase current IphC [A] (0x38F2, 0.01 A/bit).")),

        MI1_GRID_FREQUENCY_HZ( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.HERTZ) //
                        .text("Grid frequency [Hz] (0x38F3, 0.01 Hz/bit).")),

        MI1_TEMPERATURE_C( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.DEGREE_CELSIUS) //
                        .text("Microinverter temperature [°C] (0x38F4, 0.1 °C/bit).")),

        MI1_PV1_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV1 input voltage [V] (0x38F5, 0.1 V/bit).")),

        MI1_PV2_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV2 input voltage [V] (0x38F6, 0.1 V/bit).")),

        MI1_PV3_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV3 input voltage [V] (0x38F7, 0.1 V/bit).")),

        MI1_PV4_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV4 input voltage [V] (0x38F8, 0.1 V/bit).")),

        MI1_PV5_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV5 input voltage [V] (0x38F9, 0.1 V/bit).")),

        MI1_PV6_VOLTAGE_V( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.VOLT) //
                        .text("PV6 input voltage [V] (0x38FA, 0.1 V/bit).")),

        MI1_PV1_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV1 input current [A] (0x38FB, 0.01 A/bit).")),

        MI1_PV2_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV2 input current [A] (0x38FC, 0.01 A/bit).")),

        MI1_PV3_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV3 input current [A] (0x38FD, 0.01 A/bit).")),

        MI1_PV4_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV4 input current [A] (0x38FE, 0.01 A/bit).")),

        MI1_PV5_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV5 input current [A] (0x38FF, 0.01 A/bit).")),

        MI1_PV6_CURRENT_A( //
                Doc.of(OpenemsType.INTEGER) //
                        .unit(Unit.AMPERE) //
                        .text("PV6 input current [A] (0x3900, 0.01 A/bit).")),

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
                        .text("Alarm 6 code (0x390D)."));

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
