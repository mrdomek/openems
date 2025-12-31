package io.openems.edge.battery.jk_bms;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface JKBMSBattery extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// --- Cell voltages (mV) ---
		CELL_VOLTAGE_0_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_1_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_2_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_3_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_4_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_5_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_6_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_7_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_8_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_9_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_10_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_11_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_12_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_13_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_14_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_15_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_16_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_17_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_18_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_19_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_20_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_21_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_22_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_23_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_24_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_25_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_26_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_27_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_28_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_29_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_30_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_31_MV(Doc.of(OpenemsType.INTEGER)),

		// --- Cell wire resistance (mΩ) ---
		CELL_WIRE_RES_0_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_1_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_2_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_3_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_4_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_5_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_6_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_7_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_8_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_9_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_10_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_11_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_12_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_13_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_14_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_15_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_16_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_17_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_18_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_19_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_20_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_21_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_22_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_23_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_24_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_25_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_26_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_27_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_28_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_29_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_30_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_31_MOHM(Doc.of(OpenemsType.INTEGER)),

		// --- Temperatures (register is 0.1°C; we scale by -1 to get °C as int) ---
		TEMP_MOS_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_1_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_2_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_3_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_4_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_5_C(Doc.of(OpenemsType.INTEGER)),

		// --- Pack values ---
		BAT_VOLTAGE_MV(Doc.of(OpenemsType.LONG)),    //mrdomek Doc says mV (UINT32)
		BAT_CURRENT_MA(Doc.of(OpenemsType.LONG)),    //mrdomek Doc says mA (INT32)

		// --- Balancing ---
		BALANCE_CURRENT_MA(Doc.of(OpenemsType.INTEGER)),
		BALANCE_STATE(Doc.of(OpenemsType.INTEGER)),

		// --- SOC/SOH ---
		SOC(Doc.of(OpenemsType.INTEGER)), //mrdomek Derived from SOCSOH byte0
		SOH(Doc.of(OpenemsType.INTEGER)), //mrdomek Derived from SOCSOH byte1
		SOC_CAP_REMAIN_MAH(Doc.of(OpenemsType.LONG)),
		SOC_FULL_CHARGE_CAP_MAH(Doc.of(OpenemsType.LONG)),
		SOC_CYCLE_COUNT(Doc.of(OpenemsType.LONG)),
		SOC_CYCLE_CAP_MAH(Doc.of(OpenemsType.LONG)),

		// --- Alarms / status (raw for now) ---
		USER_ALARM(Doc.of(OpenemsType.INTEGER)),
		USER_ALARM_2(Doc.of(OpenemsType.INTEGER)),
		MOS_TEMP_SENSOR_ABSENT(Doc.of(OpenemsType.INTEGER)),
		PCL_MODULE_STATUS(Doc.of(OpenemsType.INTEGER)),

		// --- Heater ---
		HEAT_CURRENT_MA(Doc.of(OpenemsType.INTEGER)),

		// --- Runtime ---
		SYS_RUN_TICKS_DS(Doc.of(OpenemsType.LONG)); //mrdomek Doc says 0.1s ticks (UINT32)

		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}
}
