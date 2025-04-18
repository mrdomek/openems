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
// import io.openems.edge.common.type.TypeUtils; // Currently unused import
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

/**
 * Implements the Deye SUN-...K-SG... Hybrid inverter series for OpenEMS Edge,
 * primarily used as an AC-coupled battery inverter in this configuration.
 *
 * <p>
 * Handles Modbus communication for reading status values (SoC, Power, Voltage,
 * Limits), calculating derived values, applying power setpoints, and managing
 * component lifecycle and error states.
 *
 * <p>
 * Note: Relies on specific Modbus register mappings for Deye hybrid inverters.
 * Assumes AC-coupled operation (no direct DC PV input) based on user context.
 * Error channel mappings (STATE_xxx) are placeholders and require specific
 * register/bit mapping based on Deye documentation.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Deye.BatteryInverter", // Component Factory-ID
		immediate = true, // Start component immediately
		configurationPolicy = ConfigurationPolicy.REQUIRE, // Require configuration
		service = { // Exported services/interfaces
				DeyeSunHybrid.class, // Specific interface for this implementation
				SymmetricEss.class, // Standard Symmetric ESS interface
				ManagedSymmetricEss.class, // Standard Managed Symmetric ESS interface
				// HybridEss.class, // NOT implemented as no direct DC PV is connected
				TimedataProvider.class, // Provides timeseries data
				OpenemsComponent.class, // Base OpenEMS component interface
				ModbusComponent.class, // Modbus component interface
				ModbusSlave.class, // Allows exposing data via Modbus slave
				EventHandler.class // Handles OSGi events
		})
@EventTopics({ // Subscribe to OSGi events
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, // For calculations after reading Modbus
		EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS // For actions before controller runs
})
public class DeyeSunHybridImpl extends AbstractOpenemsModbusComponent implements DeyeSunHybrid {

	private final Logger log = LoggerFactory.getLogger(DeyeSunHybridImpl.class);

	// Constants
	private static final int POWER_LIMIT_HYSTERESIS = 500; // Hysteresis for power limits in Watts
	private static final double PERCENT_SCALING_FACTOR = 10.0; // Modbus uses 0.1% units for power setpoints
	private static final float VOLTAGE_SCALING_FACTOR = 100.0f; // Modbus uses 0.01 V units for battery voltage
	private static final int MAX_ACTIVE_POWER_SETPOINT_SCALED = 1200; // 120.0%
	private static final int MIN_ACTIVE_POWER_SETPOINT_SCALED = -1200; // -120.0%
	private static final int MAX_REACTIVE_POWER_SETPOINT_SCALED = 436; // 43.6% (needs verification against docs)
	private static final int MIN_REACTIVE_POWER_SETPOINT_SCALED = -436; // -43.6% (needs verification against docs)

	// OSGi References
	@Reference
	protected ComponentManager componentManager; // Reference to the ComponentManager
	@Reference
	private Power power; // Reference to the Power component for constraint management
	@Reference
	private ConfigurationAdmin cm; // Reference to ConfigurationAdmin for OSGi config tasks
	@Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
	private volatile Timedata timedata = null; // Optional reference to Timedata service

	// Component Configuration
	private Config config;

	// Energy calculation helpers
	private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
	private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this,
			SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
	// DC Energy calculation removed as HybridEss is not implemented for this setup

	/**
	 * Default constructor. Initializes channels based on implemented interfaces.
	 */
	public DeyeSunHybridImpl() {
		super(// Base OpenEMS Component channels
				OpenemsComponent.ChannelId.values(), //
				// Modbus Component channels
				ModbusComponent.ChannelId.values(), //
				// Symmetric ESS channels
				SymmetricEss.ChannelId.values(), //
				// Managed Symmetric ESS channels
				ManagedSymmetricEss.ChannelId.values(), //
				// NOT implementing HybridEss channels
				// HybridEss.ChannelId.values(), //
				// Specific DeyeSunHybrid channels
				DeyeSunHybrid.ChannelId.values(), //
				// Placeholder Error/Warning channels (need mapping!)
				DeyeSunHybrid.SystemErrorChannelId.values(), //
				DeyeSunHybrid.InsufficientGridParametersChannelId.values(), //
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		// Call super.activate() first
		if (super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm,
				"Modbus", config.modbus_id())) {
			// Activation failed or requires restart due to config change, return early
			return;
		}

		// Initialize component specific settings from config
		this.initializeComponentSettings(config);

		// Setup listeners and triggers
		this.setupPowerLimitListeners();
		this.setupPowerCombinerListeners();
		this.setupStateChannelTriggers();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate(); // Calls the parent deactivate logic
	}

	/**
	 * Initializes component settings based on the configuration.
	 *
	 * @param config the component configuration
	 */
	private void initializeComponentSettings(Config config) {
		// Use configured Max Apparent Power
		this._setMaxApparentPower(config.maxApparentPowerVA());

		// Use configured Battery Capacity (or 0 if not set)
		if (config.netCapacityWh() > 0) {
			this.logInfo(this.log, "Using configured Net Capacity [" + config.netCapacityWh() + " Wh].");
			this._setCapacity(config.netCapacityWh());
		} else {
			this.logWarn(this.log,
					"Configured Net Capacity is 0 or not set. Battery visualization and some statistics might be inaccurate.");
			this._setCapacity(0); // Set capacity to 0 if not configured
		}
	}

	/**
	 * Sets up listeners for handling power limit calculations and hysteresis.
	 */
	private void setupPowerLimitListeners() {
		// Listener for charge power limit with hysteresis
		IntegerReadChannel calculatedChargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
		Channel<Integer> finalChargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER);

		calculatedChargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedLimitOpt = value.asOptional();
			Optional<Integer> currentFinalLimitOpt = finalChargeLimitChannel.value().asOptional();
			final int finalValue;

			if (!calculatedLimitOpt.isPresent() && !currentFinalLimitOpt.isPresent()) {
				// Both values missing -> final limit is 0
				finalValue = 0;
			} else if (calculatedLimitOpt.isPresent() && !currentFinalLimitOpt.isPresent()) {
				// Only calculated value present -> use it directly
				finalValue = calculatedLimitOpt.get();
			} else if (!calculatedLimitOpt.isPresent() && currentFinalLimitOpt.isPresent()) {
				// Only current final value present -> keep it
				finalValue = currentFinalLimitOpt.get();
			} else {
				// Both values present -> apply hysteresis
				int calculatedMagnitude = Math.abs(calculatedLimitOpt.get());
				int currentFinalMagnitude = Math.abs(currentFinalLimitOpt.get());
				// Hysteresis: Allow decrease immediately, delay increase
				int finalMagnitude = Math.max(calculatedMagnitude, currentFinalMagnitude - POWER_LIMIT_HYSTERESIS);
				finalValue = -finalMagnitude; // Charge power is negative
			}
			// Ensure final value is not positive
			finalChargeLimitChannel.setNextValue(Math.min(0, finalValue));
		});

		// Listener for discharge power limit with hysteresis
		IntegerReadChannel calculatedDischargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
		Channel<Integer> finalDischargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER);

		calculatedDischargeLimitChannel.onUpdate((value) -> {
			Optional<Integer> calculatedLimitOpt = value.asOptional();
			Optional<Integer> currentFinalLimitOpt = finalDischargeLimitChannel.value().asOptional();
			final int finalValue;

			if (!calculatedLimitOpt.isPresent() && !currentFinalLimitOpt.isPresent()) {
				// Both values missing -> final limit is 0
				finalValue = 0;
			} else if (calculatedLimitOpt.isPresent() && !currentFinalLimitOpt.isPresent()) {
				// Only calculated value present -> use it directly
				finalValue = calculatedLimitOpt.get();
			} else if (!calculatedLimitOpt.isPresent() && currentFinalLimitOpt.isPresent()) {
				// Only current final value present -> keep it
				finalValue = currentFinalLimitOpt.get();
			} else {
				// Both values present -> apply hysteresis
				// Hysteresis: Allow decrease immediately, delay increase
				finalValue = Math.min(calculatedLimitOpt.get(), currentFinalLimitOpt.get() + POWER_LIMIT_HYSTERESIS);
			}
			// Ensure final value is not negative
			finalDischargeLimitChannel.setNextValue(Math.max(0, finalValue));
		});
	}

	/**
	 * Sets up listeners to combine High/Low Word registers for 32-bit power values.
	 */
	private void setupPowerCombinerListeners() {
		this.addApparentPowerCombinerListener();
		this.addActivePowerCombinerListener();
	}

	/**
	 * Sets up triggers to aggregate detailed state channels into summary channels.
	 */
	private void setupStateChannelTriggers() {
		// TODO: Replace placeholder source channels (e.g., SystemErrorChannelId.STATE_149)
		// with actual channels mapped to Modbus bits once identified!
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.SYSTEM_ERROR, DeyeSunHybrid.SystemErrorChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.INSUFFICIENT_GRID_PARAMTERS,
				DeyeSunHybrid.InsufficientGridParametersChannelId.values());
		this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE,
				DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values());
	}

	/**
	 * Adds listeners to the low and high word channels of Apparent Power
	 * to calculate and update the combined 32-bit APPARENT_POWER channel.
	 */
	private void addApparentPowerCombinerListener() {
		// Get references to the relevant channels
		Channel<Integer> lowWordChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER_LOW_WORD);
		Channel<Integer> highWordChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER_HIGH_WORD);
		Channel<Integer> apparentPowerChannel = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER);

		// Define the logic to combine the words
		Runnable updateApparentPower = () -> {
			Optional<Integer> lowOpt = lowWordChannel.value().asOptional();
			Optional<Integer> highOpt = highWordChannel.value().asOptional();

			if (lowOpt.isPresent() && highOpt.isPresent()) {
				try {
					// Combine High and Low Word (assuming SignedWordElement provides correct int)
					int high = highOpt.get();
					int low = lowOpt.get();
					// Standard 32-bit combination from two 16-bit words
					Integer combinedValue = (high << 16) | (low & 0xFFFF);
					apparentPowerChannel.setNextValue(combinedValue); // Update the target channel
				} catch (Exception e) {
					// Log error if combination fails
					log.error("Exception while combining apparent power words: H={}, L={}. Error: {}",
							highOpt.orElse(null), lowOpt.orElse(null), e.getMessage(), e);
					apparentPowerChannel.setNextValue(null); // Set target channel to undefined
				}
			} else {
				// If either word is missing, set target channel to undefined
				apparentPowerChannel.setNextValue(null);
			}
		};

		// Register the update logic to run whenever the low or high word changes
		lowWordChannel.onUpdate(value -> updateApparentPower.run());
		highWordChannel.onUpdate(value -> updateApparentPower.run());

		// Run the logic once initially to set the starting value
		updateApparentPower.run();
	}

	/**
	 * Adds listeners to the low and high word channels of Active Power
	 * to calculate and update the combined 32-bit ACTIVE_POWER channel
	 * (using the standard SymmetricEss channel).
	 */
	private void addActivePowerCombinerListener() {
		// Get references to the relevant channels
		Channel<Integer> lowWordChannel = this.channel(DeyeSunHybrid.ChannelId.ACTIVE_POWER_LOW_WORD);
		Channel<Integer> highWordChannel = this.channel(DeyeSunHybrid.ChannelId.ACTIVE_POWER_HIGH_WORD);
		// Target is the standard OpenEMS channel for Active Power
		Channel<Integer> activePowerChannel = this.channel(SymmetricEss.ChannelId.ACTIVE_POWER);

		// Define the logic to combine the words
		Runnable updateActivePower = () -> {
			Optional<Integer> lowOpt = lowWordChannel.value().asOptional();
			Optional<Integer> highOpt = highWordChannel.value().asOptional();

			if (lowOpt.isPresent() && highOpt.isPresent()) {
				try {
					// Combine High and Low Word (assuming SignedWordElement provides correct int)
					// Unit is W (see Deye doc Reg 636/694)
					int high = highOpt.get();
					int low = lowOpt.get();
					// Standard 32-bit combination from two 16-bit words
					Integer combinedValue = (high << 16) | (low & 0xFFFF);
					activePowerChannel.setNextValue(combinedValue); // Update the target channel
				} catch (Exception e) {
					// Log error if combination fails
					log.error("Exception while combining active power words: H={}, L={}. Error: {}",
							highOpt.orElse(null), lowOpt.orElse(null), e.getMessage(), e);
					activePowerChannel.setNextValue(null); // Set target channel to undefined
				}
			} else {
				// If either word is missing, set target channel to undefined
				activePowerChannel.setNextValue(null);
			}
		};

		// Register the update logic to run whenever the low or high word changes
		lowWordChannel.onUpdate(value -> updateActivePower.run());
		highWordChannel.onUpdate(value -> updateActivePower.run());

		// Run the logic once initially to set the starting value
		updateActivePower.run();
	}

	/**
	 * Sets the Modbus bridge reference. Called by OSGi.
	 * Needs to be static for greedy reference policy.
	 */
	@Override
	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	/**
	 * Applies the calculated active and reactive power setpoints to the inverter.
	 * Converts absolute power values (W, var) to scaled percentage values (0.1%)
	 * required by the Deye Modbus registers.
	 *
	 * @param activePower   Active power setpoint in W (negative for discharge, positive for charge)
	 * @param reactivePower Reactive power setpoint in var
	 * @throws OpenemsNamedException if applying power fails
	 */
	@Override
	public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
		// Do nothing if in read-only mode
		if (this.config.readOnlyMode()) {
			return;
		}

		// Get nominal power for scaling, check if configured
		double nominalPower = this.config.nominalPowerW();
		if (nominalPower <= 0) {
			log.warn("Nominal Power is not configured correctly ({}W). Cannot calculate percentage setpoints.",
					nominalPower);
			return; // Cannot scale without nominal power
		}

		// Calculate divisor for scaling to 0.1% units
		// divisor = nominalPower / 100 (for %) / 10 (for 0.1%)
		double divisor = nominalPower / (100.0 * PERCENT_SCALING_FACTOR);

		// Calculate scaled setpoints
		int activePowerPercentScaled = (int) Math.round(activePower / divisor);
		int reactivePowerPercentScaled = (int) Math.round(reactivePower / divisor);

		// Clamp scaled setpoints to allowed Modbus register range (e.g., -120.0% to +120.0%)
		activePowerPercentScaled = Math.max(MIN_ACTIVE_POWER_SETPOINT_SCALED,
				Math.min(MAX_ACTIVE_POWER_SETPOINT_SCALED, activePowerPercentScaled));
		reactivePowerPercentScaled = Math.max(MIN_REACTIVE_POWER_SETPOINT_SCALED,
				Math.min(MAX_REACTIVE_POWER_SETPOINT_SCALED, reactivePowerPercentScaled));

		// Get write channels for setpoints
		IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
		IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);

		// Set the next values to be written via Modbus
		setActivePowerChannel.setNextWriteValue(activePowerPercentScaled);
		setReactivePowerChannel.setNextWriteValue(reactivePowerPercentScaled);
	}

	/**
	 * Gets the configured Modbus Bridge ID.
	 *
	 * @return the Modbus Bridge Component-ID (e.g., "modbus0")
	 */
	@Override
	public String getModbusBridgeId() {
		return this.config != null ? this.config.modbus_id() : "";
	}

	/**
	 * Defines the Modbus protocol structure for the Deye inverter.
	 * Maps Modbus registers to OpenEMS channels.
	 *
	 * @return the defined ModbusProtocol
	 */
	@Override
	protected ModbusProtocol defineModbusProtocol() {
		// Note: Register addresses and types based on Deye documentation/reverse engineering.
		// Verify against specific Deye model and firmware version.
		return new ModbusProtocol(this,
				// FC3: Read Holding Registers tasks

				// Read Serial Number (Registers 3-7, 5 words total)
				new FC3ReadRegistersTask(3, Priority.LOW, // Read once or rarely
						m(DeyeSunHybrid.ChannelId.SERIAL_NUMBER, new StringWordElement(3, 5))),

				// Read Battery Limits and Voltage (Registers 212-215)
				new FC3ReadRegistersTask(212, Priority.HIGH, // Read frequently
						// Raw current limits (Unit: A, Signed?)
						m(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT, new SignedWordElement(212)),
						m(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT, new SignedWordElement(213)),
						// Register 214: Unknown or unused, skip it
						new DummyRegisterElement(214),
						// Raw battery voltage (Unit: 0.01V, Signed)
						m(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW, new SignedWordElement(215))),

				// Read Inverter Run State (Register 500)
				new FC3ReadRegistersTask(500, Priority.LOW, // Read occasionally
						// See Deye docs for state meanings
						m(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE, new UnsignedWordElement(500))),

				// Read State of Charge (Register 588)
				new FC3ReadRegistersTask(588, Priority.HIGH, // Read frequently
						// Unit: %, Unsigned
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(588))),

				// Read Block 636-637: Low words for Active/Apparent Power (Optimized Read)
				new FC3ReadRegistersTask(636, Priority.HIGH, // Start address 636, read 2 registers
						// Unit: W, Signed
						m(DeyeSunHybrid.ChannelId.ACTIVE_POWER_LOW_WORD, new SignedWordElement(636)),
						// Unit: VA, Signed
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER_LOW_WORD, new SignedWordElement(637))),

				// Read Block 694-695: High words for Active/Apparent Power (Optimized Read)
				new FC3ReadRegistersTask(694, Priority.HIGH, // Start address 694, read 2 registers
						// Unit: W, Signed
						m(DeyeSunHybrid.ChannelId.ACTIVE_POWER_HIGH_WORD, new SignedWordElement(694)),
						// Unit: VA, Signed
						m(DeyeSunHybrid.ChannelId.APPARENT_POWER_HIGH_WORD, new SignedWordElement(695))),

				// TODO: Add Read Tasks for Error/Warning registers (e.g., 553, 555-558)
				// to map the placeholder state channels (SystemErrorChannelId etc.)

				// FC16: Write Multiple Registers tasks

				// Write Active Power Setpoint (Register 1111)
				new FC16WriteRegistersTask(1111, // Address 1111
						// Unit: 0.1% of nominal power, Signed
						m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(1111))),

				// Write Reactive Power Setpoint (Register 1118)
				new FC16WriteRegistersTask(1118, // Address 1118
						// Unit: 0.1% of nominal power, Signed
						m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(1118)))

				// Optional: Define Write Task for Work State (Register 80?) if needed explicitly
				// Requires confirmation of register address and data type (Signed/Unsigned)
				/*
				 * new FC16WriteRegistersTask(80, // Example address, VERIFY!
				 * m(DeyeSunHybrid.ChannelId.SET_WORK_STATE, new UnsignedWordElement(80))) // Type VERIFY!
				 */
		);
	}

	/**
	 * Provides a string representation of the component's current state for debugging.
	 *
	 * @return A debug string.
	 */
	@Override
	public String debugLog() {
		// SoC (State of Charge)
		String soc = this.getSoc().asOptional().map(s -> s + "%").orElse("N/A");

		// Active Power (L - Leistung) - Read from the standard channel updated by the listener
		String activePower = this.getActivePower().asOptional().map(p -> p + "W").orElse("N/A");

		// Apparent Power (S - Scheinleistung) - Read from the channel updated by the listener
		String apparentPower = this.channel(DeyeSunHybrid.ChannelId.APPARENT_POWER).value().asOptional()
				.map(s -> s + "VA").orElse("N/A");

		// Battery Voltage (V) - Read from the scaled channel
		String voltage = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).value().asOptional()
				.map(v -> String.format("%.1fV", v)).orElse("N/A");

		// Original Allowed Power Limits (calculated before hysteresis/override)
		String origCharge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).value().asOptional()
				.map(String::valueOf).orElse("N/A");
		String origDischarge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).value()
				.asOptional().map(String::valueOf).orElse("N/A");

		// Final Allowed Power Limits (after hysteresis/override)
		String finalCharge = this.getAllowedChargePower().asOptional().map(String::valueOf).orElse("N/A");
		String finalDischarge = this.getAllowedDischargePower().asOptional().map(String::valueOf).orElse("N/A");

		// Next values to be written via Modbus (scaled 0.1% values)
		String nextWriteActivePercent = "N/A";
		String nextWriteReactivePercent = "N/A";
		try {
			IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
			nextWriteActivePercent = setActivePowerChannel.getNextWriteValue().map(String::valueOf).orElse("N/A");

			IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);
			nextWriteReactivePercent = setReactivePowerChannel.getNextWriteValue().map(String::valueOf).orElse("N/A");
		} catch (ClassCastException e) {
			// Should not happen if channels are defined correctly
			log.warn("Unable to cast SET_ACTIVE/REACTIVE_POWER to IntegerWriteChannel in debugLog", e);
		}

		// Format the final debug string
		return "SoC:" + soc + "|L:" + activePower + "|S:" + apparentPower //
				+ "|V:" + voltage //
				+ "|Allowed(Orig):[Chg:" + origCharge + ";Dschg:" + origDischarge + "]" //
				+ "|Allowed(Final):[Chg:" + finalCharge + ";Dschg:" + finalDischarge + "] W" //
				+ "|NextWrite(0.1%):[P:" + nextWriteActivePercent + ";Q:" + nextWriteReactivePercent + "]";
	}

	/**
	 * Handles events based on subscribed topics.
	 *
	 * @param event The OSGi event.
	 */
	@Override
	public void handleEvent(Event event) {
		// Ignore events if component is disabled
		if (!this.isEnabled()) {
			return;
		}

		// Process events based on topic
		switch (event.getTopic()) {

		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			// After Modbus data has been read and channels updated
			this.calculateAndUpdatePowerLimits(); // Calculate limits based on voltage/current
			this.applyPowerLimitOnOvertemperatureError(); // Apply overrides if needed
			this.calculateEnergy(); // Calculate accumulated AC energy
			break;

		case EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS:
			// Before controllers run (e.g., ESS balancing controller)
			this.defineWorkState(); // Periodically send START command if required
			break;
		}
	}

	private LocalDateTime lastDefineWorkState = null;

	/**
	 * Periodically sends the START command (WorkState = 0) to the inverter.
	 * This might be required by some Deye firmware versions to keep the device
	 * accepting commands or operational. Needs verification based on device behavior.
	 */
	private void defineWorkState() {
		// Only run if not in read-only mode
		if (this.config != null && this.config.readOnlyMode()) {
			return;
		}

		var now = LocalDateTime.now();
		// Send command only once per minute to avoid excessive Modbus traffic
		if (this.lastDefineWorkState == null || now.minusMinutes(1).isAfter(this.lastDefineWorkState)) {
			this.lastDefineWorkState = now;

			try {
				// Get the write channel for the work state
				IntegerWriteChannel setWorkStateChannel = this.channel(DeyeSunHybrid.ChannelId.SET_WORK_STATE);
				// Value '0' corresponds to 'START' in the SetWorkState enum (ordinal)
				int valueToWrite = SetWorkState.START.ordinal();
				log.debug("Periodically setting WorkState to [" + valueToWrite + "] (START)");
				// Set the value to be written in the next Modbus write cycle
				setWorkStateChannel.setNextWriteValue(valueToWrite);
			} catch (OpenemsNamedException e) {
				// Log error if channel access fails
				log.error("Unable to get Channel SET_WORK_STATE: " + e.getMessage());
			}
		}
	}

	/**
	 * Calculates power limits based on raw voltage and current limit readings from Modbus.
	 * Updates the corresponding ORIGINAL_ALLOWED_CHARGE/DISCHARGE_POWER channels and
	 * the scaled BATTERY_VOLTAGE channel.
	 */
	private void calculateAndUpdatePowerLimits() {
		try {
			// Get necessary read channels
			IntegerReadChannel batteryVoltageRawChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW);
			IntegerReadChannel chargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT);
			IntegerReadChannel dischargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT);

			// Get target write/update channels
			Channel<Float> batteryVoltageScaledChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE);
			Channel<Integer> originalAllowedChargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
			Channel<Integer> originalAllowedDischargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);

			// Read optional values from channels
			Optional<Integer> batteryVoltageRawOpt = batteryVoltageRawChannel.value().asOptional();
			Optional<Integer> chargeCurrentLimitOpt = chargeCurrentLimitChannel.value().asOptional();
			Optional<Integer> dischargeCurrentLimitOpt = dischargeCurrentLimitChannel.value().asOptional();

			// Check if all required values are present
			if (batteryVoltageRawOpt.isPresent() && chargeCurrentLimitOpt.isPresent()
					&& dischargeCurrentLimitOpt.isPresent()) {

				// Calculate scaled voltage (Raw value is in 0.01V)
				float voltage = batteryVoltageRawOpt.get() / VOLTAGE_SCALING_FACTOR;
				batteryVoltageScaledChannel.setNextValue(voltage);

				// Get raw current limits (assuming unit is A)
				int chargeCurrentRaw = chargeCurrentLimitOpt.get();
				int dischargeCurrentRaw = dischargeCurrentLimitOpt.get();

				// Calculate power limits (P = V * I)
				// Use absolute value for current as limits are typically magnitudes
				int calculatedChargePowerMagnitude = Math.round(voltage * Math.abs(chargeCurrentRaw));
				int calculatedDischargePowerLimit = Math.round(voltage * Math.abs(dischargeCurrentRaw));

				// Update the original power limit channels (charge is negative)
				originalAllowedChargePowerChannel.setNextValue(-calculatedChargePowerMagnitude);
				originalAllowedDischargePowerChannel.setNextValue(calculatedDischargePowerLimit);

			} else {
				// If any input value is missing, set calculated channels to undefined/zero
				batteryVoltageScaledChannel.setNextValue(null);
				originalAllowedChargePowerChannel.setNextValue(0);
				originalAllowedDischargePowerChannel.setNextValue(0);
			}
		} catch (Exception e) {
			// Catch unexpected errors during calculation
			log.error("Unexpected error during power limit calculation: " + e.getMessage(), e);
			try {
				// Attempt to reset channels to safe values on error
				this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).setNextValue(null);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).setNextValue(0);
				this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).setNextValue(0);
			} catch (Exception ex) {
				// Log error if resetting channels also fails
				log.error("Error setting default values after exception in calculateAndUpdatePowerLimits: " + ex.getMessage(), ex);
			}
		}
	}

	/**
	 * Returns the Power object used for constraint management.
	 *
	 * @return the Power object reference
	 */
	@Override
	public Power getPower() {
		return this.power;
	}

	/**
	 * Indicates whether the ESS is managed (i.e., not in read-only mode).
	 *
	 * @return true if managed, false otherwise.
	 */
	@Override
	public boolean isManaged() {
		// Managed only if config exists and readOnlyMode is false
		return this.config != null && !this.config.readOnlyMode();
	}

	/**
	 * Gets the precision for power setpoints, typically used by controllers.
	 * Calculated as 0.1% of nominal power, but at least 1 Watt.
	 *
	 * @return the power precision in Watts.
	 */
	@Override
	public int getPowerPrecision() {
		// Get nominal power from config
		double nominalPower = this.config != null ? this.config.nominalPowerW() : 0;
		if (nominalPower <= 0) {
			return 1; // Default to 1W precision if nominal power is not configured
		}
		// Calculate precision as 0.1% of nominal power, minimum 1W
		int precision = (int) Math.max(1, Math.round(nominalPower * 0.001));
		return precision;
	}

	/**
	 * Defines static constraints for the power management system.
	 * Limits active and reactive power based on configuration.
	 * Sets power to zero if in read-only mode.
	 *
	 * @return an array of Constraint objects.
	 * @throws OpenemsNamedException if creating constraints fails.
	 */
	@Override
	public Constraint[] getStaticConstraints() throws OpenemsNamedException {
		// Return no constraints if config is missing
		if (this.config == null) {
			return Power.NO_CONSTRAINTS;
		}

		// If in read-only mode, constrain power to zero
		if (this.config.readOnlyMode()) {
			return new Constraint[] { //
					// Active power must be 0
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS, 0),
					// Reactive power must be 0
					this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.REACTIVE, Relationship.EQUALS, 0) //
			};
		}

		// Get power limits from configuration
		int maxApparentPower = this.config.maxApparentPowerVA(); // Used as proxy for max active power limit
		int minReactive = this.config.minReactivePowerVar(); // Configured min reactive power
		int maxReactive = this.config.maxReactivePowerVar(); // Configured max reactive power

		// Define constraints based on configured limits
		return new Constraint[] { //
				// Active power discharge limit (max negative power)
				this.createPowerConstraint("Deye Min Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS, -maxApparentPower),
				// Active power charge limit (max positive power)
				this.createPowerConstraint("Deye Max Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.LESS_OR_EQUALS, maxApparentPower),
				// Min reactive power limit
				this.createPowerConstraint("Deye Min Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.GREATER_OR_EQUALS, minReactive),
				// Max reactive power limit
				this.createPowerConstraint("Deye Max Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.LESS_OR_EQUALS, maxReactive) //
		};
	}

	/**
	 * Provides the Modbus nature table for exposing channels via Modbus slave.
	 *
	 * @param accessMode Specifies the access mode (READ_ONLY or READ_WRITE).
	 * @return The ModbusSlaveTable definition.
	 */
	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable( //
				// Include standard tables from implemented natures
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				SymmetricEss.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode), //
				// Include specific table for DeyeSunHybrid channels
				ModbusSlaveNatureTable.of(DeyeSunHybrid.class, accessMode, 100) // Start address offset 100
						// TODO: Add specific channel mappings here if needed
						.build() //
		);
	}

	// --- Logging Wrappers (inherited logX methods add component ID) ---
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
	// --- End Logging Wrappers ---

	/**
	 * Applies a strict power limit if an overtemperature condition is detected
	 * and a specific limit is configured. Overrides existing calculated limits.
	 */
	private void applyPowerLimitOnOvertemperatureError() {
		// Check if the feature is configured
		if (this.config != null && this.config.powerLimitOnOvertemperatureW() > 0) {
			// Get the specific error state channel
			// TODO: This channel needs to be correctly mapped to the actual Modbus bit!
			StateChannel errorChannel = this.channel(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE);

			// Check if the error state is active (value is true)
			if (errorChannel.value().orElse(false)) {
				// Get the configured limit (absolute value)
				int limit = Math.abs(this.config.powerLimitOnOvertemperatureW());
				this.logWarn(this.log, String.format(
						"[%s] Overtemperature constraint active! OVERRIDING AllowedCharge to %d W / Discharge to %d W using configured limit.",
						this.id(), -limit, limit));

				// Directly set the final allowed power limits, overriding hysteresis logic
				this._setAllowedChargePower(-limit);
				this._setAllowedDischargePower(limit);
			}
		}
	}

	/**
	 * Returns the Timedata service reference if available.
	 *
	 * @return the Timedata service or null.
	 */
	@Override
	public Timedata getTimedata() {
		return this.timedata;
	}

	/**
	 * Calculates the accumulated AC charge and discharge energy based on the
	 * current AC active power. Updates the corresponding energy channels.
	 * DC energy calculation is removed as HybridEss is not used.
	 */
	private void calculateEnergy() {
		// Get AC active power value
		Optional<Integer> acActivePowerOpt = this.getActivePowerChannel().value().asOptional();

		if (acActivePowerOpt.isPresent()) {
			int acPower = acActivePowerOpt.get();
			// Update charge/discharge energy based on power sign
			if (acPower > 0) { // Charging from AC perspective (positive power)
				this.calculateAcChargeEnergy.update(acPower); // Update charge energy
				this.calculateAcDischargeEnergy.update(0); // No discharge
			} else { // Discharging from AC perspective (negative power)
				this.calculateAcChargeEnergy.update(0); // No charge
				this.calculateAcDischargeEnergy.update(-acPower); // Update discharge energy (use positive value)
			}
		} else {
			// If power is unknown, update energy channels with null
			this.calculateAcChargeEnergy.update(null);
			this.calculateAcDischargeEnergy.update(null);
		}

		// DC Energy calculation removed
		// Optional<Integer> dcPowerOpt = Optional.empty(); // Placeholder removed
		// ... logic for DC energy removed ...
	}

	/**
	 * Helper method to set up a trigger that updates a target StateChannel based on
	 * the state of multiple source Boolean channels. The target channel becomes
	 * true if any of the source channels are true.
	 *
	 * @param targetChannelId The ID of the target StateChannel to update.
	 * @param sourceChannelIds An array of Channel IDs for the source Boolean channels.
	 */
	private void addStateChannelTrigger(io.openems.edge.common.channel.ChannelId targetChannelId,
			io.openems.edge.common.channel.ChannelId[] sourceChannelIds) {
		// Get the target channel
		StateChannel targetChannel = this.channel(targetChannelId);

		// Get the source channels, handling potential errors
		List<Channel<Boolean>> sourceChannels = Arrays.stream(sourceChannelIds).map(id -> {
			try {
				// Attempt to get the channel and cast it to Boolean
				@SuppressWarnings("unchecked")
				Channel<Boolean> channel = (Channel<Boolean>) this.channel(id);
				return channel;
			} catch (IllegalArgumentException | ClassCastException e) {
				// Log warning if source channel is problematic
				this.logWarn(log, "Source channel [" + id.id() + "] problem for target [" + targetChannelId.id()
						+ "]: Not found or not Boolean. Trigger might not work correctly. " + e.getMessage());
				return null; // Return null for invalid channels
			}
		}).filter(java.util.Objects::nonNull) // Filter out null (invalid) channels
				.collect(Collectors.toList());

		// Check if any valid source channels were found
		if (sourceChannels.isEmpty()) {
			this.logWarn(log, "No valid source channels found for target [" + targetChannelId.id() + "]. Setting to false.");
			targetChannel.setNextValue(false); // Set target to false if no sources
			return;
		}

		// Define the logic to update the target channel
		Runnable updateTargetChannel = () -> {
			// Check if any source channel's value is true (defaulting to false if null/missing)
			boolean isAnySourceTrue = sourceChannels.stream().anyMatch(ch -> ch.value().orElse(false));
			// Update the target channel
			targetChannel.setNextValue(isAnySourceTrue);
		};

		// Register the update logic to run whenever any source channel changes
		for (Channel<Boolean> sourceChannel : sourceChannels) {
			sourceChannel.onUpdate(value -> updateTargetChannel.run());
		}

		// Run the logic once initially to set the starting value
		updateTargetChannel.run();
	}

	/**
	 * Gets the Modbus Unit-ID configured for this component.
	 *
	 * @return The Modbus Unit-ID.
	 */
	@Override
	public Integer getUnitId() {
		// Delegated to the superclass method which stores the unitId from activate/modified
		return super.getUnitId();
	}

}