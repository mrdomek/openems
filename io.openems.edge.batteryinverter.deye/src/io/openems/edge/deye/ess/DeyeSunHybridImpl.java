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

	private static final int POWER_LIMIT_HYSTERESIS = 500; // 500 W Hysterese - Optional konfigurierbar machen?
	private final Logger log = LoggerFactory.getLogger(DeyeSunHybridImpl.class);

	private static final double PERCENT_SCALING_FACTOR = 10.0; // Faktor für 0.1%

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
				HybridEss.ChannelId.values(), // Beibehalten, auch wenn Service auskommentiert
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

		// === Listener für Leistungslimits ===
		IntegerReadChannel calculatedChargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
		Channel<Integer> finalChargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER);
		calculatedChargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedOpt = value.asOptional();
			Optional<Integer> currentFinalOpt = finalChargeLimitChannel.value().asOptional();
			final int finalValue;
			if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) {
				finalValue = 0;
			} else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) {
				finalValue = calculatedOpt.get();
			} else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) {
				finalValue = currentFinalOpt.get();
			} else {
				int calculatedMagnitude = Math.abs(calculatedOpt.get());
				int currentFinalMagnitude = Math.abs(currentFinalOpt.get());
				int finalMagnitude = Math.max(calculatedMagnitude, currentFinalMagnitude - POWER_LIMIT_HYSTERESIS);
				finalValue = -finalMagnitude;
			}
			finalChargeLimitChannel.setNextValue(Math.min(0, finalValue));
		});

		IntegerReadChannel calculatedDischargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
		Channel<Integer> finalDischargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER);
		calculatedDischargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedOpt = value.asOptional();
			Optional<Integer> currentFinalOpt = finalDischargeLimitChannel.value().asOptional();
			final int finalValue;
			if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) {
				finalValue = 0;
			} else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) {
				finalValue = calculatedOpt.get();
			} else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) {
				finalValue = currentFinalOpt.get();
			} else {
				finalValue = Math.min(calculatedOpt.get(), currentFinalOpt.get() + POWER_LIMIT_HYSTERESIS);
			}
			finalDischargeLimitChannel.setNextValue(Math.max(0, finalValue));
		});
		// === Ende Listener Leistungslimits ===


		// === Trigger für zusammengefasste StateChannels ===
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.SYSTEM_ERROR, DeyeSunHybrid.SystemErrorChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.INSUFFICIENT_GRID_PARAMTERS,
				DeyeSunHybrid.InsufficientGridParametersChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE,
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values());
		// === Ende Trigger Registrierung ===
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
		if (this.config.readOnlyMode()) {
			return;
		}

		double nominalPower = this.config.nominalPowerW();
		if (nominalPower <= 0) {
			log.warn("Nominal Power is not configured correctly ({}W). Cannot calculate percentage setpoints.", nominalPower);
			return;
		}

		double divisor = nominalPower / (100.0 * PERCENT_SCALING_FACTOR);
		int activePowerPercentScaled = (int) Math.round(activePower / divisor);
		int reactivePowerPercentScaled = (int) Math.round(reactivePower / divisor);

		int maxActivePercentScaled = 1200;
		int minActivePercentScaled = -1200;
		int maxReactivePercentScaled = 436;
		int minReactivePercentScaled = -436;

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
						m(DeyeSunHybrid.ChannelId.SERIAL_NUMBER, new StringWordElement(3, 5))), // Liest 5 Register (10 Bytes) von Adresse 3 [Source 13]
				new FC3ReadRegistersTask(212, Priority.HIGH, // address 212
						m(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT, new SignedWordElement(212)), // Unit: 1A [Source 37]
						m(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT, new SignedWordElement(213)), // Unit: 1A [Source 37]
						new DummyRegisterElement(214), // Li-bat SOC (auch in 588 gelesen)
						m(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW, new SignedWordElement(215))), // Unit: 0.01V [Source 37]
				new FC3ReadRegistersTask(500, Priority.LOW, // address 500
						m(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE, new UnsignedWordElement(500))), // 0-5 [Source 63]
				new FC3ReadRegistersTask(588, Priority.HIGH, // address 588
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(588))), // Unit: 1% [Source 71]
				new FC3ReadRegistersTask(620, Priority.LOW, // address 620
						// Unit: 100VA (S16)? [Source 73] -> Skalierung nötig?
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER, new SignedWordElement(620))), // S16 oder U16? Doku widersprüchlich, S16 wahrscheinlicher
				new FC3ReadRegistersTask(636, Priority.HIGH, // address 636
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(636))), // Unit: 1W (S16) [Source 75]

				// FC16: Write Registers
				new FC16WriteRegistersTask(1111, // address 1111
						m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(1111))), // Schreibt skalierten %-Wert (0.1%) [Source 89]
				new FC16WriteRegistersTask(1118, // address 1118
						m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(1118))) // Schreibt skalierten %-Wert (0.1% oder PF) [Source 91]
		);
	}

	@Override
	public String debugLog() {
		String soc = this.getSoc().asOptional().map(s -> s + "%").orElse("N/A");
		String activePower = this.getActivePower().asOptional().map(p -> p + "W").orElse("N/A");
		String origCharge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).value().asOptional()
				.map(String::valueOf).orElse("N/A");
		String origDischarge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).value()
				.asOptional().map(String::valueOf).orElse("N/A");
		String finalCharge = this.getAllowedChargePower().asOptional().map(String::valueOf).orElse("N/A");
		String finalDischarge = this.getAllowedDischargePower().asOptional().map(String::valueOf).orElse("N/A");
		String voltage = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).value().asOptional()
				.map(v -> String.format("%.1fV", v)).orElse("N/A");

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

		return "SoC:" + soc + "|L:" + activePower + "|V:" + voltage + "|Allowed(Orig):[Chg:" + origCharge + ";Dschg:"
				+ origDischarge + "]" + "|Allowed(Final):[Chg:" + finalCharge + ";Dschg:" + finalDischarge + "] W"
				+ "|NextWrite(0.1%):[P:" + nextWriteActivePercent + ";Q:" + nextWriteReactivePercent + "]";
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
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
		var now = LocalDateTime.now();
		if (this.lastDefineWorkState == null || now.minusMinutes(1).isAfter(this.lastDefineWorkState)) {
			this.lastDefineWorkState = now;

			if (this.config != null && this.config.readOnlyMode()) {
				return;
			}

			try {
				IntegerWriteChannel setWorkStateChannel = this.channel(DeyeSunHybrid.ChannelId.SET_WORK_STATE);

				// Periodically send START command.
				// NOTE: Verify if this periodic sending is strictly required by the specific
				// Deye firmware/model or if it was a workaround for a specific issue.
				// According to Modbus documentation (Reg 80), 0=OFF, 1=ON. [Source 23]
				// Sending START.ordinal() (which is 0) periodically. This likely means sending OFF.
				// --> SHOULD THIS BE SetWorkState.START -> 1? Check Enum definition and device behavior!
				int valueToWrite = SetWorkState.START.ordinal(); // Currently 0

				log.debug("Periodically setting WorkState to [" + valueToWrite + "] (START.ordinal())");
				setWorkStateChannel.setNextWriteValue(valueToWrite);

			} catch (OpenemsNamedException e) {
				log.error("Unable to get Channel SET_WORK_STATE: " + e.getMessage());
			}
		}
	}

	private void calculateAndUpdatePowerLimits() {
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
				float voltage = batteryVoltageRawOpt.get() / 100.0f; // Unit: 0.01V [Source 37]
				batteryVoltageScaledChannel.setNextValue(voltage);

				int chargeCurrentRaw = chargeCurrentLimitOpt.get(); // Unit: 1A S16 [Source 37]
				int dischargeCurrentRaw = dischargeCurrentLimitOpt.get(); // Unit: 1A S16 [Source 37]

				int calculatedChargePowerMagnitude = Math.round(voltage * Math.abs(chargeCurrentRaw));
				int calculatedDischargePowerLimit = Math.round(voltage * Math.abs(dischargeCurrentRaw));

				originalAllowedChargePowerChannel.setNextValue(-calculatedChargePowerMagnitude);
				originalAllowedDischargePowerChannel.setNextValue(calculatedDischargePowerLimit);

			} else {
				batteryVoltageScaledChannel.setNextValue(null);
				originalAllowedChargePowerChannel.setNextValue(0);
				originalAllowedDischargePowerChannel.setNextValue(0);
			}
		// *** Fehler behoben: Unnötige/Unerreichbare catch-Blöcke entfernt ***
		} catch (Exception e) {
			// Catch general exceptions during calculation or channel access
			log.error("Unexpected error during power limit calculation: " + e.getMessage(), e);
			// Attempt to set defaults as a fallback
			try {
				this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).setNextValue(null);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).setNextValue(0);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).setNextValue(0);
			// *** Fehler behoben: Unnötigen/Unerreichbaren catch (OpenemsNamedException ex) entfernt ***
			} catch (Exception ex) {
				// Log error during fallback setting, falls channel() oder setNextValue() fehlschlägt
				log.error("Error setting default values after exception in calculateAndUpdatePowerLimits: " + ex.getMessage(), ex);
			}
		}
	}


	@Override
	public Power getPower() {
		return this.power;
	}

	@Override
	public boolean isManaged() {
		return this.config != null && !this.config.readOnlyMode();
	}

	@Override
	public int getPowerPrecision() {
		double nominalPower = this.config != null ? this.config.nominalPowerW() : 0;
		if (nominalPower <= 0) {
			return 1;
		}
		int precision = (int) Math.max(1, Math.round(nominalPower * 0.001)); // 0.1% = 0.001
		return precision;
	}

	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		if (this.config == null) {
			return Power.NO_CONSTRAINTS;
		}

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
		return new ModbusSlaveTable(
				OpenemsComponent.getModbusSlaveNatureTable(accessMode),
				SymmetricEss.getModbusSlaveNatureTable(accessMode),
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode),
				ModbusSlaveNatureTable.of(DeyeSunHybrid.class, accessMode, 100)
						.build()
		);
	}

	// --- Log-Methoden ---
	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	protected void logWarn(Logger log, String message) {
		super.logWarn(log, message);
	}

	@Override
	protected void logError(Logger log, String message) {
		super.logError(log, message);
	}

	private void applyPowerLimitOnOvertemperatureError() {
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
		return this.timedata;
	}

	private void calculateEnergy() {
		Optional<Integer> acActivePowerOpt = this.getActivePowerChannel().value().asOptional();

		if (acActivePowerOpt.isPresent()) {
			int acPower = acActivePowerOpt.get();
			if (acPower > 0) { // Discharging to AC
				this.calculateAcChargeEnergy.update(0);
				this.calculateAcDischargeEnergy.update(acPower);
			} else { // Charging from AC
				this.calculateAcChargeEnergy.update(-acPower);
				this.calculateAcDischargeEnergy.update(0);
			}
		} else {
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		}

		// DC Energy Calculation -> CURRENTLY INCORRECTLY USES AC POWER
		Optional<Integer> dcPowerOpt = Optional.empty(); // Placeholder
		try {
			// Example: dcPowerOpt = this.channel(HybridEss.ChannelId.DC_DISCHARGE_POWER).value().asOptional();
			if (dcPowerOpt.isPresent()) {
				int dcPower = dcPowerOpt.get();
				if (dcPower > 0) { // Discharging
					this.calculateDcChargeEnergy.update(0);
					this.calculateDcDischargeEnergy.update(dcPower);
				} else { // Charging
					this.calculateDcChargeEnergy.update(-dcPower);
					this.calculateDcDischargeEnergy.update(0);
				}
			} else {
				this.calculateDcChargeEnergy.update(null);
				this.calculateDcDischargeEnergy.update(null);
			}
		} catch (Exception e) {
			this.calculateDcChargeEnergy.update(null);
			this.calculateDcDischargeEnergy.update(null);
		}
	}

	private void addStateChannelTrigger(io.openems.edge.common.channel.ChannelId targetChannelId,
			io.openems.edge.common.channel.ChannelId[] sourceChannelIds) {
		StateChannel targetChannel = this.channel(targetChannelId);

		List<Channel<Boolean>> sourceChannels = Arrays.stream(sourceChannelIds).map(id -> {
			try {
				@SuppressWarnings("unchecked")
				Channel<Boolean> channel = (Channel<Boolean>) this.channel(id);
				return channel;
			} catch (IllegalArgumentException | ClassCastException e) { // Combined catch
				this.logWarn(log, "Source channel [" + id.id() + "] problem for target ["
						+ targetChannelId.id() + "]: Not found or not Boolean. Trigger might not work correctly. " + e.getMessage());
				return null;
			}
		}).filter(java.util.Objects::nonNull).collect(Collectors.toList());

		if (sourceChannels.isEmpty()) {
			this.logWarn(log, "No valid source channels found for target [" + targetChannelId.id() + "]. Setting to false.");
			targetChannel.setNextValue(false);
			return;
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
		return super.getUnitId();
	}

} // Ende der Klasse