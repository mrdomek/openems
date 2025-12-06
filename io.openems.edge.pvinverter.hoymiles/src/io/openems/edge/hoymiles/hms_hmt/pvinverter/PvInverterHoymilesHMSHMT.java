package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import org.osgi.annotation.versioning.ProviderType;

import io.openems.edge.common.channel.annotation.ChannelInfo;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;
import io.openems.edge.pvinverter.api.SymmetricPvInverter;
import io.openems.edge.timedata.api.TimedataProvider;

@ProviderType
public interface PvInverterHoymilesHMSHMT
        extends ManagedSymmetricPvInverter, OpenemsComponent, TimedataProvider {

    /**
     * AC-Phase, an der der HMS/HMT hängt.
     */
    public static enum Phase {
        L1, L2, L3;
    }

    /**
     * Zusätzliche Hoymiles-spezifische Channels.
     *
     * Die "klassischen" PV-Channels kommen von {@link SymmetricPvInverter.ChannelId}.
     */
    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

        @ChannelInfo(
                type = ChannelInfo.Type.STRING,
                description = "Serial number of Microinverter 1 (register 0x38E0 ff.).")
        MI1_SERIAL,

        @ChannelInfo(
                type = ChannelInfo.Type.LONG,
                unit = "Wh",
                description = "Total production of Microinverter 1 in Wh (from 0x38E1; 0.1 kWh/bit → *100 Wh).")
        MI1_TOTAL_PRODUCTION_WH,

        @ChannelInfo(
                type = ChannelInfo.Type.LONG,
                unit = "Wh",
                description = "Today production of Microinverter 1 in Wh (if mapped; 0.01 kWh/bit → *10 Wh).")
        MI1_TODAY_PRODUCTION_WH,

        @ChannelInfo(
                type = ChannelInfo.Type.INTEGER,
                unit = "W",
                description = "AC active power of Microinverter 1, scaled to W (0x38E7, 0.1 W/bit).")
        MI1_ACTIVE_POWER_W,

        @ChannelInfo(
                type = ChannelInfo.Type.STRING,
                description = "Configured AC phase (L1/L2/L3) where the Hoymiles inverter is connected.")
        CONFIGURED_PHASE;
    }

    @Override
    default String debugLog() {
        // Kannst du später mit Details füllen
        return "";
    }
}
