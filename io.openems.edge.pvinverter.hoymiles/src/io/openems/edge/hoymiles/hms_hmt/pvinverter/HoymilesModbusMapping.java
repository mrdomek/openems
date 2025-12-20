package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.common.taskmanager.Priority;

/**
 * Encapsulates Hoymiles DTU Modbus register layout (elements), channel mapping and protocol tasks.
 */
//mrdomek Keep register layout and address math in a dedicated helper to keep the component readable.
public final class HoymilesModbusMapping {

	/*
	 * MI register layout:
	 * MI1 base: 0x38E0
	 * MI2 base: 0x3940
	 * block size: 0x60 (96 words) per microinverter
	 */
	//mrdomek Single source of truth for MI register block size.
	private static final int MI_REGISTER_BLOCK_SIZE = 0x60;

	private final int base;
	private final int portBase;

	// --- Serial + Energy ---
	private final SignedWordElement serialW0;
	private final SignedWordElement serialW1;
	private final SignedWordElement serialW2;

	private final UnsignedDoublewordElement totalProductionWh;
	private final UnsignedDoublewordElement todayProductionWh;

	// --- AC ---
	private final SignedWordElement activePower;
	private final SignedWordElement reactivePower;
	private final SignedWordElement powerFactor;

	private final SignedWordElement vphA;
	private final SignedWordElement vphB;
	private final SignedWordElement vphC;

	private final SignedWordElement uab;
	private final SignedWordElement ubc;
	private final SignedWordElement uca;

	private final SignedWordElement iphA;
	private final SignedWordElement iphB;
	private final SignedWordElement iphC;

	private final SignedWordElement frequency;
	private final SignedWordElement temperature;

	// --- PV Inputs ---
	private final SignedWordElement pv1Voltage;
	private final SignedWordElement pv1Current;
	private final SignedWordElement pv1Power;

	private final SignedWordElement pv2Voltage;
	private final SignedWordElement pv2Current;
	private final SignedWordElement pv2Power;

	private final SignedWordElement pv3Voltage;
	private final SignedWordElement pv3Current;
	private final SignedWordElement pv3Power;

	private final SignedWordElement pv4Voltage;
	private final SignedWordElement pv4Current;
	private final SignedWordElement pv4Power;

	private final SignedWordElement pv5Voltage;
	private final SignedWordElement pv5Current;
	private final SignedWordElement pv5Power;

	private final SignedWordElement pv6Voltage;
	private final SignedWordElement pv6Current;
	private final SignedWordElement pv6Power;

	// --- Status / Alarms ---
	private final SignedWordElement status;

	private final SignedWordElement alarm1;
	private final SignedWordElement alarm2;
	private final SignedWordElement alarm3;
	private final SignedWordElement alarm4;
	private final SignedWordElement alarm5;
	private final SignedWordElement alarm6;

	// --- Writes ---
	public final SignedWordElement portOnOff;
	public final SignedWordElement portTempLimitActivePower;

	private HoymilesModbusMapping(int base, int portBase) {
		this.base = base;
		this.portBase = portBase;

		// Serial + Energy
		this.serialW0 = new SignedWordElement(base + 0x00);
		this.serialW1 = new SignedWordElement(base + 0x01);
		this.serialW2 = new SignedWordElement(base + 0x02);

		this.totalProductionWh = new UnsignedDoublewordElement(base + 0x03);
		this.todayProductionWh = new UnsignedDoublewordElement(base + 0x05);

		// AC
		this.activePower = new SignedWordElement(base + 0x07);
		this.reactivePower = new SignedWordElement(base + 0x08);
		this.powerFactor = new SignedWordElement(base + 0x09);

		this.vphA = new SignedWordElement(base + 0x0A);
		this.vphB = new SignedWordElement(base + 0x0B);
		this.vphC = new SignedWordElement(base + 0x0C);

		this.uab = new SignedWordElement(base + 0x0D);
		this.ubc = new SignedWordElement(base + 0x0E);
		this.uca = new SignedWordElement(base + 0x0F);

		this.iphA = new SignedWordElement(base + 0x10);
		this.iphB = new SignedWordElement(base + 0x11);
		this.iphC = new SignedWordElement(base + 0x12);

		this.frequency = new SignedWordElement(base + 0x13);
		this.temperature = new SignedWordElement(base + 0x14);

		// PV inputs
		this.pv1Voltage = new SignedWordElement(base + 0x15);
		this.pv1Current = new SignedWordElement(base + 0x16);
		this.pv1Power = new SignedWordElement(base + 0x17);

		this.pv2Voltage = new SignedWordElement(base + 0x18);
		this.pv2Current = new SignedWordElement(base + 0x19);
		this.pv2Power = new SignedWordElement(base + 0x1A);

		this.pv3Voltage = new SignedWordElement(base + 0x1B);
		this.pv3Current = new SignedWordElement(base + 0x1C);
		this.pv3Power = new SignedWordElement(base + 0x1D);

		this.pv4Voltage = new SignedWordElement(base + 0x1E);
		this.pv4Current = new SignedWordElement(base + 0x1F);
		this.pv4Power = new SignedWordElement(base + 0x20);

		this.pv5Voltage = new SignedWordElement(base + 0x21);
		this.pv5Current = new SignedWordElement(base + 0x22);
		this.pv5Power = new SignedWordElement(base + 0x23);

		this.pv6Voltage = new SignedWordElement(base + 0x24);
		this.pv6Current = new SignedWordElement(base + 0x25);
		this.pv6Power = new SignedWordElement(base + 0x26);

		// Status / alarms
		this.status = new SignedWordElement(base + 0x27);

		this.alarm1 = new SignedWordElement(base + 0x28);
		this.alarm2 = new SignedWordElement(base + 0x29);
		this.alarm3 = new SignedWordElement(base + 0x2A);
		this.alarm4 = new SignedWordElement(base + 0x2B);
		this.alarm5 = new SignedWordElement(base + 0x2C);
		this.alarm6 = new SignedWordElement(base + 0x2D);

		// Writes
		this.portOnOff = new SignedWordElement(portBase + 0x0000);
		this.portTempLimitActivePower = new SignedWordElement(portBase + 0x0001);
	}

