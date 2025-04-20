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
// import io.openems.edge.ess.api.HybridEss; // Removed as not implemented
import io.openems.edge.timedata.api.TimedataProvider;

/**
 * Defines the specific interface for the Deye SUN Hybrid inverter component.
 * Extends standard OpenEMS ESS functionality with Deye-specific channels
 * and settings. Assumes AC-coupled operation in this context.
 */
public interface DeyeSunHybrid extends // Implemented OpenEMS Natures:
		ManagedSymmetricEss, // For managed symmetric ESS control
		SymmetricEss, // For basic symmetric ESS properties
		// HybridEss, // Removed - Not implementing Hybrid functionality (direct DC PV)
		OpenemsComponent, // Base OpenEMS component nature
		EventHandler, // Handles OSGi events
		ModbusSlave, // Exposes data via Modbus slave
		TimedataProvider // Provides timeseries data
{

	/**
	 * Gets the Modbus Unit-ID configured for this device.
	 *
	 * @return the Unit-ID
	 */
	public Integer getUnitId();

	/**
	 * Gets the Component-ID of the Modbus-Bridge used by this component
	 * (e.g., "modbus0").
	 *
	 * @return the Component-ID of the bridge
	 */
	public String getModbusBridgeId();

	/**
	 * Defines the Channel IDs specific to the DeyeSunHybrid component.
	 */
	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		/*
		 * Configuration and Status Registers
		 * ------------------------------------
		 */
		SERIAL_NUMBER(Doc.of(OpenemsType.STRING) //
				.persistencePriority(PersistencePriority.HIGH) // Store persistently
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Device Serial Number. Read from Modbus registers 3-7.")), // Reg 3-7 [Source 13]

		INVERTER_RUN_STATE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Inverter run state code. See Deye documentation for meaning. Read from Modbus register 500.")),
		RUN_STATE_TEXT(Doc.of(OpenemsType.STRING) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Textdarstellung des Inverter Run State, basierend auf INVERTER_RUN_STATE")),
		CT_RATIO(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only? Or Config? -> Check Deye Docs
				.text("CT (Current Transformer) Ratio Setting. Read from Modbus register 347?")), // Reg 347 [Source 51] - Verify R/W and purpose

		/*
		 * Battery Related Registers (Raw and Calculated)
		 * ------------------------------------------------
		 */
		// Raw values read directly from Modbus
		RAW_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) // Unit: Amperes
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Raw battery charge current limit reported by BMS/Inverter. Read from Modbus register 212.")), // Reg 212 [Source 37]
		RAW_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE) // Unit: Amperes
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Raw battery discharge current limit reported by BMS/Inverter. Read from Modbus register 213.")), // Reg 213 [Source 37]
		BATTERY_VOLTAGE_RAW(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Raw battery voltage reading in 0.01 V. Read from Modbus register 215.")), // Reg 215, Unit 0.01V [Source 37]

		// Calculated values based on raw readings
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT) //
				.unit(Unit.VOLT) // Unit: Volts
				.text("Battery voltage scaled to Volts from BATTERY_VOLTAGE_RAW.")), // Internally calculated/scaled
		ORIGINAL_ALLOWED_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) // Unit: Watts
				.text("Allowed charge power calculated from voltage and current limits (before hysteresis/override). Value is negative.")), // Internally calculated
		ORIGINAL_ALLOWED_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) // Unit: Watts
				.text("Allowed discharge power calculated from voltage and current limits (before hysteresis/override). Value is positive.")), // Internally calculated

		/*
		 * Power Related Registers (32-bit combined values and components)
		 * ---------------------------------------------------------------
		 * Standard SymmetricEss.ACTIVE_POWER / REACTIVE_POWER / FREQUENCY / VOLTAGE
		 * channels are also implemented but defined in the SymmetricEss interface.
		 */
		// Components for 32-bit Active Power (Internal use for calculation)
		ACTIVE_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Internal Use: Low Word of AC Active Power (W). Read from Modbus register 636.")),
		ACTIVE_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Internal Use: High Word of AC Active Power (W). Read from Modbus register 694.")),

		// Components for 32-bit Apparent Power (Internal use for calculation)
		APPARENT_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Internal Use: Low Word of AC Apparent Power (VA). Read from Modbus register 637.")),
		APPARENT_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY) // Read from Modbus only
				.text("Internal Use: High Word of AC Apparent Power (VA). Read from Modbus register 695.")),

		// Combined 32-bit Apparent Power (Calculated)
		APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE) // Unit: VA
				.text("AC Apparent Power calculated internally from Low/High Word registers (637/695).")),

		/*
		 * Setpoint Write Registers / Channels
		 * -------------------------------------
		 */
		SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) // Unit: 0.1% of nominal power, not Watts directly
				.accessMode(AccessMode.WRITE_ONLY) // Write to Modbus only
				.text("Setpoint for Active Power in 0.1% of nominal power (Range [-1200, 1200] -> [-120.0%, +120.0%]). Written to Modbus register 1111.")), // Reg 1111 [Source 89]
		SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) // Unit: 0.1% of nominal power, not var directly
				.accessMode(AccessMode.WRITE_ONLY) // Write to Modbus only
				.text("Setpoint for Reactive Power in 0.1% of nominal power (Range [-436, +436]? -> [-43.6%, +43.6%]). Written to Modbus register 1118.")), // Reg 1118 [Source 91] - Verify range
		SET_WORK_STATE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.WRITE_ONLY) // Write to Modbus only
				.text("Sets the work state (0: START, 1: STOP). Written to Modbus register 80? Implementation might send START periodically.")), // Reg 80? [Source 23] - Verify address

		// Other Setpoints (Verify Modbus registers and necessity)
		SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)
				.text("Generator Peak Shaving Power Setpoint (W). Written to Modbus register 190?")), // Reg 190 [Source 35] - Verify
		SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT).accessMode(AccessMode.WRITE_ONLY)
				.text("Grid Peak Shaving Power Setpoint (W). Written to Modbus register 191?")), // Reg 191 [Source 35] - Verify

		// Unused / Placeholder Channels from previous version (verify or remove)
		SURPLUS_FEED_IN_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.WATT)
				.text("Purpose unclear - verify or remove.")), // Internal channel?
		SET_GRID_LOAD_OFF_POWER(Doc.of(OpenemsType.INTEGER).unit(Unit.PERCENT).accessMode(AccessMode.WRITE_ONLY)
				.text("Purpose unclear - verify Modbus register or remove.")), // ??? Not found

		/*
		 * Aggregated State Channels (Summarize detailed states/errors)
		 * -------------------------------------------------------------
		 * Source channels need to be mapped correctly from Modbus error/warning registers.
		 */
		SYSTEM_ERROR(Doc.of(Level.FAULT) // Severity: Fault
				.text("Aggregated System Error State. True if any underlying system error bit (mapped from Reg 555-558?) is active.")),
		INSUFFICIENT_GRID_PARAMTERS(Doc.of(Level.FAULT) // Severity: Fault
				.text("Aggregated Insufficient Grid Parameters State. True if any underlying grid parameter error bit (mapped from Reg 555-558?) is active.")),
		POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE(Doc.of(Level.WARNING) // Severity: Warning or Fault? -> Check Deye Behavior
				.text("Aggregated Overtemperature State. True if relevant fan/temperature warning/error bit (mapped from Reg 553?) is active.")),
		// Placeholders from previous version - Verify need and map correctly if kept:
		EMERGENCY_STOP_ACTIVATED(Doc.of(Level.WARNING) // Or Fault?
				.text("Placeholder: Emergency Stop Activated (map from Reg 555-558?)")),
		KEY_MANUAL_ACTIVATED(Doc.of(Level.WARNING)
				.text("Placeholder: Key Manual Activated (map from Reg 555-558?)")),
		BECU_UNIT_DEFECTIVE(Doc.of(Level.FAULT)
				.text("Placeholder: BECU Unit Defective (map from Reg 555-558?)")),
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

	} // End of ChannelId Enum

	/**
	 * Enum defining the possible values for the SET_WORK_STATE channel.
	 * Ordinal values (0 for START, 1 for STOP) are typically written to Modbus.
	 */
	public enum SetWorkState {
		START, // ordinal = 0
		STOP // ordinal = 1
	}

	/*
	 * Placeholder Enums for Source Channels of Aggregated States
	 * ----------------------------------------------------------
	 * These enums define placeholder Channel IDs. In a complete implementation,
	 * each enum value would correspond to a specific BooleanReadChannel mapped
	 * directly to a Modbus register bit representing that exact error/warning.
	 * The current names (e.g., STATE_149) are arbitrary placeholders.
	 */

	/**
	 * Source-Channels for {@link ChannelId#SYSTEM_ERROR}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum SystemErrorChannelId implements io.openems.edge.common.channel.ChannelId {
		// Example placeholder - replace with actual error bit channel
		STATE_149(Doc.of(OpenemsType.BOOLEAN).text("Placeholder: Needs mapping to specific error bit in Reg 555-558"));
		private final Doc doc;
		private SystemErrorChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels for {@link ChannelId#INSUFFICIENT_GRID_PARAMTERS}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum InsufficientGridParametersChannelId implements io.openems.edge.common.channel.ChannelId {
		// Example placeholder - replace with actual error bit channel
		STATE_84(Doc.of(OpenemsType.BOOLEAN).text("Placeholder: Needs mapping to specific grid error bit in Reg 555-558"));
		private final Doc doc;
		private InsufficientGridParametersChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels for {@link ChannelId#POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum PowerDecreaseCausedByOvertemperatureChannelId implements io.openems.edge.common.channel.ChannelId {
		// Example placeholder - replace with actual warning/error bit channel
		STATE_146(Doc.of(OpenemsType.BOOLEAN).text("Placeholder: Needs mapping to specific fan/temp bit in Reg 553?"));
		private final Doc doc;
		private PowerDecreaseCausedByOvertemperatureChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

} // End of DeyeSunHybrid Interface