package io.openems.edge.battery.jk_bms;

import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.DIRECT_1_TO_1;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_MINUS_1;

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

import io.openems.common.exceptions.OpenemsException;
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
		implements JKBMSBattery, ModbusComponent, OpenemsComponent, EventHandler {

	@Reference
	private ConfigurationAdmin cm;

	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public JKBMSBatteryImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				JKBMSBattery.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
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
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			break;
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		// Callback für SOC/SOH (derzeit deaktiviert oder auf Dummy gelegt, da Position unklar)
		// Wir lassen das Callback leer oder entfernen es vorerst, um NullPointer zu vermeiden,
		// bis wir wissen, wo SOC wirklich liegt.
		
		return new ModbusProtocol(this, //
				
				// --- SINGLE TASK: 125 Words ab 0x1200 ---
				// Bereich: 0x1200 bis 0x127C
				new FC3ReadRegistersTask(0x1200, Priority.LOW, //
						
						// --- 1. Zellspannungen (0x1200 - 0x121F) ---
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_0_MV, new UnsignedWordElement(0x1200), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_1_MV, new UnsignedWordElement(0x1201), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_2_MV, new UnsignedWordElement(0x1202), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_3_MV, new UnsignedWordElement(0x1203), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_4_MV, new UnsignedWordElement(0x1204), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_5_MV, new UnsignedWordElement(0x1205), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_6_MV, new UnsignedWordElement(0x1206), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_7_MV, new UnsignedWordElement(0x1207), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_8_MV, new UnsignedWordElement(0x1208), DIRECT_1_TO_1),
						m(JKBMSBattery.ChannelId.CELL_VOLTAGE_9_MV, new UnsignedWordElement(0x1209), DIRECT_1_TO_1),
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

						// --- 2. Der große Unbekannte Bereich (0x1220 - 0x127B) ---
						// Hier liegen Widerstände, Ströme, Temp, etc.
						// Wir füllen das mit Dummies auf, um den Block vollzumachen.
						// Länge: 0x127B - 0x1220 + 1 = 92 Register
						new DummyRegisterElement(0x1220, 0x127B),

						// --- 3. Das Ende (0x127C) ---
						// Soll laut Berechnung der 125. Wert sein.
						m(JKBMSBattery.ChannelId.PCL_MODULE_STATUS, new UnsignedWordElement(0x127C), DIRECT_1_TO_1)
				)
		);
	}	
	
	
	private void handleSocSoh(int rawWord) {
		final int soc = rawWord & 0xFF;
		final int soh = (rawWord >> 8) & 0xFF;

		this.channel(JKBMSBattery.ChannelId.SOC).setNextValue(soc);
		this.channel(JKBMSBattery.ChannelId.SOH).setNextValue(soh);
	}

	@Override
	public String debugLog() {
		return "JK-BMS"
				+ " U=" + this.channel(JKBMSBattery.ChannelId.BAT_VOLTAGE_MV).value().asString()
				+ " I=" + this.channel(JKBMSBattery.ChannelId.BAT_CURRENT_MA).value().asString()
				+ " SOC=" + this.channel(JKBMSBattery.ChannelId.SOC).value().asString()
				+ " SOH=" + this.channel(JKBMSBattery.ChannelId.SOH).value().asString();
	}
}