package io.openems.edge.battery.jk_bms;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Arrays;
import java.util.stream.Collectors;

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
import io.openems.edge.bridge.modbus.api.ChannelMetaInfoReadAndWrite;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerWriteChannel;
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
				"Event.topics=" + EdgeEventConstants.TOPIC_CYCLE_BEFORE_WRITE, //
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

	//mrdomek Cache raw settings values (per-cell mV) to derive pack voltage limits using CellCount.
	private Integer settingsCellCount = null;
	private Integer settingsCellRcvMv = null;
	private Integer settingsSoc0Mv = null;

	//mrdomek If true, enable-flag writes are scheduled once in the next BEFORE_WRITE cycle.
	private volatile boolean pendingWriteEnableFlags = false;

	public JKBMSBatteryImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				Battery.ChannelId.values(), //
				Arrays.stream(JKBMSBattery.ChannelId.values()) //
						//mrdomek Keep JK-specific channels (cell voltages, wire resistance, settings, etc.).
						//mrdomek Only avoid Channel-ID collisions with Battery.* (e.g., Soc/Voltage/Current if present).
						.filter(jkId -> Arrays.stream(Battery.ChannelId.values()) //
								.noneMatch(batId -> batId.name().equals(jkId.name()))) //
						.toArray(io.openems.edge.common.channel.ChannelId[]::new) //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			return;
		}
		this.pendingWriteEnableFlags = true; //mrdomek Apply config enable-switches via Modbus once after (re-)activation.
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_WRITE:
			if (this.pendingWriteEnableFlags) {
				if (this.writeEnableFlagsFromConfig()) {
					this.pendingWriteEnableFlags = false;
				}
			}
			break;

		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.logDebug(this.log, "Cycle finished");
			break;
		}
	}

	/**
	 * Sammelt ALLE Channel-Werte und gruppiert sie automatisch nach ihrer Herkunft
	 * (Nature/Interface). Funktioniert auch für zukünftige Natures ohne
	 * Code-Änderung.
	 */
	public String collectDebugData() {
		// Wir holen uns alle aktiven Channels der Komponente
		return this.channels().stream()
				// Sortieren nach Channel-Name (innerhalb der Gruppe)
				.sorted((c1, c2) -> c1.channelId().name().compareTo(c2.channelId().name()))
				// Gruppieren nach dem Namen des Interfaces (z.B. "Battery", "JKBMSBattery")
				.collect(Collectors.groupingBy(channel -> {
					// Trick: Wir holen die Klasse der ChannelId (meist ein Enum)
					// und davon die "Declaring Class" (das Interface).
					Class<?> clazz = channel.channelId().getClass();
					Class<?> declaring = clazz.getDeclaringClass();
					if (declaring != null) {
						return declaring.getSimpleName();
					} else {
						return clazz.getSimpleName();
					}
				})).entrySet().stream()
				// Ausgabe sortieren: Battery zuerst, dann JKBMSBattery, dann Rest alphabetisch
				.sorted((e1, e2) -> {
					String k1 = e1.getKey();
					String k2 = e2.getKey();
					if (k1.equals(k2)) {
						return 0;
					}
					if (k1.equals("Battery")) {
						return -1;
					}
					if (k2.equals("Battery")) {
						return 1;
					}
					if (k1.equals("JKBMSBattery")) {
						return -1;
					}
					if (k2.equals("JKBMSBattery")) {
						return 1;
					}
					return k1.compareTo(k2);
				}).map(entry -> {
					String groupName = entry.getKey();
					String channels = entry.getValue().stream().map(channel -> {
						try {
							return String.format("%-30s = %s", channel.channelId().name(), channel.value().asString());
						} catch (Exception e) {
							return "Error reading channel";
						}
					}).collect(Collectors.joining("\n"));
					return "--- " + groupName + " ---\n" + channels;
				}).collect(Collectors.joining("\n\n"));
	}

	@Override
	protected void logDebug(Logger log, String message) {
		if (this.config.debugMode()) {

			if (this.config.extendedDebugMode()) {
				this.logInfo(this.log,
						"\n ############################################## JK BMS Values Start #############################################");
				this.logInfo(this.log, this.collectDebugData());
				this.logInfo(this.log,
						"\n ############################################## JK BMS Values End #############################################");
			}
			this.logInfo(log, message);

		}
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().get() //
				+ "; Voltage:" + this.getVoltage().get() //
				+ "; Current:" + this.getCurrent().get();
	}

	@Override
	public void setStartStop(StartStop startStop) throws OpenemsException {
		this._setStartStop(startStop);
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //

				// --------------- BLOCK 1: Basic + Cell Voltages (Word-based addressing) ---------------
				new FC3ReadRegistersTask(0x1200, Priority.HIGH, //

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

				// --- BLOCK 2: Restliche Daten (Start: 0x128A) ---
				new FC3ReadRegistersTask(0x128A, Priority.HIGH, //

						// 0x128A: MOS Temp (INT16, °C * -0.1)
						m(JKBMSBattery.ChannelId.TEMP_MOS_C, new SignedWordElement(0x128A), SCALE_FACTOR_MINUS_1),

						// Dummy 0x128B - 0x128C
						new DummyRegisterElement(0x128B, 0x128C),

						// 0x128D: Battery Voltage (U32, mV)
						m(JKBMSBattery.ChannelId.BAT_VOLTAGE_MV, new UnsignedDoublewordElement(0x128D), DIRECT_1_TO_1)
								.onUpdateCallback(val -> {
									if (val == null)
										return;
									this._setVoltage(val.intValue() / 1000);
								}),

						// Dummy 0x128F - 0x1290
						new DummyRegisterElement(0x128F, 0x1290),

						// 0x1291: Battery Current (S32, mA)
						m(JKBMSBattery.ChannelId.BAT_CURRENT_MA,
								new SignedDoublewordElement(0x1291), DIRECT_1_TO_1)
										.onUpdateCallback(val -> {
											if (val == null)
												return;
											this._setCurrent(val.intValue() / 1000);
										}),

						// 0x1293: Battery Temp 1 (INT16, °C * -0.1)
						m(JKBMSBattery.ChannelId.TEMP_BAT_1_C, new SignedWordElement(0x1293), SCALE_FACTOR_MINUS_1),

						// 0x1294: Battery Temp 2 (INT16, °C * -0.1)
						m(JKBMSBattery.ChannelId.TEMP_BAT_2_C, new SignedWordElement(0x1294), SCALE_FACTOR_MINUS_1),

						// Dummy 0x1295 - 0x1296
						new DummyRegisterElement(0x1295, 0x1296),

						// 0x1297: Balance Current (INT16, mA)
						m(JKBMSBattery.ChannelId.BALANCE_CURRENT_MA, new SignedWordElement(0x1297), DIRECT_1_TO_1),

						// Offset 14: SOC (Byte 0) & Balance (Byte 1) bei 0x1298
						m(new UnsignedWordElement(0x1298)).build().onUpdateCallback(raw -> {
							if (raw == null) return;
							int soc = raw & 0xFF;
							int balance = (raw >> 8) & 0xFF;

							this._setSoc(soc); //mrdomek Canonical SOC is Battery.SOC; no private mirror channel.
							this.channel(JKBMSBattery.ChannelId.BALANCE_STATE).setNextValue(balance);
						}),

						// 0x1299: Remaining Capacity (S32, mAh)
						m(JKBMSBattery.ChannelId.SOC_CAP_REMAIN_MAH,
								new SignedDoublewordElement(0x1299), DIRECT_1_TO_1),

						// 0x129B: Full Charge Capacity (U32, mAh)
						m(JKBMSBattery.ChannelId.SOC_FULL_CHARGE_CAP_MAH, new UnsignedDoublewordElement(0x129B),
								DIRECT_1_TO_1),

						// 0x129D: Cycle Count (U32)
						m(JKBMSBattery.ChannelId.SOC_CYCLE_COUNT, new UnsignedDoublewordElement(0x129D), DIRECT_1_TO_1),

						// 0x129F: Cycle Capacity (U32, mAh)
						m(JKBMSBattery.ChannelId.SOC_CYCLE_CAP_MAH, new UnsignedDoublewordElement(0x129F), DIRECT_1_TO_1),

						// Offset 23: SOH (Byte 1)
						m(new UnsignedWordElement(0x12A1)).build().onUpdateCallback(raw -> {
							if (raw == null) return;
							int soh = (raw >> 8) & 0xFF;

							this._setSoh(soh); //mrdomek Canonical SOH is Battery.SOH; no private mirror channel.
						}),

						// 0x12A2: User Alarm
						m(JKBMSBattery.ChannelId.USER_ALARM, new UnsignedWordElement(0x12A2), DIRECT_1_TO_1),

						// Dummy 0x12A3 - 0x12A5
						new DummyRegisterElement(0x12A3, 0x12A5),

						// 0x12A6: User Alarm 2
						m(JKBMSBattery.ChannelId.USER_ALARM_2, new UnsignedWordElement(0x12A6), DIRECT_1_TO_1),

						// Dummy 0x12A7 - 0x12AC
						new DummyRegisterElement(0x12A7, 0x12AC),

						// 0x12AD: Temp Sensor Absent
						m(JKBMSBattery.ChannelId.TEMP_SENSOR_ABSENT, new UnsignedWordElement(0x12AD), DIRECT_1_TO_1),

						// Dummy 0x12AE - 0x12B5
						new DummyRegisterElement(0x12AE, 0x12B5),

						// 0x12B6: Heat Current (INT16, mA)
						m(JKBMSBattery.ChannelId.HEAT_CURRENT_MA, new SignedWordElement(0x12B6), DIRECT_1_TO_1),

						// Dummy 0x12B7
						new DummyRegisterElement(0x12B7, 0x12B7),

						// 0x12B8: System Run Time (U32, 0.1s)
						m(JKBMSBattery.ChannelId.SYS_RUN_TICKS_DS, new UnsignedDoublewordElement(0x12B8), DIRECT_1_TO_1),

						// Dummy 0x12BA - 0x12C0
						new DummyRegisterElement(0x12BA, 0x12C0),

						// 0x12C1: PCL Module Status
						m(JKBMSBattery.ChannelId.PCL_MODULE_STATUS, new UnsignedWordElement(0x12C1), DIRECT_1_TO_1),

						// 0x12C2: Battery Temp 3 (INT16, °C * -0.1)
						m(JKBMSBattery.ChannelId.TEMP_BAT_3_C, new SignedWordElement(0x12C2), SCALE_FACTOR_MINUS_1),

						// 0x12C3: Battery Temp 4 (INT16, °C * -0.1)
						m(JKBMSBattery.ChannelId.TEMP_BAT_4_C, new SignedWordElement(0x12C3), SCALE_FACTOR_MINUS_1)),

				// --- BLOCK 3: SETTINGS (0x1000 - 0x103E) ---
				new FC3ReadRegistersTask(0x1000, Priority.HIGH, //
						
						// 0x1000: Smart Sleep Voltage (Gap)
						new DummyRegisterElement(0x1000, 0x1001),
						
						// 0x1002: Cell UPV (UINT32, mV) -> MIN_CELL_VOLTAGE (V)
						m(JKBMSBattery.ChannelId.SETTINGS_CELL_UVP, new UnsignedDoublewordElement(0x1002), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setMinCellVoltage(val.intValue()); 
							}),

						// 0x1006 - 0x1005: (Gap)
						new DummyRegisterElement(0x1004, 0x1005),

							
						// 0x1006: Cell OPV (UINT32, mV) -> MIN_CELL_VOLTAGE (V)
						m(JKBMSBattery.ChannelId.SETTINGS_CELL_OVP, new UnsignedDoublewordElement(0x1006), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setMaxCellVoltage(val.intValue()); 
							}),
							
						// 0x1008 - 0x100D: (Gap)
						new DummyRegisterElement(0x1008, 0x100D),

							
						// 0x100E: Voltage SOC 0% (UINT32, mV) -> DISCHARGE_MIN_VOLTAGE (PACK V)
						m(JKBMSBattery.ChannelId.SETTINGS_VOLTAGE_SOC_0, new UnsignedDoublewordElement(0x100E), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								//mrdomek Cache per-cell mV, derive pack-level limit using CellCount.
								this.settingsSoc0Mv = val.intValue();
								this.updateDerivedVoltageLimits();
							}),
							
						// 0x1010: CELL RCV (UINT32, mV) -> CHARGE_MAX_VOLTAGE (PACK V)
						m(JKBMSBattery.ChannelId.SETTINGS_CELL_RCV, new UnsignedDoublewordElement(0x1010), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								//mrdomek Cache per-cell mV, derive pack-level limit using CellCount.
								this.settingsCellRcvMv = val.intValue();
								this.updateDerivedVoltageLimits();
							}),
					
					
						// Gap 0x1010 - 0x1015
						new DummyRegisterElement(0x1012, 0x1015),
						
						// 0x1016: Charge Current (UINT32, mA) -> CHARGE_MAX_CURRENT (A)
						// FIX: mA -> A
						m(JKBMSBattery.ChannelId.SETTINGS_MAX_CHARGE_CURRENT, new UnsignedDoublewordElement(0x1016), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setChargeMaxCurrent(val.intValue() / 1000); 
							}),
						
						// Gap 0x1018 - 0x101B
						new DummyRegisterElement(0x1018, 0x101B),
						
						// 0x101C: Discharge Current (UINT32, mA) -> DISCHARGE_MAX_CURRENT (A)
						// FIX: mA -> A
						m(JKBMSBattery.ChannelId.SETTINGS_MAX_DISCHARGE_CURRENT, new UnsignedDoublewordElement(0x101C), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								this._setDischargeMaxCurrent(val.intValue() / 1000); 
							}),
						
						// Gap 0x101E - 0x1035
						new DummyRegisterElement(0x101E, 0x1035),
						
						// 0x1036: CellCount
						m(JKBMSBattery.ChannelId.SETTINGS_CELL_COUNT, new UnsignedDoublewordElement(0x1036), DIRECT_1_TO_1)
							.onUpdateCallback(val -> {
								if (val == null) return;
								//mrdomek CellCount is required to compute pack voltage limits.
								this.settingsCellCount = val.intValue();
								this.updateDerivedVoltageLimits();
							}),

						m(JKBMSBattery.ChannelId.SETTINGS_CHARGE_ENABLE, new UnsignedDoublewordElement(0x1038), DIRECT_1_TO_1,
									new ChannelMetaInfoReadAndWrite(0x1038, 0x1070)),
						m(JKBMSBattery.ChannelId.SETTINGS_DISCHARGE_ENABLE, new UnsignedDoublewordElement(0x103A), DIRECT_1_TO_1,
									new ChannelMetaInfoReadAndWrite(0x103A, 0x1074)),
						m(JKBMSBattery.ChannelId.SETTINGS_BALANCE_ENABLE, new UnsignedDoublewordElement(0x103C), DIRECT_1_TO_1,
									new ChannelMetaInfoReadAndWrite(0x103C, 0x1078)),
			
						// 0x103E: Battery Capacity (UINT32, mAH) -> CAPACITY (Wh)
						m(JKBMSBattery.ChannelId.SETTINGS_CAPACITY_AH, new UnsignedDoublewordElement(0x103E), DIRECT_1_TO_1)
							.onUpdateCallback(valMah -> {
								if (valMah == null) return;
								// Capacity [Wh] = Capacity [mAh] * Voltage [V] / 1000
								// Achtung: Voltage ist jetzt in Volt (wegen Division oben).
								// Wenn getVoltage() jetzt z.B. 52 (V) liefert:
								// Wh = mAh * V / 1000
								Integer voltageV = this.getVoltage().get();
								if (voltageV != null) {
									long capacityWh = (long) valMah * voltageV / 1000;
									this._setCapacity((int) capacityWh);
									}
								})
						),

				// --- WRITE: enable flags (requested: one FC16 per switch; UINT32) ---
				new FC16WriteRegistersTask(0x1070, //
						m(JKBMSBattery.ChannelId.SETTINGS_CHARGE_ENABLE, new UnsignedDoublewordElement(0x1070), DIRECT_1_TO_1,
								new ChannelMetaInfoReadAndWrite(0x1038, 0x1070))),
				new FC16WriteRegistersTask(0x1074, //
						m(JKBMSBattery.ChannelId.SETTINGS_DISCHARGE_ENABLE, new UnsignedDoublewordElement(0x1074), DIRECT_1_TO_1,
								new ChannelMetaInfoReadAndWrite(0x103A, 0x1074))),
				new FC16WriteRegistersTask(0x1078, //
						m(JKBMSBattery.ChannelId.SETTINGS_BALANCE_ENABLE, new UnsignedDoublewordElement(0x1078), DIRECT_1_TO_1,
								new ChannelMetaInfoReadAndWrite(0x103C, 0x1078)))


				);
		
	}
	private void updateDerivedVoltageLimits() {
		//mrdomek JK provides per-cell voltage limits; OpenEMS Battery expects pack limits. We derive pack limits using CellCount.
		if (this.settingsCellCount == null || this.settingsCellCount.intValue() <= 0) {
			return;
		}
		final long cellCount = this.settingsCellCount.longValue();

		if (this.settingsCellRcvMv != null) {
			final long packMv = this.settingsCellRcvMv.longValue() * cellCount;
			this._setChargeMaxVoltage((int) (packMv / 1000L)); // mV -> V
		}

		if (this.settingsSoc0Mv != null) {
			final long packMv = this.settingsSoc0Mv.longValue() * cellCount;
			this._setDischargeMinVoltage((int) (packMv / 1000L)); // mV -> V
		}
	}

	private boolean writeEnableFlagsFromConfig() {
		//mrdomek We only schedule writes when requested; FC16WriteRegistersTask writes only channels with NextWriteValue set.
		if (this.config == null) {
			return false;
		}
		try {
			((IntegerWriteChannel) this.channel(JKBMSBattery.ChannelId.SETTINGS_CHARGE_ENABLE))
					.setNextWriteValue(this.config.chargeEnable() ? 1 : 0);
			((IntegerWriteChannel) this.channel(JKBMSBattery.ChannelId.SETTINGS_DISCHARGE_ENABLE))
					.setNextWriteValue(this.config.dischargeEnable() ? 1 : 0);
			((IntegerWriteChannel) this.channel(JKBMSBattery.ChannelId.SETTINGS_BALANCE_ENABLE))
					.setNextWriteValue(this.config.balanceEnable() ? 1 : 0);
			return true;
		} catch (Exception e) {
			this.logError(this.log, "Failed to schedule enable-flag writes: " + e.getMessage());
			return false;
		}
	}
}
