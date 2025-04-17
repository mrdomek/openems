package io.openems.edge.deye.ess;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.type.TypeUtils; // Bleibt hier für andere Util-Methoden, auch wenn toInteger nicht verwendet wird
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.StringWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveNatureTable;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.ManagedSymmetricEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.power.api.Constraint;
import io.openems.edge.ess.power.api.Phase;
import io.openems.edge.ess.power.api.Power;
import io.openems.edge.ess.power.api.Pwr;
import io.openems.edge.ess.power.api.Relationship;
import io.openems.edge.timedata.api.Timedata;
import io.openems.edge.timedata.api.TimedataProvider;
import io.openems.edge.timedata.api.utils.CalculateEnergyFromPower;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Deye.BatteryInverter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		service = { //
				DeyeSunHybrid.class, // Exportieren des spezifischen Interfaces
				SymmetricEss.class, //
				ManagedSymmetricEss.class, //
				// HybridEss.class, // Auskommentiert, da DC-Logik noch unklar/fehlerhaft
				TimedataProvider.class, //
				OpenemsComponent.class, //
				ModbusComponent.class, //
				ModbusSlave.class, //
				EventHandler.class //
		})
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, //
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
public class DeyeSunHybridImpl extends AbstractOpenemsModbusComponent implements DeyeSunHybrid {

	private static final int POWER_LIMIT_HYSTERESIS = 500;
	private final Logger log = LoggerFactory.getLogger(DeyeSunHybridImpl.class);

	private static final double PERCENT_SCALING_FACTOR = 10.0;

	// Referenzen
	@Reference
	protected ComponentManager componentManager;
	@Reference
	private Power power;
	@Reference
	private ConfigurationAdmin cm;
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null;

	private Config config;