	public static HoymilesModbusMapping create(int microinverterNumber) {
		final int mi = Math.max(1, Math.min(microinverterNumber, 99));

		final int base = 0x38E0 + (mi - 1) * MI_REGISTER_BLOCK_SIZE;
		final int portBase = 0xD006 + (mi - 1) * 0x0006;

		return new HoymilesModbusMapping(base, portBase);
	}

	public void applyChannelMappings(PvInverterHoymilesHMSHMTImpl c) {
		// Serial words (used to build MI1_SERIAL in component)
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_0, this.serialW0);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_1, this.serialW1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_2, this.serialW2);

		// Energy
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH, this.totalProductionWh);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TODAY_PRODUCTION_WH, this.todayProductionWh);

		// AC power
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W, this.activePower,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_REACTIVE_POWER_VAR, this.reactivePower,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_POWER_FACTOR, this.powerFactor,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_3);

		// AC voltages
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_mV, this.vphA,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_mV, this.vphB,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_mV, this.vphC,
				ElementToChannelConverter.SCALE_FACTOR_2);

		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_L2_mV, this.uab,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_L3_mV, this.ubc,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_L1_mV, this.uca,
				ElementToChannelConverter.SCALE_FACTOR_2);

		// AC currents
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L1_mA, this.iphA,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L2_mA, this.iphB,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L3_mA, this.iphC,
				ElementToChannelConverter.SCALE_FACTOR_1);

		// Frequency + temperature
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_GRID_FREQUENCY_mHz, this.frequency,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TEMPERATURE_C, this.temperature,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		// PV voltages
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_VOLTAGE_mV, this.pv1Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_VOLTAGE_mV, this.pv2Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_VOLTAGE_mV, this.pv3Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_VOLTAGE_mV, this.pv4Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_VOLTAGE_mV, this.pv5Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_VOLTAGE_mV, this.pv6Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);

		// PV currents
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_CURRENT_mA, this.pv1Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_CURRENT_mA, this.pv2Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_CURRENT_mA, this.pv3Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_CURRENT_mA, this.pv4Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_CURRENT_mA, this.pv5Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_CURRENT_mA, this.pv6Current,
				ElementToChannelConverter.SCALE_FACTOR_1);

		// PV power
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W, this.pv1Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W, this.pv2Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W, this.pv3Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W, this.pv4Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W, this.pv5Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W, this.pv6Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		// Status + alarms
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE, this.status);

		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE, this.alarm1);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE, this.alarm2);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE, this.alarm3);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE, this.alarm4);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE, this.alarm5);
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE, this.alarm6);

		/*
		 * "Last sent" percent limit:
		 * this is a UI/debug mirror; the DTU does not provide a readback register here.
		 */
		c.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT, this.portTempLimitActivePower);
	}

	public FC4ReadInputRegistersTask createReadTask() {
		return new FC4ReadInputRegistersTask(this.base, Priority.HIGH,
				this.serialW0, this.serialW1, this.serialW2,
				this.totalProductionWh, this.todayProductionWh,
				this.activePower, this.reactivePower, this.powerFactor,
				this.vphA, this.vphB, this.vphC,
				this.uab, this.ubc, this.uca,
				this.iphA, this.iphB, this.iphC,
				this.frequency, this.temperature,
				this.pv1Voltage, this.pv1Current, this.pv1Power,
				this.pv2Voltage, this.pv2Current, this.pv2Power,
				this.pv3Voltage, this.pv3Current, this.pv3Power,
				this.pv4Voltage, this.pv4Current, this.pv4Power,
				this.pv5Voltage, this.pv5Current, this.pv5Power,
				this.pv6Voltage, this.pv6Current, this.pv6Power,
				this.status,
				this.alarm1, this.alarm2, this.alarm3, this.alarm4, this.alarm5, this.alarm6);
	}

	public FC16WriteRegistersTask createWriteTask() {
		return new FC16WriteRegistersTask(this.portBase, this.portOnOff, this.portTempLimitActivePower);
	}

	public ModbusProtocol createProtocol(PvInverterHoymilesHMSHMTImpl c) {
		return new ModbusProtocol(c, this.createReadTask(), this.createWriteTask());
	}
}
