package io.openems.edge.battery.jk_bms;

import static io.openems.common.channel.AccessMode.READ_ONLY;
import static io.openems.common.channel.AccessMode.READ_WRITE;
import static io.openems.common.channel.Unit.DEGREE_CELSIUS;
import static io.openems.common.channel.Unit.MILLIAMPERE;
import static io.openems.common.channel.Unit.MILLIAMPERE_HOURS;
import static io.openems.common.channel.Unit.MILLIVOLT;
import static io.openems.common.channel.Unit.NONE;
import static io.openems.common.types.OpenemsType.INTEGER;
import static io.openems.common.types.OpenemsType.LONG;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;

public interface JKBMSBattery extends OpenemsComponent {

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {

		/*
		 * Zellspannungen (mV)
		 */
		CELL_VOLTAGE_00_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 0")),
		CELL_VOLTAGE_01_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 1")),
		CELL_VOLTAGE_02_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 2")),
		CELL_VOLTAGE_03_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 3")),
		CELL_VOLTAGE_04_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 4")),
		CELL_VOLTAGE_05_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 5")),
		CELL_VOLTAGE_06_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 6")),
		CELL_VOLTAGE_07_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 7")),
		CELL_VOLTAGE_08_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 8")),
		CELL_VOLTAGE_09_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 9")),
		CELL_VOLTAGE_10_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 10")),
		CELL_VOLTAGE_11_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 11")),
		CELL_VOLTAGE_12_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 12")),
		CELL_VOLTAGE_13_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 13")),
		CELL_VOLTAGE_14_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 14")),
		CELL_VOLTAGE_15_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 15")),
		CELL_VOLTAGE_16_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 16")),
		CELL_VOLTAGE_17_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 17")),
		CELL_VOLTAGE_18_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 18")),
		CELL_VOLTAGE_19_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 19")),
		CELL_VOLTAGE_20_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 20")),
		CELL_VOLTAGE_21_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 21")),
		CELL_VOLTAGE_22_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 22")),
		CELL_VOLTAGE_23_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 23")),
		CELL_VOLTAGE_24_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 24")),
		CELL_VOLTAGE_25_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 25")),
		CELL_VOLTAGE_26_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 26")),
		CELL_VOLTAGE_27_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 27")),
		CELL_VOLTAGE_28_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 28")),
		CELL_VOLTAGE_29_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 29")),
		CELL_VOLTAGE_30_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 30")),
		CELL_VOLTAGE_31_MV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Cell Voltage 31")),

		/*
		 * Zell-Innenwiderstand / Wire Resistance (mOhm) – Unit ist aktuell NONE wie in
		 * deinem Stand
		 */
		CELL_WIRE_RES_00_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 0")),
		CELL_WIRE_RES_01_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 1")),
		CELL_WIRE_RES_02_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 2")),
		CELL_WIRE_RES_03_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 3")),
		CELL_WIRE_RES_04_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 4")),
		CELL_WIRE_RES_05_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 5")),
		CELL_WIRE_RES_06_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 6")),
		CELL_WIRE_RES_07_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 7")),
		CELL_WIRE_RES_08_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 8")),
		CELL_WIRE_RES_09_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 9")),
		CELL_WIRE_RES_10_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 10")),
		CELL_WIRE_RES_11_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 11")),
		CELL_WIRE_RES_12_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 12")),
		CELL_WIRE_RES_13_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 13")),
		CELL_WIRE_RES_14_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 14")),
		CELL_WIRE_RES_15_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 15")),
		CELL_WIRE_RES_16_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 16")),
		CELL_WIRE_RES_17_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 17")),
		CELL_WIRE_RES_18_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 18")),
		CELL_WIRE_RES_19_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 19")),
		CELL_WIRE_RES_20_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 20")),
		CELL_WIRE_RES_21_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 21")),
		CELL_WIRE_RES_22_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 22")),
		CELL_WIRE_RES_23_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 23")),
		CELL_WIRE_RES_24_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 24")),
		CELL_WIRE_RES_25_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 25")),
		CELL_WIRE_RES_26_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 26")),
		CELL_WIRE_RES_27_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 27")),
		CELL_WIRE_RES_28_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 28")),
		CELL_WIRE_RES_29_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 29")),
		CELL_WIRE_RES_30_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 30")),
		CELL_WIRE_RES_31_MOHM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Cell Wire Resistance 31")),

		/*
		 * Temperatur-Sensoren
		 */
		TEMP_MOS_C(Doc.of(INTEGER).unit(DEGREE_CELSIUS).accessMode(READ_ONLY).text("MOS Temperature")),
		TEMP_BAT_1_C(Doc.of(INTEGER).unit(DEGREE_CELSIUS).accessMode(READ_ONLY).text("Battery Temperature 1")),
		TEMP_BAT_2_C(Doc.of(INTEGER).unit(DEGREE_CELSIUS).accessMode(READ_ONLY).text("Battery Temperature 2")),
		TEMP_BAT_3_C(Doc.of(INTEGER).unit(DEGREE_CELSIUS).accessMode(READ_ONLY).text("Battery Temperature 3")),
		TEMP_BAT_4_C(Doc.of(INTEGER).unit(DEGREE_CELSIUS).accessMode(READ_ONLY).text("Battery Temperature 4")),

		/*
		 * Pack-/System-Werte
		 */
		BAT_VOLTAGE_MV(Doc.of(LONG).unit(MILLIVOLT).accessMode(READ_ONLY).text("Battery Voltage")),
		BAT_CURRENT_MA(Doc.of(LONG).unit(MILLIAMPERE).accessMode(READ_ONLY).text("Battery Current")),
		BALANCE_CURRENT_MA(Doc.of(INTEGER).unit(MILLIAMPERE).accessMode(READ_ONLY).text("Balance Current")),
		BALANCE_STATE(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Balance State")),
		HEAT_CURRENT_MA(Doc.of(INTEGER).unit(MILLIAMPERE).accessMode(READ_ONLY).text("Heat Current")),
		SYS_RUN_TICKS_DS(Doc.of(LONG).unit(NONE).accessMode(READ_ONLY).text("0.1s ticks")),

		/*
		 * SOC/SOH & Kapazitäten
		 */
		SOC_CAP_REMAIN_MAH(Doc.of(LONG).unit(MILLIAMPERE_HOURS).accessMode(READ_ONLY).text("Remaining capacity")),
		SOC_FULL_CHARGE_CAP_MAH(Doc.of(LONG).unit(MILLIAMPERE_HOURS).accessMode(READ_ONLY).text("Full charge capacity")),
		SOC_CYCLE_COUNT(Doc.of(LONG).unit(NONE).accessMode(READ_ONLY).text("Cycle count")),
		SOC_CYCLE_CAP_MAH(Doc.of(LONG).unit(MILLIAMPERE_HOURS).accessMode(READ_ONLY).text("Cycle capacity")),

		/*
		 * Alarme/Status
		 */
		USER_ALARM(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("User Alarm")),
		USER_ALARM_2(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("User Alarm 2")),
		TEMP_SENSOR_ABSENT(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Temp Sensor Absent")),
		PCL_MODULE_STATUS(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("PCL Module Status")),

		/*
		 * Settings (aus Block 3 / 0x1000)
		 */
		SETTINGS_CELL_UVP(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Settings: Cell UVP")),
		SETTINGS_CELL_OVP(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Settings: Cell OVP")),
		SETTINGS_CELL_RCV(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Settings: Cell RCV")),
		SETTINGS_VOLTAGE_SOC_0(Doc.of(INTEGER).unit(MILLIVOLT).accessMode(READ_ONLY).text("Settings: Voltage SOC 0")),
		SETTINGS_CAPACITY_AH(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Settings: Capacity (mAh)")),
		SETTINGS_MAX_CHARGE_CURRENT(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Settings: Max Charge Current")),
		SETTINGS_MAX_DISCHARGE_CURRENT(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Settings: Max Discharge Current")),
		SETTINGS_CELL_COUNT(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Settings: Cell Count")),
		SETTINGS_CHARGE_ENABLE(Doc.of(INTEGER).unit(NONE).accessMode(READ_WRITE).text("Settings: Charge Enable")),
		SETTINGS_DISCHARGE_ENABLE(Doc.of(INTEGER).unit(NONE).accessMode(READ_WRITE).text("Settings: Discharge Enable")),
		SETTINGS_BALANCE_ENABLE(Doc.of(INTEGER).unit(NONE).accessMode(READ_WRITE).text("Settings: Balance Enable")),
		SETTINGS_HEAT_ENABLE(Doc.of(INTEGER).unit(NONE).accessMode(READ_ONLY).text("Settings: Heat Enable"));

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
