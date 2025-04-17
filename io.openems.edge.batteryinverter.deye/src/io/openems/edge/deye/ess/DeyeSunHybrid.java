package io.openems.edge.deye.ess;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
// import io.openems.edge.common.channel.StateChannel; // Fehler 3: Entfernt, da ungenutzt
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
		SERIAL_NUMBER(Doc.of(OpenemsType.STRING).persistencePriority(PersistencePriority.HIGH)
				.accessMode(AccessMode.READ_ONLY)), // Reg 3-7 [Source 13]
		SURPLUS_FEED_IN_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), // Interner Kanal für spezifische Logik
		SET_GRID_LOAD_OFF_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).accessMode(AccessMode.WRITE_ONLY)), // ??? Nicht in Doku gefunden
		// *** Schritt 3: Text angepasst ***
		SET_WORK_STATE(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.WRITE_ONLY)
				.text("Sets the work state (0: START, 1: STOP). Implementation might send START periodically. See Modbus Reg 80.")), // Reg 80 [Source 23]
		// *** NÄCHSTER SCHRITT: Einheit muss korrigiert werden basierend auf Reg 1111 (0.1%) ***
		SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)), // Reg 1111 [Source 89]
		// *** NÄCHSTER SCHRITT: Einheit muss korrigiert werden basierend auf Reg 1118 (0.1% oder 0.001 PF) ***
		SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE_REACTIVE)
				.accessMode(AccessMode.WRITE_ONLY)), // Reg 1118 [Source 91]
		SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)), // Reg 190 [Source 35]
		SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)), // Reg 191 [Source 35]
		CT_RATIO(Doc.of(OpenemsType.INTEGER)), // Reg 347 [Source 51]
		INVERTER_RUN_STATE(Doc.of(OpenemsType.INTEGER)), // Reg 500 [Source 63]
		ORIGINAL_ACTIVE_CHARGE_ENERGY(Doc.of(OpenemsType.LONG)), // Intern berechnet
		ORIGINAL_ACTIVE_DISCHARGE_ENERGY(Doc.of(OpenemsType.LONG)), // Intern berechnet
		RAW_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)), // Reg 212 [Source 37]
		RAW_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)), // Reg 213 [Source 37]
		BATTERY_VOLTAGE_RAW(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)), // Reg 215 [Source 37]
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), // Intern berechnet/skaliert aus RAW
		// *** Schritt 1: Doc-Erstellung vereinheitlicht ***
		ORIGINAL_ALLOWED_CHARGE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), // Intern berechnet
		ORIGINAL_ALLOWED_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), // Intern berechnet
		// *** NÄCHSTER SCHRITT: Einheit/Skalierung prüfen (Reg 620 -> 100VA?) ***
		APPARENT_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE)), // Reg 620 [Source 73]
		// --- Aggregierte State Channels (Quellen müssen noch gemappt werden) ---
		SYSTEM_ERROR(Doc.of(Level.FAULT).text("System-Error. Check Fault Registers 555-558.")),
		INSUFFICIENT_GRID_PARAMTERS(
				Doc.of(Level.FAULT).text("Insufficient Grid Parameters. Check Fault Registers 555-558.")),
		POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE(
				Doc.of(Level.FAULT).text("Power Decrease caused by Overtemperature. Check Warning Register 553 (Fan).")),
		EMERGENCY_STOP_ACTIVATED(
				Doc.of(Level.WARNING).text("Emergency Stop has been activated. Check Fault Registers 555-558.")),
		KEY_MANUAL_ACTIVATED(
				Doc.of(Level.WARNING).text("Key Manual has been activated. Check Fault Registers 555-558.")),
		BECU_UNIT_DEFECTIVE(Doc.of(Level.FAULT).text("BECU Unit is defective. Check Fault Registers 555-558.")),;

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
	 * Corresponds to Modbus Register 80 values? (0=OFF, 1=ON) [Source 23]
	 * Needs verification: START.ordinal() should map to 1? STOP.ordinal() to 0?
	 * Current implementation uses START.ordinal() -> check defineWorkState().
	 */
	public enum SetWorkState {
		START, // ordinal = 0 -> Maps to '1' (ON) on Modbus Reg 80?
		STOP // ordinal = 1 -> Maps to '0' (OFF) on Modbus Reg 80?
	}

	/**
	 * Source-Channels für {@link ChannelId#SYSTEM_ERROR}.
	 * Needs mapping to specific bits in Fault Registers (555-558).
	 */
	public static enum SystemErrorChannelId implements io.openems.edge.common.channel.ChannelId {
		// TODO: Map STATE_149 to actual fault bit, e.g., Fault Word X, Bit Y
		STATE_149(Doc.of(OpenemsType.BOOLEAN).text("HighVoltageSideVoltageChangeUnconventionally - NEEDS MAPPING"));
		private final Doc doc;

		private SystemErrorChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Source-Channels für {@link ChannelId#INSUFFICIENT_GRID_PARAMTERS}.
	 * Needs mapping to specific bits in Fault Registers (555-558).
	 */
	public static enum InsufficientGridParametersChannelId implements io.openems.edge.common.channel.ChannelId {
		// TODO: Map STATE_84 to actual fault bit, e.g., Fault Word X, Bit Y for Overvoltage Protection
		STATE_84(Doc.of(OpenemsType.BOOLEAN).text("Phase3InverterVoltageGeneralOvervoltageProtection - NEEDS MAPPING"));
		private final Doc doc;

		private InsufficientGridParametersChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	/**
	 * Source-Channels für {@link ChannelId#POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE}.
	 * Needs mapping to specific bits in Warning/Fault Registers (e.g., 553).
	 */
	public static enum PowerDecreaseCausedByOvertemperatureChannelId implements io.openems.edge.common.channel.ChannelId {
		// TODO: Map STATE_146 to Warning Reg 553, Bit 1 (FAN WARN) [Source 69]
		STATE_146(Doc.of(OpenemsType.BOOLEAN).text("Fan4StartupFailed - NEEDS MAPPING to Fan Warn Bit"));
		private final Doc doc;

		private PowerDecreaseCausedByOvertemperatureChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

} // Ende DeyeSunHybrid Interface