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
 * Defines the specific interface for the Deye SUN Hybrid inverter component.
 * Extends standard OpenEMS ESS functionality with Deye-specific channels
 * and settings. Assumes AC-coupled operation in this context.
 */
public interface DeyeSunHybrid extends // Implemented OpenEMS Natures:
		ManagedSymmetricEss, // For managed symmetric ESS control
		SymmetricEss, // For basic symmetric ESS properties
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
				.text("CT (Current Transformer) Ratio Setting. Read from Modbus register 347?")),

		/*
		 * Battery Related Registers (Raw and Calculated)
		 * ------------------------------------------------
		 */
		// Raw values read directly from Modbus
		RAW_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)
				.accessMode(AccessMode.READ_ONLY)
				.text("Raw battery charge current limit reported by BMS/Inverter. Read from Modbus register 212.")),
		RAW_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE)
				.accessMode(AccessMode.READ_ONLY)
				.text("Raw battery discharge current limit reported by BMS/Inverter. Read from Modbus register 213.")),
		BATTERY_VOLTAGE_RAW(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Raw battery voltage reading in 0.01 V. Read from Modbus register 215.")),

		// Calculated values based on raw readings
		BATTERY_VOLTAGE(Doc.of(OpenemsType.FLOAT) //
				.unit(Unit.VOLT)
				.text("Battery voltage scaled to Volts from BATTERY_VOLTAGE_RAW.")),
		ORIGINAL_ALLOWED_CHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)
				.text("Allowed charge power calculated from voltage and current limits (before hysteresis/override). Value is negative.")),
		ORIGINAL_ALLOWED_DISCHARGE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)
				.text("Allowed discharge power calculated from voltage and current limits (before hysteresis/override). Value is positive.")),

		/*
		 * Power Related Registers (32-bit combined values and components)
		 * ---------------------------------------------------------------
		 */
		ACTIVE_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Internal Use: Low Word of AC Active Power (W). Read from Modbus register 636.")),
		ACTIVE_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Internal Use: High Word of AC Active Power (W). Read from Modbus register 694.")),

		APPARENT_POWER_LOW_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Internal Use: Low Word of AC Apparent Power (VA). Read from Modbus register 637.")),
		APPARENT_POWER_HIGH_WORD(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)
				.text("Internal Use: High Word of AC Apparent Power (VA). Read from Modbus register 695.")),

		APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)
				.text("AC Apparent Power calculated internally from Low/High Word registers (637/695).")),

		/*
		 * Setpoint Write Registers / Channels
		 * -------------------------------------
		 */
		SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) // 0.1% of nominal power
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Setpoint for Active Power in 0.1% of nominal power ([-1200,1200]). Written to Modbus register 1111.")),
		SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) // 0.1% of nominal power
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Setpoint for Reactive Power in 0.1% of nominal power. Written to Modbus register 1118.")),
		SET_WORK_STATE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Sets the work state (0: START, 1: STOP). Written to Modbus register 80?")),

		SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Generator Peak Shaving Power Setpoint (W). Written to Modbus register 190?")),
		SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Grid Peak Shaving Power Setpoint (W). Written to Modbus register 191?")),

		SURPLUS_FEED_IN_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT)
				.text("Purpose unclear - verify or remove.")),
		SET_GRID_LOAD_OFF_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.PERCENT)
				.accessMode(AccessMode.WRITE_ONLY)
				.text("Purpose unclear - verify Modbus register or remove.")),

		/*
		 * Aggregated State Channels (Summarize detailed states/errors)
		 * -------------------------------------------------------------
		 */
		SYSTEM_ERROR(Doc.of(Level.FAULT)
				.text("Aggregated System Error State. True if any underlying system error bit is active.")),
		INSUFFICIENT_GRID_PARAMTERS(Doc.of(Level.FAULT)
				.text("Aggregated Insufficient Grid Parameters State.")),
		POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE(Doc.of(Level.WARNING)
				.text("Aggregated Overtemperature State.")),
		EMERGENCY_STOP_ACTIVATED(Doc.of(Level.WARNING)
				.text("Placeholder: Emergency Stop Activated.")),
		KEY_MANUAL_ACTIVATED(Doc.of(Level.WARNING)
				.text("Placeholder: Key Manual Activated.")),
		BECU_UNIT_DEFECTIVE(Doc.of(Level.FAULT)
				.text("Placeholder: BECU Unit Defective.")),
		;

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}

	}

	/**
	 * Enum defining the possible values for the SET_WORK_STATE channel.
	 * Ordinal values (0 for START, 1 for STOP) are typically written to Modbus.
	 */
	public enum SetWorkState {
		START, // ordinal = 0
		STOP // ordinal = 1
	}

	/**
	 * Source-Channels for {@link ChannelId#SYSTEM_ERROR}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum SystemErrorChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_149(Doc.of(OpenemsType.BOOLEAN)
				.text("Placeholder: Needs mapping to specific error bit in Reg 555-558"));
		private final Doc doc;
		private SystemErrorChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels for {@link ChannelId#INSUFFICIENT_GRID_PARAMTERS}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum InsufficientGridParametersChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_84(Doc.of(OpenemsType.BOOLEAN)
				.text("Placeholder: Needs mapping to specific grid error bit in Reg 555-558"));
		private final Doc doc;
		private InsufficientGridParametersChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

	/**
	 * Source-Channels for {@link ChannelId#POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE}.
	 * TODO: Replace placeholders with actual Channels mapped to Modbus bits.
	 */
	public static enum PowerDecreaseCausedByOvertemperatureChannelId implements io.openems.edge.common.channel.ChannelId {
		STATE_146(Doc.of(OpenemsType.BOOLEAN)
				.text("Placeholder: Needs mapping to specific fan/temp bit in Reg 553?"));
		private final Doc doc;
		private PowerDecreaseCausedByOvertemperatureChannelId(Doc doc) { this.doc = doc; }
		@Override public Doc doc() { return this.doc; }
	}

}
