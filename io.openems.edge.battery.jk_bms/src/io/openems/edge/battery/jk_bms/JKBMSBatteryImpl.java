package io.openems.edge.battery.jk_bms;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "io.openems.edge.battery.jk_bms", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = { //
				"Event.topics=" + EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
		} //
)
public class JKBMSBatteryImpl extends AbstractOpenemsModbusComponent
		implements JKBMSBattery, Battery, ModbusComponent, OpenemsComponent, EventHandler, StartStoppable {

	private final Logger log = LoggerFactory.getLogger(JKBMSBatteryImpl.class);

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	private Config config;

	public JKBMSBatteryImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				Battery.ChannelId.values(), //
				// FIX: Doppelte Channel-IDs filtern und Typ voll qualifizieren
				Arrays.stream(JKBMSBattery.ChannelId.values())
					.filter(id -> {
						return Arrays.stream(Battery.ChannelId.values())
								.noneMatch(batId -> batId.name().equals(id.name()));
					})
					.toArray(io.openems.edge.common.channel.ChannelId[]::new) // [FIX] Ambiguous Type gelöst
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void setStartStop(StartStop value) {
		this._setStartStop(value);
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.logDebug(this.log, "Cycle finished");
			break;
		}
	}

	public String collectDebugData() {
		return Stream.of( //
				JKBMSBattery.ChannelId.values(), //
				Battery.ChannelId.values(), //
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values() //
		) //
				.flatMap(Arrays::stream) //
				.map(id -> {
					try {
						return id.name() + "=" + this.channel(id).value().asString();
					} catch (Exception e) {
						return id.name() + "=n/a";
					}
				}) //
				.sorted() //
				.distinct() //
				.collect(Collectors.joining("; \n"));
	}

	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {
			if (this.config.extendedDebugMode()) {
				this.logInfo(log, "\n #################### EXTENDED DEBUG START ####################");
				this.logInfo(log, this.collectDebugData());
				this.logInfo(log, "\n #################### EXTENDED DEBUG END ####################");
			}
			this.logInfo(log, message);
		}
	}

	@Override
	public String debugLog() {
		return "JK-BMS" //
				+ " U=" + this.getVoltage().asString() //
				+ " I=" + this.getCurrent().asString() //
				+ " SOC=" + this.getSoc().asString() //
				+ " SOH=" + this.getSoh().asString();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //

				// --- BLOCK 1: Zellspannungen & Widerstände (0x1200 - 0x1244) ---
				// (Dieser Block bleibt exakt so wie in deinem Code)
				new FC3ReadRegistersTask(0x1200, Priority.LOW, //
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_00_MV, new UnsignedWordElement(0x1200), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_01_MV, new UnsignedWordElement(0x1201), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_02_MV, new UnsignedWordElement(0x1202), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_03_MV, new UnsignedWordElement(0x1203), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_04_MV, new UnsignedWordElement(0x1204), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_05_MV, new UnsignedWordElement(0x1205), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_06_MV, new UnsignedWordElement(0x1206), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_07_MV, new UnsignedWordElement(0x1207), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_08_MV, new UnsignedWordElement(0x1208), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_09_MV, new UnsignedWordElement(0x1209), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_10_MV, new UnsignedWordElement(0x120A), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_11_MV, new UnsignedWordElement(0x120B), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_12_MV, new UnsignedWordElement(0x120C), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_13_MV, new UnsignedWordElement(0x120D), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_14_MV, new UnsignedWordElement(0x120E), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_15_MV, new UnsignedWordElement(0x120F), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_16_MV, new UnsignedWordElement(0x1210), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_17_MV, new UnsignedWordElement(0x1211), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_18_MV, new UnsignedWordElement(0x1212), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_19_MV, new UnsignedWordElement(0x1213), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_20_MV, new UnsignedWordElement(0x1214), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_21_MV, new UnsignedWordElement(0x1215), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_22_MV, new UnsignedWordElement(0x1216), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_23_MV, new UnsignedWordElement(0x1217), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_24_MV, new UnsignedWordElement(0x1218), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_25_MV, new UnsignedWordElement(0x1219), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_26_MV, new UnsignedWordElement(0x121A), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_27_MV, new UnsignedWordElement(0x121B), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_28_MV, new UnsignedWordElement(0x121C), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_29_MV, new UnsignedWordElement(0x121D), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_30_MV, new UnsignedWordElement(0x121E), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_31_MV, new UnsignedWordElement(0x121F), DIRECT_1_TO_1),

						// GAP: 5 Register
						new DummyRegisterElement(0x1220, 0x1224),

						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_00_MOHM, new UnsignedWordElement(0x1225), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_01_MOHM, new UnsignedWordElement(0x1226), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_02_MOHM, new UnsignedWordElement(0x1227), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_03_MOHM, new UnsignedWordElement(0x1228), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_04_MOHM, new UnsignedWordElement(0x1229), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_05_MOHM, new UnsignedWordElement(0x122A), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_06_MOHM, new UnsignedWordElement(0x122B), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_07_MOHM, new UnsignedWordElement(0x122C), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_08_MOHM, new UnsignedWordElement(0x122D), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_09_MOHM, new UnsignedWordElement(0x122E), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_10_MOHM, new UnsignedWordElement(0x122F), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_11_MOHM, new UnsignedWordElement(0x1230), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_12_MOHM, new UnsignedWordElement(0x1231), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_13_MOHM, new UnsignedWordElement(0x1232), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_14_MOHM, new UnsignedWordElement(0x1233), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_15_MOHM, new UnsignedWordElement(0x1234), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_16_MOHM, new UnsignedWordElement(0x1235), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_17_MOHM, new UnsignedWordElement(0x1236), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_18_MOHM, new UnsignedWordElement(0x1237), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_19_MOHM, new UnsignedWordElement(0x1238), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_20_MOHM, new UnsignedWordElement(0x1239), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_21_MOHM, new UnsignedWordElement(0x123A), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_22_MOHM, new UnsignedWordElement(0x123B), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_23_MOHM, new UnsignedWordElement(0x123C), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_24_MOHM, new UnsignedWordElement(0x123D), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_25_MOHM, new UnsignedWordElement(0x123E), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_26_MOHM, new UnsignedWordElement(0x123F), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_27_MOHM, new UnsignedWordElement(0x1240), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_28_MOHM, new UnsignedWordElement(0x1241), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_29_MOHM, new UnsignedWordElement(0x1242), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_30_MOHM, new UnsignedWordElement(0x1243), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_WIRE_RES_31_MOHM, new UnsignedWordElement(0x1244), DIRECT_1_TO_1)),

				// --- BLOCK 2: Der "Shifted" Block ---
				// START: 0x128A
				new FC3ReadRegistersTask(0x128A, Priority.LOW, //

						// Offset 0
						m(JKBMSBattery.ChannelId.TEMP_MOS_C, new SignedWordElement(0x128A), SCALE_FACTOR_MINUS_1),

						// Offset 1
						new DummyRegisterElement(0x128B, 0x128C),

						// Offset 3
						m(JKBMSBattery.ChannelId.BAT_VOLTAGE_MV, new UnsignedDoublewordElement(0x128D), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setVoltage(val.intValue());
							}),

						// Offset 5
						new DummyRegisterElement(0x128F, 0x1290),

						// Offset 7
						m(JKBMSBattery.ChannelId.BAT_CURRENT_MA, new SignedDoublewordElement(0x1291), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setCurrent(val.intValue());
							}),

						// Offset 9
						m(JKBMSBattery.ChannelId.TEMP_BAT_1_C, new SignedWordElement(0x1293), SCALE_FACTOR_MINUS_1),

						// Offset 10
						m(JKBMSBattery.ChannelId.TEMP_BAT_2_C, new SignedWordElement(0x1294), SCALE_FACTOR_MINUS_1),

						// Offset 11
						new DummyRegisterElement(0x1295, 0x1296),

						// Offset 13
						m(JKBMSBattery.ChannelId.BALANCE_CURRENT_MA, new SignedWordElement(0x1297), DIRECT_1_TO_1),

						// Offset 14: SOC (Byte 0) & Balance (Byte 1) bei 0x1298
						m(new UnsignedWordElement(0x1298))
							.build()
							.onUpdateCallback(raw -> {
								if (raw == null) return;
								
								// Byte 0 = SOC
								int soc = raw & 0xFF;
								// Byte 1 = Balance State
								int balance = (raw >> 8) & 0xFF;
								
								// Setze SOC (Standard & Privat)
								this._setSoc(soc);
								try {
									this.channel(JKBMSBattery.ChannelId.SOC).setNextValue(soc);
								} catch (Exception e) {} // Ignorieren falls gefiltert

								// Setze Balance State (Privat)
								this.channel(JKBMSBattery.ChannelId.BALANCE_STATE).setNextValue(balance);
							}),

						// Offset 15
						m(JKBMSBattery.ChannelId.SOC_CAP_REMAIN_MAH, new SignedDoublewordElement(0x1299), DIRECT_1_TO_1),

						// Offset 17
						m(JKBMSBattery.ChannelId.SOC_FULL_CHARGE_CAP_MAH, new UnsignedDoublewordElement(0x129B), DIRECT_1_TO_1),

						// Offset 19
						m(JKBMSBattery.ChannelId.SOC_CYCLE_COUNT, new UnsignedDoublewordElement(0x129D), DIRECT_1_TO_1),

						// Offset 21
						m(JKBMSBattery.ChannelId.SOC_CYCLE_CAP_MAH, new UnsignedDoublewordElement(0x129F), DIRECT_1_TO_1),

						// Offset 23: Altes SOC/SOH bei 0x12A1 (SOH Byte 1)
						m(new UnsignedWordElement(0x12A1))
							.build()
							.onUpdateCallback(raw -> {
								if (raw == null) return;
								// Byte 1 = SOH
								int soh = (raw >> 8) & 0xFF;
								
								this._setSoh(soh);
								try {
									this.channel(JKBMSBattery.ChannelId.SOH).setNextValue(soh);
								} catch (Exception e) {}
							}),

						// Offset 24
						m(JKBMSBattery.ChannelId.USER_ALARM, new UnsignedWordElement(0x12A2), DIRECT_1_TO_1),

						// Offset 25
						new DummyRegisterElement(0x12A3, 0x12A4),

						// Offset 27
						new DummyRegisterElement(0x12A5, 0x12A5),

						// Offset 28
						m(JKBMSBattery.ChannelId.USER_ALARM_2, new UnsignedWordElement(0x12A6), DIRECT_1_TO_1),

						// Offset 29
						new DummyRegisterElement(0x12A7, 0x12AC),

						// Offset 35
						m(JKBMSBattery.ChannelId.MOS_TEMP_SENSOR_ABSENT, new UnsignedWordElement(0x12AD), DIRECT_1_TO_1),

						// Offset 36
						new DummyRegisterElement(0x12AE, 0x12B5),

						// Offset 44
						m(JKBMSBattery.ChannelId.HEAT_CURRENT_MA, new SignedWordElement(0x12B6), DIRECT_1_TO_1),

						// Offset 45
						new DummyRegisterElement(0x12B7, 0x12B9),

						// Offset 48
						m(JKBMSBattery.ChannelId.SYS_RUN_TICKS_DS, new UnsignedDoublewordElement(0x12BA), DIRECT_1_TO_1),

						// Offset 50
						new DummyRegisterElement(0x12BC, 0x12BD),

						// Offset 52
						m(JKBMSBattery.ChannelId.TEMP_BAT_3_C, new SignedWordElement(0x12BE), SCALE_FACTOR_MINUS_1),

						// Offset 53
						m(JKBMSBattery.ChannelId.TEMP_BAT_4_C, new SignedWordElement(0x12BF), SCALE_FACTOR_MINUS_1),

						// Offset 54
						m(JKBMSBattery.ChannelId.TEMP_BAT_5_C, new SignedWordElement(0x12C0), SCALE_FACTOR_MINUS_1),

						// Offset 55
						m(JKBMSBattery.ChannelId.PCL_MODULE_STATUS, new UnsignedWordElement(0x12C1), DIRECT_1_TO_1)));
	}	
	
	private void handleSocSoh(int rawWord) {
		final int soc = rawWord & 0xFF;
		final int soh = (rawWord >> 8) & 0xFF;

		// Update Standard Battery Nature
		this._setSoc(soc);
		this._setSoh(soh);
		
		// Update JK Specific Nature (wenn nicht gefiltert)
		try {
			this.channel(JKBMSBattery.ChannelId.SOC).setNextValue(soc);
			this.channel(JKBMSBattery.ChannelId.SOH).setNextValue(soh);
		} catch (Exception e) {
			// Ignorieren, falls Channel gefiltert wurde
		}
	}
}