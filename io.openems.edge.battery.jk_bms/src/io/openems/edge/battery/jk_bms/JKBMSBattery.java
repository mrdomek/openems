package io.openems.edge.battery.jk_bms;

import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface JKBMSBattery extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		// --- Cell voltages (mV) ---
		// [FIX] Führende Nullen für korrekte Sortierung im Log
		CELL_VOLTAGE_00_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_01_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_02_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_03_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_04_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_05_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_06_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_07_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_08_MV(Doc.of(OpenemsType.INTEGER)),
		CELL_VOLTAGE_09_MV(Doc.of(OpenemsType.INTEGER)),
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
		// [FIX] Führende Nullen für korrekte Sortierung im Log
		CELL_WIRE_RES_00_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_01_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_02_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_03_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_04_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_05_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_06_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_07_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_08_MOHM(Doc.of(OpenemsType.INTEGER)),
		CELL_WIRE_RES_09_MOHM(Doc.of(OpenemsType.INTEGER)),
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

		// --- Temperatures ---
		TEMP_MOS_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_1_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_2_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_3_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_4_C(Doc.of(OpenemsType.INTEGER)),
		TEMP_BAT_5_C(Doc.of(OpenemsType.INTEGER)),

		// --- Pack values ---
		BAT_VOLTAGE_MV(Doc.of(OpenemsType.LONG)),
		BAT_CURRENT_MA(Doc.of(OpenemsType.LONG)),

		// --- Balancing ---
		BALANCE_CURRENT_MA(Doc.of(OpenemsType.INTEGER)),
		BALANCE_STATE(Doc.of(OpenemsType.INTEGER)),

		// --- SOC/SOH ---
		SOC(Doc.of(OpenemsType.INTEGER)),
		SOH(Doc.of(OpenemsType.INTEGER)),
		SOC_CAP_REMAIN_MAH(Doc.of(OpenemsType.LONG)),
		SOC_FULL_CHARGE_CAP_MAH(Doc.of(OpenemsType.LONG)),
		SOC_CYCLE_COUNT(Doc.of(OpenemsType.LONG)),
		SOC_CYCLE_CAP_MAH(Doc.of(OpenemsType.LONG)),

		// --- Alarms / status ---
		USER_ALARM(Doc.of(OpenemsType.INTEGER)),
		USER_ALARM_2(Doc.of(OpenemsType.INTEGER)),
		MOS_TEMP_SENSOR_ABSENT(Doc.of(OpenemsType.INTEGER)),
		PCL_MODULE_STATUS(Doc.of(OpenemsType.INTEGER)),

		// --- Heater ---
		HEAT_CURRENT_MA(Doc.of(OpenemsType.INTEGER)),

		// --- Runtime ---
		SYS_RUN_TICKS_DS(Doc.of(OpenemsType.LONG));

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