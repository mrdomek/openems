package io.openems.edge.deye.ess;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.IntegerDoc;
import io.openems.edge.common.channel.StateChannel; // Nur für Typ-Info
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.timedata.api.TimedataProvider;

/**
 * Definiert die Schnittstelle für den Deye SUN Hybrid Wechselrichter.
 * Erweitert Standard-ESS-Funktionalitäten und fügt spezifische Kanäle hinzu.
 */
public interface DeyeSunHybrid
        extends ManagedSymmetricEss, SymmetricEss, OpenemsComponent, EventHandler, ModbusSlave, TimedataProvider {

    /**
     * Gets the Modbus Unit-ID.
     *
     * @return the Unit-ID
     */
    public Integer getUnitId();

    /**
     * Gets the Modbus-Bridge Component-ID, i.e. "modbus0".
     *
     * @return the Component-ID
     */
    public String getModbusBridgeId();


    /**
     * Definiert die Channel IDs für diese Komponente.
     */
    public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
        SERIAL_NUMBER(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.HIGH).accessMode(AccessMode.READ_ONLY)),
        SURPLUS_FEED_IN_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)),
        SET_GRID_LOAD_OFF_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).accessMode(AccessMode.WRITE_ONLY)),
        SET_WORK_STATE(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.WRITE_ONLY)), // Workaround
        SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)),
        SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE_REACTIVE).accessMode(AccessMode.WRITE_ONLY)),
        SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)),
        SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)),
        CT_RATIO(Doc.of(OpenemsType.INTEGER)),
        INVERTER_RUN_STATE(Doc.of(OpenemsType.INTEGER)),
        ORIGINAL_ACTIVE_CHARGE_ENERGY(Doc.of(OpenemsType.LONG)),
        ORIGINAL_ACTIVE_DISCHARGE_ENERGY(Doc.of(OpenemsType.LONG)),
        RAW_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)),
        RAW_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)),
        BATTERY_VOLTAGE_RAW(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)),
        BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), // Intern beschreibbar
        ORIGINAL_ALLOWED_CHARGE_POWER(new IntegerDoc().unit(Unit.WATT)),
        ORIGINAL_ALLOWED_DISCHARGE_POWER(new IntegerDoc().unit(Unit.WATT)),
        APPARENT_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE)),
        SYSTEM_ERROR(Doc.of(Level.FAULT).text("System-Error. More information at: https://deyeinverter.com/")),
        INSUFFICIENT_GRID_PARAMTERS(Doc.of(Level.FAULT).text("Insufficient Grid Parameters. More information at: https://deyeinverter.com/")),
        POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE(Doc.of(Level.FAULT).text("Power Decrease caused by Overtemperature. More information at:  https://deyeinverter.com/")),
        EMERGENCY_STOP_ACTIVATED(Doc.of(Level.WARNING).text("Emergency Stop has been activated. More information at:  https://deyeinverter.com/")),
        KEY_MANUAL_ACTIVATED(Doc.of(Level.WARNING).text("Key Manual has been activated. More information at:  https://deyeinverter.com/")),
        BECU_UNIT_DEFECTIVE(Doc.of(Level.FAULT).text("BECU Unit is defective. More information at:  https://deyeinverter.com/")),
        ;

        private final Doc doc;

        private ChannelId(Doc doc) {
            this.doc = doc;
        }

        @Override
        public Doc doc() {
            return this.doc;
        }

    } // Ende ChannelId Enum

    /**
     * Enum für den Arbeitszustand (Schreib-Kanal).
     */
    public enum SetWorkState {
        START, // ordinal = 0
        STOP   // ordinal = 1
    }

    /**
     * Source-Channels für {@link ChannelId#SYSTEM_ERROR}.
     */
    public static enum SystemErrorChannelId implements io.openems.edge.common.channel.ChannelId {
        STATE_149(Doc.of(OpenemsType.BOOLEAN).text("HighVoltageSideVoltageChangeUnconventionally"));
        private final Doc doc;
        private SystemErrorChannelId(Doc doc) { this.doc = doc; }
        @Override public Doc doc() { return this.doc; }
    }

    /**
     * Source-Channels für {@link ChannelId#INSUFFICIENT_GRID_PARAMTERS}.
     */
    public static enum InsufficientGridParametersChannelId implements io.openems.edge.common.channel.ChannelId {
        STATE_84(Doc.of(OpenemsType.BOOLEAN).text("Phase3InverterVoltageGeneralOvervoltageProtection"));
        private final Doc doc;
        private InsufficientGridParametersChannelId(Doc doc) { this.doc = doc; }
        @Override public Doc doc() { return this.doc; }
    }

    /**
     * Source-Channels für {@link ChannelId#POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE}.
     */
    public static enum PowerDecreaseCausedByOvertemperatureChannelId implements io.openems.edge.common.channel.ChannelId {
        STATE_146(Doc.of(OpenemsType.BOOLEAN).text("Fan4StartupFailed"));
        // Beispiel für einen Kommentar - stelle sicher, dass alle korrekt beendet sind */
        private final Doc doc;
        private PowerDecreaseCausedByOvertemperatureChannelId(Doc doc) { this.doc = doc; }
        @Override public Doc doc() { return this.doc; }
    }

} // Ende DeyeSunHybrid Interface