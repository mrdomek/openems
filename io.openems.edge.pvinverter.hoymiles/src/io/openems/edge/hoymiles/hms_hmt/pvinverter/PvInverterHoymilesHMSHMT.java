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
     */
    public static enum Phase {
        L1, L2, L3;
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
                        .text("Configured AC phase (L1/L2/L3) where the Hoymiles inverter is connected."));

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
