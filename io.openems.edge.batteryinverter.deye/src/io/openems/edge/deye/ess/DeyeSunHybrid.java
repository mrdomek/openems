package io.openems.edge.deye.ess;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Level;
import io.openems.common.channel.PersistencePriority;
import io.openems.common.channel.Unit;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
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
		SET_WORK_STATE(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.WRITE_ONLY)
				.text("Sets the work state (0: START, 1: STOP). Implementation might send START periodically. See Modbus Reg 80.")), // Reg 80 [Source 23]
		SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.NONE) // Wert ist skaliert, nicht direkt Watt
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Setpoint for Active Power in 0.1% of nominal power (Range [-1200, 1200] -> [-120.0%, +120.0%]). See Modbus Reg 1111.")), // Reg 1111 [Source 89]
		SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER)
				.unit(Unit.NONE) // Wert ist skaliert, nicht direkt var
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Setpoint for Reactive Power in 0.1% of nominal power (Range [-436, +436]? -> [-43.6%, +43.6%]). See Modbus Reg 1118.")), // Reg 1118 [Source 91]
		SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)), // Reg 190 [Source 35]
		SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)), // Reg 191 [Source 35]
		CT_RATIO(Doc.of(OpenemsType.INTEGER)), // Reg 347 [Source 51]
		INVERTER_RUN_STATE(Doc.of(OpenemsType.INTEGER)), // Reg 500 [Source 63]
		ORIGINAL_ACTIVE_CHARGE_ENERGY(Doc.of(OpenemsType.LONG)), // Intern berechnet
		ORIGINAL_ACTIVE_DISCHARGE_ENERGY(Doc.of(OpenemsType.LONG)), // Intern berechnet
		RAW_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)), // Reg 212 [Source 37]
		RAW_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER).unit(Unit.AMPERE).accessMode(AccessMode.READ_ONLY)), // Reg 213 [Source 37]
		BATTERY_VOLTAGE_RAW(Doc.of(OpenemsType.INTEGER).accessMode(AccessMode.READ_ONLY)), // Reg 215, Unit 0.01V [Source 37]
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT).unit(Unit.VOLT)), // Intern berechnet/skaliert aus RAW
		ORIGINAL_ALLOWED_CHARGE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), // Intern berechnet
		ORIGINAL_ALLOWED_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)), // Intern berechnet
		APPARENT_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.VOLT_AMPERE)
				.text("Inverter Output Apparent Power calculated from Low/High Word registers (637/695).")),

		APPARENT_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.text("Low Word of Inverter Output Apparent Power (Register 637) - Internal use.")),
		APPARENT_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.text("High Word of Inverter Output Apparent Power (Register 695) - Internal use.")),

		// Temporäre interne Kanäle für 32-Bit Wirkleistung
		ACTIVE_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.text("Low Word of Inverter Output Active Power (Register 636) - Internal use.")),
		ACTIVE_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER)
				.accessMode(AccessMode.READ_ONLY)
				.text("High Word of Inverter Output Active Power (Register 694) - Internal use.")),


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
	 */
	public enum SetWorkState {
		START, // ordinal = 0
		STOP // ordinal = 1
	}

	/**
	 * Source-Channels für {@link ChannelId#SYSTEM_ERROR}.
	 */
	public static enum SystemErrorChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_149(Doc.of(OpenemsType.BOOLEAN).text("HighVoltageSideVoltageChangeUnconventionally - NEEDS MAPPING"));
		private final Doc doc;
		private SystemErrorChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels für {@link ChannelId#INSUFFICIENT_GRID_PARAMTERS}.
	 */
	public static enum InsufficientGridParametersChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_84(Doc.of(OpenemsType.BOOLEAN).text("Phase3InverterVoltageGeneralOvervoltageProtection - NEEDS MAPPING"));
		private final Doc doc;
		private InsufficientGridParametersChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels für {@link ChannelId#POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE}.
	 */
	public static enum PowerDecreaseCausedByOvertemperatureChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_146(Doc.of(OpenemsType.BOOLEAN).text("Fan4StartupFailed - NEEDS MAPPING to Fan Warn Bit"));
		private final Doc doc;
		private PowerDecreaseCausedByOvertemperatureChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

} // Ende DeyeSunHybrid Interface