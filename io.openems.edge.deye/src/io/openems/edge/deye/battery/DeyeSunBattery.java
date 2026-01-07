package io.openems.edge.deye.battery;

import static io.openems.common.channel.PersistencePriority.HIGH;

import org.osgi.service.event.EventHandler;

import io.openems.common.channel.AccessMode;
import io.openems.common.channel.Unit;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.types.OpenemsType;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.channel.EnumReadChannel;
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
//import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.deye.enums.BatteryChargeMode;
import io.openems.edge.deye.enums.BatteryOperateMode;
import io.openems.edge.deye.enums.BatteryRunState;
import io.openems.edge.deye.enums.WorkState;
import io.openems.edge.timedata.api.TimedataProvider;

public interface DeyeSunBattery
		extends Battery, OpenemsComponent, EventHandler, ModbusSlave, TimedataProvider {

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

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// OpenEMS state machine
		RUN_STATE(Doc.of(BatteryRunState.values()) //
				.text("Current State of State-Machine").persistencePriority(HIGH)), //

		//START_STOP(Doc.of(StartStop.values())),

	    // BMS Status Registers (read-only)
	    BMS_CHARGING_VOLTAGE(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIVOLT)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_DISCHARGING_VOLTAGE(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIVOLT)
	            .accessMode(AccessMode.READ_WRITE)),

	    /*
	     * NOTE: Naming fix (per Modbus doc):
	     *  - Reg 108/109 are manual inverter limits (not BMS-originated)
	     *  - Reg 212/213 are dynamic limits reported by inverter/BMS path
	     */
	    MANUAL_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: Reg 108 manual limit (inverter setting), not "BMS"
	    MANUAL_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: Reg 109 manual limit (inverter setting), not "BMS"

	    DYNAMIC_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: Reg 212 dynamic reported limit used for min(manual,dynamic)
	    DYNAMIC_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: Reg 213 dynamic reported limit used for min(manual,dynamic)

	    // Backwards compatibility aliases (remove later)
	    @Deprecated
	    BMS_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: deprecated alias -> MANUAL_CHARGE_CURRENT_LIMIT
	    @Deprecated
	    BMS_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.AMPERE)
	            .accessMode(AccessMode.READ_WRITE)), //mrdomek: deprecated alias -> MANUAL_DISCHARGE_CURRENT_LIMIT

	    BMS_BATTERY_SOC(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.PERCENT)
	            .accessMode(AccessMode.READ_ONLY)),
	    BMS_BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIVOLT)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_CURRENT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIAMPERE)
	            .accessMode(AccessMode.READ_WRITE)),
	    OFF_GRID_BATTERY_CHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIAMPERE)
	            .accessMode(AccessMode.READ_WRITE)),
	    OFF_GRID_BATTERY_DISCHARGE_CURRENT_LIMIT(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.MILLIAMPERE)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_ALARM(Doc.of(OpenemsType.INTEGER)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_FAULT_LOCATION(Doc.of(OpenemsType.INTEGER)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_SYMBOL_2(Doc.of(OpenemsType.INTEGER)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_LITHIUM_TYPE(Doc.of(OpenemsType.INTEGER)
	            .accessMode(AccessMode.READ_WRITE)),
	    BMS_BATTERY_SOH(Doc.of(OpenemsType.INTEGER)
	            .accessMode(AccessMode.READ_WRITE)),

		SET_BATTERY_CHARGE_MODE(Doc.of(BatteryChargeMode.values()) // lead or lithium optimized charge curve
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_CHARGE_MODE(Doc.of(BatteryChargeMode.values()) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_EQUALIZATION_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_EQUALIZATION_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_ABSORPTION_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_ABSORPTION_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_FLOAT_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_FLOAT_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_CAPACITY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE_HOURS) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_CAPACITY(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.AMPERE_HOURS) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_EMPTY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_EMPTY_VOLTAGE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIVOLT) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_ZERO_EXPORT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_ZERO_EXPORT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.READ_ONLY)), //

		// Days between battery balancing cycles
		// Values between 0-90 days are vaild
		SET_BATTERY_EQUALIZATION_DAY_CYCLE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_EQUALIZATION_DAY_CYCLE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.READ_ONLY)), //

		// time in balancing mode. Resolution 0.5h
		// Values 0-20 are valid (-> max. 10h)
		SET_BATTERY_EQUALIZATION_TIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_EQUALIZATION_TIME(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.READ_ONLY)), //

		SET_BATTERY_MAX_CHARGE_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_MAX_CHARGE_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.READ_WRITE)), //

		SET_BATTERY_MAX_DISCHARGE_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_MAX_DISCHARGE_CURRENT(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.MILLIAMPERE) //
				.accessMode(AccessMode.READ_WRITE)), //

		// Battery voltages need to be adjusted based on temperature
		// (for example, charging voltage must be higher in cold conditions).
		// The TEMPCO register defines by how many millivolts per degree Celsius the voltage setpoints shift per cell.
		SET_BATTERY_TEMPERATURE_COMPENSATION_VALUE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		BATTERY_TEMPERATURE_COMPENSATION_VALUE(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.NONE) //
				.accessMode(AccessMode.READ_ONLY)), //

	    // Battery operating parameters (read-only)
	    BATTERY_OPERATE_MODE(Doc.of(BatteryOperateMode.values())
	        .accessMode(AccessMode.READ_ONLY)), // register 111

	    // Battery operating parameters (read-only)
	    SET_BATTERY_OPERATE_MODE(Doc.of(BatteryOperateMode.values())
	        .accessMode(AccessMode.WRITE_ONLY)), // register 111

	    LITHIUM_WAKE_UP_SIGN(Doc.of(OpenemsType.INTEGER)
	        .accessMode(AccessMode.READ_ONLY)), // register 112

	    BATTERY_INTERNAL_RESISTANCE(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.MILLIOHM)
	        .accessMode(AccessMode.READ_ONLY)), // register 113

	    BATTERY_CHARGING_EFFICIENCY(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.PERCENT)
	        .accessMode(AccessMode.READ_ONLY)), // register 114

	    BATTERY_CAPACITY_SHUTDOWN(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.PERCENT)
	        .accessMode(AccessMode.READ_ONLY)), // register 115

	    BATTERY_CAPACITY_RESTART(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.PERCENT)
	        .accessMode(AccessMode.READ_ONLY)), // register 116

	    BATTERY_LOW_BATT_CAPACITY(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.PERCENT)
	        .accessMode(AccessMode.READ_ONLY)), // register 117

	    BATTERY_VOLTAGE_SHUTDOWN(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.MILLIVOLT)
	        .accessMode(AccessMode.READ_ONLY)), // register 118

	    BATTERY_VOLTAGE_RESTART(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.MILLIVOLT)
	        .accessMode(AccessMode.READ_ONLY)), // register 119

	    BATTERY_VOLTAGE_LOW_BATT(Doc.of(OpenemsType.INTEGER)
	        .unit(Unit.MILLIVOLT)
	        .accessMode(AccessMode.READ_ONLY)), // register 120

	    // BMS Metrics
	    BATTERY_TEMPERATURE(Doc.of(OpenemsType.INTEGER) // register 586
	            .unit(Unit.DEGREE_CELSIUS)
	            .accessMode(AccessMode.READ_ONLY)),
	    BATTERY_VOLTAGE(Doc.of(OpenemsType.INTEGER) // register 587
	            .unit(Unit.MILLIVOLT)
	            .accessMode(AccessMode.READ_ONLY)),
	    BATTERY_SOC(Doc.of(OpenemsType.INTEGER) // register 588
	            .unit(Unit.PERCENT)
	            .accessMode(AccessMode.READ_ONLY)),
	    BATTERY_OUTPUT_POWER(Doc.of(OpenemsType.INTEGER) // register 590
	            .unit(Unit.WATT)
	            .accessMode(AccessMode.READ_ONLY)),
	    BATTERY_OUTPUT_CURRENT(Doc.of(OpenemsType.INTEGER) // register 591
	            .unit(Unit.MILLIAMPERE)
	            .accessMode(AccessMode.READ_ONLY)),
	    BATTERY_CORRECTED_AH(Doc.of(OpenemsType.INTEGER) // register 592
	            .unit(Unit.AMPERE_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),

	    // Battery Energy
	    TODAY_BATTERY_CHARGE(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),
	    TODAY_BATTERY_DISCHARGE(Doc.of(OpenemsType.INTEGER)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),

	    TOTAL_BATTERY_CHARGE(Doc.of(OpenemsType.LONG)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),
	    TOTAL_BATTERY_DISCHARGE(Doc.of(OpenemsType.LONG)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),

	    // OpenEMS calculated channels
	    // Ideally they have the same values as total
	    // counters from Deye
	    DC_CHARGE_ENERGY(Doc.of(OpenemsType.LONG)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),
	    DC_DISCHARGE_ENERGY(Doc.of(OpenemsType.LONG)
	            .unit(Unit.WATT_HOURS)
	            .accessMode(AccessMode.READ_ONLY)),

	    // Temperatures
	    DC_TRANSFORMER_TEMP(Doc.of(OpenemsType.FLOAT)
	            .unit(Unit.DEGREE_CELSIUS)
	            .accessMode(AccessMode.READ_ONLY)),
	    HEATSINK_TEMP(Doc.of(OpenemsType.FLOAT)
	            .unit(Unit.DEGREE_CELSIUS)
	            .accessMode(AccessMode.READ_ONLY)),

		// EnumWriteChannels
		SET_WORK_STATE(Doc.of(WorkState.values()) //
				.accessMode(AccessMode.WRITE_ONLY)), //

		// IntegerWriteChannel
		SET_ACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		SET_REACTIVE_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE_REACTIVE) //
				.accessMode(AccessMode.WRITE_ONLY)), //

		SET_APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE) //
				.accessMode(AccessMode.WRITE_ONLY)), //

		SET_GEN_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		SET_GRID_PEAK_SHAVING_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.WATT) //
				.accessMode(AccessMode.WRITE_ONLY)), //
		CT_RATIO(Doc.of(OpenemsType.INTEGER)), //

		// LongReadChannel
		ORIGINAL_ACTIVE_CHARGE_ENERGY(Doc.of(OpenemsType.LONG)), //
		ORIGINAL_ACTIVE_DISCHARGE_ENERGY(Doc.of(OpenemsType.LONG)), //

		APPARENT_POWER(Doc.of(OpenemsType.INTEGER) //
				.unit(Unit.VOLT_AMPERE)), //
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

	// --- Existing helper methods below unchanged (except the renamed ones) ---

	public default IntegerReadChannel getBatteryAlarmChannel() {
		return this.channel(ChannelId.BMS_BATTERY_ALARM);
	}

	public default Value<Integer> getBatteryAlarm() {
		return this.getBatteryAlarmChannel().value();
	}

	public default IntegerReadChannel getDcPowerChannel() {
		return this.channel(ChannelId.BATTERY_OUTPUT_POWER);
	}

	public default Value<Integer> getDcPower() {
		return this.getDcPowerChannel().value();
	}

	public default IntegerReadChannel getBatteryCurrentChannel() {
		return this.channel(ChannelId.BATTERY_OUTPUT_CURRENT);
	}

	public default Value<Integer> getBatteryCurrent() {
		return this.getBatteryCurrentChannel().value();
	}

	// ---- New: Manual limits (Reg 108/109) ----

	public default IntegerReadChannel getManualChargeCurrentLimitChannel() {
		return this.channel(ChannelId.MANUAL_CHARGE_CURRENT_LIMIT);
	}

	public default Value<Integer> getManualChargeCurrentLimit() {
		return this.getManualChargeCurrentLimitChannel().value();
	}

	public default IntegerReadChannel getManualDischargeCurrentLimitChannel() {
		return this.channel(ChannelId.MANUAL_DISCHARGE_CURRENT_LIMIT);
	}

	public default Value<Integer> getManualDischargeCurrentLimit() {
		return this.getManualDischargeCurrentLimitChannel().value();
	}

	public default IntegerWriteChannel getSetManualChargeCurrentLimitChannel() {
		return this.channel(ChannelId.MANUAL_CHARGE_CURRENT_LIMIT);
	}

	public default void setManualChargeCurrentLimit(int value) throws OpenemsNamedException {
		this.getSetManualChargeCurrentLimitChannel().setNextWriteValue(value);
	}

	public default IntegerWriteChannel getSetManualDischargeCurrentLimitChannel() {
		return this.channel(ChannelId.MANUAL_DISCHARGE_CURRENT_LIMIT);
	}

	public default void setManualDischargeCurrentLimit(int value) throws OpenemsNamedException {
		this.getSetManualDischargeCurrentLimitChannel().setNextWriteValue(value);
	}

	// ---- New: Dynamic limits (Reg 212/213) ----

	public default IntegerReadChannel getDynamicChargeCurrentLimitChannel() {
		return this.channel(ChannelId.DYNAMIC_CHARGE_CURRENT_LIMIT);
	}

	public default Value<Integer> getDynamicChargeCurrentLimit() {
		return this.getDynamicChargeCurrentLimitChannel().value();
	}

	public default IntegerReadChannel getDynamicDischargeCurrentLimitChannel() {
		return this.channel(ChannelId.DYNAMIC_DISCHARGE_CURRENT_LIMIT);
	}

	public default Value<Integer> getDynamicDischargeCurrentLimit() {
		return this.getDynamicDischargeCurrentLimitChannel().value();
	}

	// ---- Deprecated compatibility methods (keep until callers are migrated) ----

	/**
	 * @deprecated Use {@link #getManualChargeCurrentLimit()} instead.
	 */
	@Deprecated
	public default Value<Integer> getBmsChargeCurrentLimit() {
		return this.getManualChargeCurrentLimit();
	}

	/**
	 * @deprecated Use {@link #getManualDischargeCurrentLimit()} instead.
	 */
	@Deprecated
	public default Value<Integer> getBmsDischargeCurrentLimit() {
		return this.getManualDischargeCurrentLimit();
	}

	/**
	 * @deprecated Use {@link #setManualChargeCurrentLimit(int)} instead.
	 */
	@Deprecated
	public default void setBmsMaxChargeCurrent(int value) throws OpenemsNamedException {
		this.setManualChargeCurrentLimit(value);
	}

	/**
	 * @deprecated Use {@link #setManualDischargeCurrentLimit(int)} instead.
	 */
	@Deprecated
	public default void setBmsMaxDischargeCurrent(int value) throws OpenemsNamedException {
		this.setManualDischargeCurrentLimit(value);
	}

	public default IntegerReadChannel getBmsBatteryVoltageChannel() {
		return this.channel(ChannelId.BMS_BATTERY_VOLTAGE);
	}

	public default Value<Integer> getBmsBatteryVoltage() {
		return this.getBmsBatteryVoltageChannel().value();
	}

	public default EnumReadChannel getBatteryOperateModeChannel() {
		return this.channel(ChannelId.BATTERY_OPERATE_MODE);
	}

	public default EnumWriteChannel getSetBatteryOperateModeChannel() {
		return this.channel(ChannelId.SET_BATTERY_OPERATE_MODE);
	}

	public default Value<Integer> getBatteryOperateMode() {
		return this.getBatteryOperateModeChannel().value();
	}

	public default void setBatteryOperateMode(BatteryOperateMode value) throws OpenemsNamedException {
		this.getSetBatteryOperateModeChannel().setNextWriteValue(value);
	}

	public static ModbusSlaveNatureTable getModbusSlaveNatureTable(AccessMode accessMode) {
		// TODO Auto-generated method stub
		return null;
	}

	// ----------------------------------------
	// StateMachine Channel
	// ----------------------------------------

	public default Channel<BatteryRunState> getRunStateChannel() {
		return this.channel(ChannelId.RUN_STATE);
	}

	public default BatteryRunState getRunState() {
		return this.getRunStateChannel().value().asEnum();
	}

	public default void _setRunState(BatteryRunState value) {
		this.getRunStateChannel().setNextValue(value);
	}

	public default IntegerReadChannel getBatteryVoltageChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE);
	}

	public default Value<Integer> getBatteryVoltage() {
		return this.getBatteryVoltageChannel().value();
	}

	public default IntegerReadChannel getBatteryEmptyVoltageChannel() {
		return this.channel(ChannelId.BATTERY_EMPTY_VOLTAGE);
	}

	public default Value<Integer> getBatteryEmptyVoltage() {
		return this.getBatteryEmptyVoltageChannel().value();
	}

	public default IntegerReadChannel getBatteryVoltageLowChannel() {
		return this.channel(ChannelId.BATTERY_VOLTAGE_LOW_BATT);
	}

	public default Value<Integer> getBatteryVoltageLow() {
		return this.getBatteryVoltageLowChannel().value();
	}

	public default IntegerReadChannel getBatteryCapacityShutdownChannel() {
		return this.channel(ChannelId.BATTERY_CAPACITY_SHUTDOWN);
	}

	public default Value<Integer> getBatteryCapacityShutdown() {
		return this.getBatteryCapacityShutdownChannel().value();
	}

	public default IntegerReadChannel getBatteryCapacityChannel() {
		return this.channel(ChannelId.BATTERY_CAPACITY);
	}

	public default Value<Integer> getBatteryCapacity() {
		return this.getBatteryCapacityChannel().value();
	}

	public boolean hasError();

	public boolean hasWarning();

	public int getConfiguredMaxChargeCurrent();

	public int getConfiguredMaxDischargeCurrent();

	public void setOfflineByExternal(String string);

	public void clearExternalOffline();
}