	// Energieberechnung
	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this,
			HybridEss.ChannelId.DC_DISCHARGE_ENERGY);

	public DeyeSunHybridImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				ManagedSymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				DeyeSunHybrid.ChannelId.values(), //
				DeyeSunHybrid.SystemErrorChannelId.values(), //
				DeyeSunHybrid.InsufficientGridParametersChannelId.values(), //
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.unit_id(), this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}

		// Use configured values
		this._setMaxApparentPower(config.maxApparentPowerVA());
		if (config.netCapacityWh() > 0) {
			this.logInfo(log, "Using configured Net Capacity [" + config.netCapacityWh() + " Wh].");
			this._setCapacity(config.netCapacityWh());
		} else {
			this.logWarn(log,
					"Configured Net Capacity is 0 or not set. Battery visualization and some statistics might be inaccurate.");
			this._setCapacity(0);
		}

		// === Listener für Leistungslimits === (unverändert)
		IntegerReadChannel calculatedChargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
		Channel<Integer> finalChargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER);
		calculatedChargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedOpt = value.asOptional();
			Optional<Integer> currentFinalOpt = finalChargeLimitChannel.value().asOptional();
			final int finalValue;
			if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) { finalValue = 0;
			} else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) { finalValue = calculatedOpt.get();
			} else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) { finalValue = currentFinalOpt.get();
			} else {
				int calculatedMagnitude = Math.abs(calculatedOpt.get());
				int currentFinalMagnitude = Math.abs(currentFinalOpt.get());
				int finalMagnitude = Math.max(calculatedMagnitude, currentFinalMagnitude - POWER_LIMIT_HYSTERESIS);
				finalValue = -finalMagnitude; }
			finalChargeLimitChannel.setNextValue(Math.min(0, finalValue)); });
		IntegerReadChannel calculatedDischargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
		Channel<Integer> finalDischargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER);
		calculatedDischargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedOpt = value.asOptional();
			Optional<Integer> currentFinalOpt = finalDischargeLimitChannel.value().asOptional();
			final int finalValue;
			if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) { finalValue = 0;
			} else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) { finalValue = calculatedOpt.get();
			} else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) { finalValue = currentFinalOpt.get();
			} else { finalValue = Math.min(calculatedOpt.get(), currentFinalOpt.get() + POWER_LIMIT_HYSTERESIS); }
			finalDischargeLimitChannel.setNextValue(Math.max(0, finalValue)); });
		// === Ende Listener Leistungslimits ===


		// === Trigger für zusammengefasste StateChannels === (unverändert)
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.SYSTEM_ERROR, DeyeSunHybrid.SystemErrorChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.INSUFFICIENT_GRID_PARAMTERS,
				DeyeSunHybrid.InsufficientGridParametersChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE,
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values());
		// === Ende Trigger Registrierung ===

		// Listener zum Kombinieren von Low/High Words
		this.addApparentPowerCombinerListener();
		// *** NEU: Listener für Active Power ***
		this.addActivePowerCombinerListener();
	}

	/**
	 * Adds listeners to the low and high word channels of Apparent Power
	 * to calculate and update the combined 32-bit APPARENT_POWER channel.
	 */
	private void addApparentPowerCombinerListener() {
		// (unverändert zur letzten Version)
		Channel<Integer> lowWordChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER_LOW_WORD);
		Channel<Integer> highWordChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER_HIGH_WORD);
		Channel<Integer> apparentPowerChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER);
		Runnable updateApparentPower = () -> {
			Optional<Integer> lowOpt = lowWordChannel.value().asOptional();
			Optional<Integer> highOpt = highWordChannel.value().asOptional();
			if (lowOpt.isPresent() && highOpt.isPresent()) {
				try {
					int high = highOpt.get();
					int low = lowOpt.get();
					Integer combinedValue = (high << 16) | (low & 0xFFFF);
					apparentPowerChannel.setNextValue(combinedValue);
				} catch (Exception e) {
					log.error("Exception while combining apparent power words: H={}, L={}. Error: {}",
							highOpt.orElse(null), lowOpt.orElse(null), e.getMessage(), e);
					apparentPowerChannel.setNextValue(null);
				}
			} else { apparentPowerChannel.setNextValue(null); }
		};
		lowWordChannel.onUpdate(value -> updateApparentPower.run());
		highWordChannel.onUpdate(value -> updateApparentPower.run());
		updateApparentPower.run();
	}

	/**
	 * Adds listeners to the low and high word channels of Active Power
	 * to calculate and update the combined 32-bit ACTIVE_POWER channel.
	 */
	private void addActivePowerCombinerListener() {
		Channel<Integer> lowWordChannel = this.channel(DeyeSunHybrid.ChannelId.ACTIVE_POWER_LOW_WORD);
		Channel<Integer> highWordChannel = this.channel(DeyeSunHybrid.ChannelId.ACTIVE_POWER_HIGH_WORD);
		// Ziel ist der Standard-OpenEMS-Kanal für Wirkleistung
		Channel<Integer> activePowerChannel = this.channel(SymmetricEss.ChannelId.ACTIVE_POWER);

		Runnable updateActivePower = () -> {
			Optional<Integer> lowOpt = lowWordChannel.value().asOptional();
			Optional<Integer> highOpt = highWordChannel.value().asOptional();

			if (lowOpt.isPresent() && highOpt.isPresent()) {
				try {
					// Kombiniere High und Low Word zu einem 32-Bit Integer
					// Einheit ist W (siehe Doku Reg 636/694)
					int high = highOpt.get();
					int low = lowOpt.get();
					Integer combinedValue = (high << 16) | (low & 0xFFFF);
					activePowerChannel.setNextValue(combinedValue);
				} catch (Exception e) {
					log.error("Exception while combining active power words: H={}, L={}. Error: {}",
							highOpt.orElse(null), lowOpt.orElse(null), e.getMessage(), e);
					activePowerChannel.setNextValue(null); // Setze auf undefiniert
				}
			} else {
				activePowerChannel.setNextValue(null);
			}
		};

		lowWordChannel.onUpdate(value -> updateActivePower.run());
		highWordChannel.onUpdate(value -> updateActivePower.run());

		// Initialen Wert setzen
		updateActivePower.run();
	}


	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		// (unverändert)
		if (this.config.readOnlyMode()) { return; }
		double nominalPower = this.config.nominalPowerW();
		if (nominalPower <= 0) { log.warn("Nominal Power is not configured correctly ({}W). Cannot calculate percentage setpoints.", nominalPower); return; }
		double divisor = nominalPower / (100.0 * PERCENT_SCALING_FACTOR);
		int activePowerPercentScaled = (int) Math.round(activePower / divisor);
		int reactivePowerPercentScaled = (int) Math.round(reactivePower / divisor);
		int maxActivePercentScaled = 1200; int minActivePercentScaled = -1200;
		int maxReactivePercentScaled = 436; int minReactivePercentScaled = -436;
		activePowerPercentScaled = Math.max(minActivePercentScaled, Math.min(maxActivePercentScaled, activePowerPercentScaled));
		reactivePowerPercentScaled = Math.max(minReactivePercentScaled, Math.min(maxReactivePercentScaled, reactivePowerPercentScaled));
		IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
		setActivePowerChannel.setNextWriteValue(activePowerPercentScaled);
		IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);
		setReactivePowerChannel.setNextWriteValue(reactivePowerPercentScaled);
	}

	@Override
	public String getModbusBridgeId() {
		return this.config != null ? this.config.modbus_id() : "";
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this,
				// FC3: Read Registers
				new FC3ReadRegistersTask(3, Priority.LOW, // address 3
						m(DeyeSunHybrid.ChannelId.SERIAL_NUMBER, new StringWordElement(3, 5))),
				new FC3ReadRegistersTask(212, Priority.HIGH, // address 212
						m(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT, new SignedWordElement(212)),
						m(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT, new SignedWordElement(213)),
						new DummyRegisterElement(214),
						m(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW, new SignedWordElement(215))),
				new FC3ReadRegistersTask(500, Priority.LOW, // address 500
						m(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE, new UnsignedWordElement(500))),
				new FC3ReadRegistersTask(588, Priority.HIGH, // address 588
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(588))),

				// *** Task optimiert: Liest Block 636-637 ***
				new FC3ReadRegistersTask(636, Priority.HIGH, // Startadresse 636, Länge 2
						m(DeyeSunHybrid.ChannelId.ACTIVE_POWER_LOW_WORD, new SignedWordElement(636)), // P_Low
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER_LOW_WORD, new SignedWordElement(637))),// S_Low

				// *** Task optimiert: Liest Block 694-695 ***
				new FC3ReadRegistersTask(694, Priority.HIGH, // Startadresse 694, Länge 2
						m(DeyeSunHybrid.ChannelId.ACTIVE_POWER_HIGH_WORD, new SignedWordElement(694)), // P_High
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER_HIGH_WORD, new SignedWordElement(695))),// S_High

				// FC16: Write Registers
				new FC16WriteRegistersTask(1111, // address 1111
						m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(1111))),
				new FC16WriteRegistersTask(1118, // address 1118
						m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(1118)))
		);
	}

	@Override
	public String debugLog() {
		// Debug Log zeigt jetzt den kombinierten Wert für S an, P wird noch vom Listener gesetzt
		String soc = this.getSoc().asOptional().map(s -> s + "%").orElse("N/A");
		// ACTIVE_POWER wird jetzt durch den Listener aus Low/High gesetzt
		String activePower = this.getActivePower().asOptional().map(p -> p + "W").orElse("N/A");
		String origCharge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).value().asOptional()
				.map(String::valueOf).orElse("N/A");
		String origDischarge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).value()
				.asOptional().map(String::valueOf).orElse("N/A");
		String finalCharge = this.getAllowedChargePower().asOptional().map(String::valueOf).orElse("N/A");
		String finalDischarge = this.getAllowedDischargePower().asOptional().map(String::valueOf).orElse("N/A");
		String voltage = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).value().asOptional()
				.map(v -> String.format("%.1fV", v)).orElse("N/A");
		String apparentPower = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER).value().asOptional()
				.map(s -> s + "VA").orElse("N/A");
		String nextWriteActivePercent = "N/A";
		String nextWriteReactivePercent = "N/A";
		try {
			IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
			nextWriteActivePercent = setActivePowerChannel.getNextWriteValue().map(String::valueOf).orElse("N/A");
			IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);
			nextWriteReactivePercent = setReactivePowerChannel.getNextWriteValue().map(String::valueOf).orElse("N/A");
		} catch (ClassCastException e) {
			log.warn("Unable to cast SET_ACTIVE/REACTIVE_POWER to IntegerWriteChannel in debugLog", e);
		}
		return "SoC:" + soc + "|L:" + activePower + "|S:" + apparentPower
				+ "|V:" + voltage + "|Allowed(Orig):[Chg:" + origCharge + ";Dschg:"
				+ origDischarge + "]" + "|Allowed(Final):[Chg:" + finalCharge + ";Dschg:" + finalDischarge + "] W"
				+ "|NextWrite(0.1%):[P:" + nextWriteActivePercent + ";Q:" + nextWriteReactivePercent + "]";
	}

	@Override
	public void handleEvent(Event event) {
		// (unverändert)
		if (!this.isEnabled()) { return; }
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.calculateAndUpdatePowerLimits();
			this.applyPowerLimitOnOvertemperatureError();
			this.calculateEnergy();
			break;
		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			this.defineWorkState();
			break;
		}
	}

	private LocalDateTime lastDefineWorkState = null;

	private void defineWorkState() {
		// (unverändert)
		var now = LocalDateTime.now();
		if (this.lastDefineWorkState == null || now.minusMinutes(1).isAfter(this.lastDefineWorkState)) {
			this.lastDefineWorkState = now;
			if (this.config != null && this.config.readOnlyMode()) { return; }
			try {
				IntegerWriteChannel setWorkStateChannel = this.channel(DeyeSunHybrid.ChannelId.SET_WORK_STATE);
				int valueToWrite = SetWorkState.START.ordinal();
				log.debug("Periodically setting WorkState to [" + valueToWrite + "] (START.ordinal())");
				setWorkStateChannel.setNextWriteValue(valueToWrite);
			} catch (OpenemsNamedException e) {
				log.error("Unable to get Channel SET_WORK_STATE: " + e.getMessage());
			}
		}
	}


	private void calculateAndUpdatePowerLimits() {
		// (unverändert)
		try {
			IntegerReadChannel batteryVoltageRawChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW);
			IntegerReadChannel chargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT);
			IntegerReadChannel dischargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT);
			Channel<Float> batteryVoltageScaledChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE);
			Channel<Integer> originalAllowedChargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
			Channel<Integer> originalAllowedDischargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
			Optional<Integer> batteryVoltageRawOpt = batteryVoltageRawChannel.value().asOptional();
			Optional<Integer> chargeCurrentLimitOpt = chargeCurrentLimitChannel.value().asOptional();
			Optional<Integer> dischargeCurrentLimitOpt = dischargeCurrentLimitChannel.value().asOptional();
			if (batteryVoltageRawOpt.isPresent() && chargeCurrentLimitOpt.isPresent()
					&& dischargeCurrentLimitOpt.isPresent()) {
				float voltage = batteryVoltageRawOpt.get() / 100.0f;
				batteryVoltageScaledChannel.setNextValue(voltage);
				int chargeCurrentRaw = chargeCurrentLimitOpt.get();
				int dischargeCurrentRaw = dischargeCurrentLimitOpt.get();
				int calculatedChargePowerMagnitude = Math.round(voltage * Math.abs(chargeCurrentRaw));
				int calculatedDischargePowerLimit = Math.round(voltage * Math.abs(dischargeCurrentRaw));
				originalAllowedChargePowerChannel.setNextValue(-calculatedChargePowerMagnitude);
				originalAllowedDischargePowerChannel.setNextValue(calculatedDischargePowerLimit);
			} else {
				batteryVoltageScaledChannel.setNextValue(null);
				originalAllowedChargePowerChannel.setNextValue(0);
				originalAllowedDischargePowerChannel.setNextValue(0);
			}
		} catch (Exception e) {
			log.error("Unexpected error during power limit calculation: " + e.getMessage(), e);
			try {
				this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).setNextValue(null);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).setNextValue(0);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).setNextValue(0);
			} catch (Exception ex) {
				log.error("Error setting default values after exception in calculateAndUpdatePowerLimits: " + ex.getMessage(), ex);
			}
		}
	}


	@Override
	public Power getPower() {
		// (unverändert)
		return this.power;
	}

	@Override
	public boolean isManaged() {
		// (unverändert)
		return this.config != null && !this.config.readOnlyMode();
	}

	@Override
	public int getPowerPrecision() {
		// (unverändert)
		double nominalPower = this.config != null ? this.config.nominalPowerW() : 0;
		if (nominalPower <= 0) { return 1; }
		int precision = (int) Math.max(1, Math.round(nominalPower * 0.001));
		return precision;
	}

	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		// (unverändert)
		if (this.config == null) { return Power.NO_CONSTRAINTS; }
		if (this.config.readOnlyMode()) {
			return new Constraint[] {
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS, 0),
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.REACTIVE, Relationship.EQUALS, 0) };
		}
		int maxActivePower = this.config.maxApparentPowerVA();
		int minReactive = this.config.minReactivePowerVar();
		int maxReactive = this.config.maxReactivePowerVar();
		return new Constraint[] {
				this.createPowerConstraint("Deye Min Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS, -maxActivePower),
				this.createPowerConstraint("Deye Max Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.LESS_OR_EQUALS, maxActivePower),
				this.createPowerConstraint("Deye Min Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.GREATER_OR_EQUALS, minReactive),
				this.createPowerConstraint("Deye Max Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.LESS_OR_EQUALS, maxReactive) };
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		// (unverändert)
		return new ModbusSlaveTable(
				OpenemsComponent.getModbusSlaveNatureTable(accessMode),
				SymmetricEss.getModbusSlaveNatureTable(accessMode),
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode),
				ModbusSlaveNatureTable.of(DeyeSunHybrid.class, accessMode, 100)
						.build()
		);
	}

	// --- Log-Methoden --- (unverändert)
	@Override protected void logInfo(Logger log, String message) { super.logInfo(log, message); }
	@Override protected void logWarn(Logger log, String message) { super.logWarn(log, message); }
	@Override protected void logError(Logger log, String message) { super.logError(log, message); }

	private void applyPowerLimitOnOvertemperatureError() {
		// (unverändert)
		if (this.config != null && this.config.powerLimitOnOvertemperatureW() > 0) {
			StateChannel errorChannel = this.channel(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE);
			if (errorChannel.value().orElse(false)) {
				int limit = Math.abs(this.config.powerLimitOnOvertemperatureW());
				this.logWarn(this.log, String.format(
						"[%s] Overtemperature constraint active! OVERRIDING AllowedCharge to %d W / Discharge to %d W using configured limit.",
						this.id(), -limit, limit));
				this._setAllowedChargePower(-limit);
				this._setAllowedDischargePower(limit);
			}
		}
	}

	@Override
	public Timedata getTimedata() {
		// (unverändert)
		return this.timedata;
	}

	private void calculateEnergy() {
		// (unverändert - DC Teil braucht noch Korrektur)
		Optional<Integer> acActivePowerOpt = this.getActivePowerChannel().value().asOptional();
		if (acActivePowerOpt.isPresent()) {
			int acPower = acActivePowerOpt.get();
			if (acPower > 0) { this.calculateAcChargeEnergy.update(0); this.calculateAcDischargeEnergy.update(acPower);
			} else { this.calculateAcChargeEnergy.update(-acPower); this.calculateAcDischargeEnergy.update(0); }
		} else { this.calculateAcChargeEnergy.update(null); this.calculateAcDischargeEnergy.update(null); }
		Optional<Integer> dcPowerOpt = Optional.empty(); // Placeholder
		try {
			// Example: dcPowerOpt = this.channel(HybridEss.ChannelId.DC_DISCHARGE_POWER).value().asOptional();
			if (dcPowerOpt.isPresent()) {
				int dcPower = dcPowerOpt.get();
				if (dcPower > 0) { this.calculateDcChargeEnergy.update(0); this.calculateDcDischargeEnergy.update(dcPower);
				} else { this.calculateDcChargeEnergy.update(-dcPower); this.calculateDcDischargeEnergy.update(0); }
			} else { this.calculateDcChargeEnergy.update(null); this.calculateDcDischargeEnergy.update(null); }
		} catch (Exception e) {
			this.calculateDcChargeEnergy.update(null); this.calculateDcDischargeEnergy.update(null);
		}
	}

	private void addStateChannelTrigger(io.openems.edge.common.channel.ChannelId targetChannelId,
			io.openems.edge.common.channel.ChannelId[] sourceChannelIds) {
		// (unverändert)
		StateChannel targetChannel = this.channel(targetChannelId);
		List<Channel<Boolean>> sourceChannels = Arrays.stream(sourceChannelIds).map(id -> {
			try {
				@SuppressWarnings("unchecked") Channel<Boolean> channel = (Channel<Boolean>) this.channel(id);
				return channel;
			} catch (IllegalArgumentException | ClassCastException e) {
				this.logWarn(log, "Source channel [" + id.id() + "] problem for target [" + targetChannelId.id() + "]: Not found or not Boolean. Trigger might not work correctly. " + e.getMessage());
				return null;
			}
		}).filter(java.util.Objects::nonNull).collect(Collectors.toList());
		if (sourceChannels.isEmpty()) {
			this.logWarn(log, "No valid source channels found for target [" + targetChannelId.id() + "]. Setting to false.");
			targetChannel.setNextValue(false); return;
		}
		for (Channel<Boolean> sourceChannel : sourceChannels) {
			sourceChannel.onUpdate(value -> {
				boolean isAnySourceTrue = sourceChannels.stream().anyMatch(ch -> ch.value().orElse(false));
				targetChannel.setNextValue(isAnySourceTrue);
			});
		}
		boolean isInitiallyAnySourceTrue = sourceChannels.stream().anyMatch(ch -> ch.value().orElse(false));
		targetChannel.setNextValue(isInitiallyAnySourceTrue);
	}

	@Override
	public Integer getUnitId() {
		// (unverändert)
		return super.getUnitId();
	}

} // Ende der Klasse