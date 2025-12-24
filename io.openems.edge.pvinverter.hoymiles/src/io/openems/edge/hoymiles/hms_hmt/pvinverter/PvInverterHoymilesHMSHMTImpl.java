package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.channel.AccessMode;
import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedDoublewordElement;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
import io.openems.edge.bridge.modbus.api.task.Task;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.modbusslave.ModbusSlave;
import io.openems.edge.common.modbusslave.ModbusSlaveTable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.meter.api.ElectricityMeter;
import io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter;

@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "PV-Inverter.Hoymiles.HMS-HMT", //
		immediate = true, //
		configurationPolicy = REQUIRE, //
		property = { //
				"type=PRODUCTION" //
		})
@EventTopics({ //
		TOPIC_CYCLE_EXECUTE_WRITE //
})
public class PvInverterHoymilesHMSHMTImpl extends AbstractOpenemsModbusComponent //
		implements PvInverterHoymilesHMSHMT, ManagedSymmetricPvInverter, ElectricityMeter, //
		ModbusComponent, OpenemsComponent, EventHandler, ModbusSlave {

	// OpenEMS-konformes SLF4J-Logging (aktuell nur für evtl. spätere Nutzung)
	private final Logger log = LoggerFactory.getLogger(PvInverterHoymilesHMSHMTImpl.class);

	/*
	 * Interner Debug-Schalter für debugLog():
	 * - true  -> ausführliche Statuszeile für ctrlDebugLog0
	 * - false -> debugLog() gibt einen leeren String zurück
	 */
	private static final boolean INTERNAL_DEBUG = true;

	/*
	 * Configuration as provided by OSGi / Felix WebConsole.
	 */
	private Config config;

	/**
	 * Size of one microinverter register block.
	 *
	 * According to Hoymiles Modbus documentation:
	 * MI1 base: 0x38E0
	 * MI2 base: 0x3940
	 * → block size: 0x60 (96 words) per microinverter.
	 */
	private static final int MI_REGISTER_BLOCK_SIZE = 0x60; // 96 registers per inverter block

	/**
	 * Hysterese für Leistungs-Sollwert in W.
	 */
	private static final int LIMIT_HYSTERESIS_W = 100;

	//mrdomek Keep power-limit logic out of the component to stay readable.
	private final HoymilesPowerLimitHandler powerLimitHandler =
			new HoymilesPowerLimitHandler(LIMIT_HYSTERESIS_W, 5_000L);

	// Selected microinverter number (1..99); used to shift the read register block.
	private int microinverterNumber = 1;

	// --- DTU topology (needed to map Microinverter -> Port index for writes) ---
	private static final int DTU_REGISTERED_MICROINVERTER_COUNT_REG = 0x3004; // FC04
	private static final int DTU_SERIAL_LIST_BASE_REG = 0x502B;               // FC03, 3 words per microinverter
	private static final int MAX_PORTS = 99;
	private static final int MAX_MICROINVERTERS = 99;
	private static final long TOPOLOGY_REFRESH_INTERVAL_MS = 30_000L;
	
	// Serial list as 3-word element -> direct String per MI (no per-word channels)
	private final ThreeWordHexRegisterElement[] dtuSerialListSerials =
			new ThreeWordHexRegisterElement[MAX_MICROINVERTERS];


	// Values are read via Modbus tasks; we access them via reflection to stay compatible across OpenEMS versions.
	private SignedWordElement dtuRegisteredMicroinverterCount;

	// --- Per-Port write elements (Port == DC input) ---
	private SignedWordElement[] portOnOffByPort = new SignedWordElement[MAX_PORTS];
	private SignedWordElement[] portTempLimitActivePowerByPort = new SignedWordElement[MAX_PORTS];

	// Current selected write port for THIS component (derived from DTU topology).
	private int selectedWritePort = 1;
	private long lastTopologyRefreshMs = 0;
	private String lastTopologySignature = null;

	// Active write pointers (existing write logic uses THESE)
	private SignedWordElement portOnOff;
	private SignedWordElement portTempLimitActivePower;

	// Last written value (avoid redundant writes)
	private Boolean lastWrittenPortOn = null;

	public PvInverterHoymilesHMSHMTImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				ManagedSymmetricPvInverter.ChannelId.values(), //
				PvInverterHoymilesHMSHMT.ChannelId.values() //
		);
	}

	@Override
	@Reference(policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference
	private ConfigurationAdmin cm;

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		/*
		 * Typischer Fallback:
		 * - Wenn id leer -> Alias oder "pvInverter0"
		 * - Wenn Alias leer -> id
		 * - Wenn modbus_id leer -> "modbus0"
		 * - Wenn Unit-ID <= 0 -> 201
		 */
		String id = config.id();
		String alias = config.alias();
		String modbusId = config.modbus_id();
		int unitId = config.modbusUnitId();

		if (id == null || id.isBlank()) {
			if (alias != null && !alias.isBlank()) {
				id = alias;
			} else {
				id = "pvInverter0";
			}
		}

		if (alias == null || alias.isBlank()) {
			alias = id;
		}

		if (modbusId == null || modbusId.isBlank()) {
			modbusId = "modbus0";
		}

		if (unitId <= 0) {
			unitId = 201;
		}

		/*
		 * WICHTIG:
		 * microinverterNumber MUSS gesetzt sein,
		 * bevor super.activate() -> defineModbusProtocol() aufruft.
		 */
		this.microinverterNumber = Math.max(1, Math.min(config.microinverterNumber(), 99));

		super.activate(context, //
				id, //
				alias, //
				config.enabled(), //
				unitId, //
				this.cm, //
				"Modbus", //
				modbusId);

		// Konfigurierte Phase ins Channel-Model schreiben
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.CONFIGURED_PHASE) //
				.setNextValue(config.phase().name());

		this.updateStaticPowerLimitsFromModel();
	}

	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		if (!TOPIC_CYCLE_EXECUTE_WRITE.equals(event.getTopic())) {
			return;
		}

		if (this.config == null) {
			return;
		}

		if (!this.isEnabled()) {
			return;
		}

		// Step 1: update DTU topology -> selects correct write-port for this microinverter
		this.updateWritePortFromDtuTopologyIfDue();

		// Update derived meta values and configured limits
		this.updateMetaAndLimits();

		// Meter/Power aggregation (kept as own method for clarity)
		final int totalPowerW = this.updateMeterPowersAndGetTotalPowerW();

		// Status + utilization based on current measurements
		this.updateStatusAndUtilization(totalPowerW);

		// Apply control only if allowed
		if (!this.config.readOnly()) {
			this.applyActivePowerLimitFromChannel();
		}
	}

	private void updateMetaAndLimits() {
		// Aktive Leistungsbegrenzung anwenden, falls nicht im Read-Only-Modus
		if (!this.config.readOnly()) {
			this.applyActivePowerLimitFromChannel();
		}
	}

	private Integer updateMeterPowersAndGetTotalPowerW() {
		/*
		 * Get total AC active power from microinverter block.
		 * This is already scaled to W via Modbus mapping.
		 */
		Optional<?> pOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W)
				.value()
				.asOptional();

		if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
			return null;
		}

		int pTotal = ((Number) pOpt.get()).intValue();

		/*
		 * Determine if the selected device model is three-phase (HMT)
		 * or single-phase (HMS).
		 */
		DeviceModel model = null;
		boolean threePhaseDevice = false;
		PvInverterHoymilesHMSHMT.Phase phase = null;

		try {
			model = (this.config != null) ? this.config.deviceModel() : null;
			phase = (this.config != null) ? this.config.phase() : null;
			threePhaseDevice = (model != null) && model.isThreePhase();
		} catch (Exception e) {
			// defensive fallback: treat as single-phase on L1
			threePhaseDevice = false;
		}

		// Split total power to phases according to device type + configured phase
		int[] phases = splitPowerByPhase(threePhaseDevice, phase, pTotal);
		int pL1 = phases[0];
		int pL2 = phases[1];
		int pL3 = phases[2];

		// Set phase powers
		this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L1).setNextValue(pL1);
		this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L2).setNextValue(pL2);
		this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L3).setNextValue(pL3);

		// Total meter power = sum of phases
		this.channel(ElectricityMeter.ChannelId.ACTIVE_POWER).setNextValue(pL1 + pL2 + pL3);

		return Integer.valueOf(pTotal);
	}

	private void updateStatusAndUtilization(int pTotal) {
		/*
		 * DC utilization per PV input:
		 * utilization[%] = (PVx_POWER_W / configured_module_peak_W) * 100
		 * If peak = 0 or missing power -> channel is set to null.
		 */
		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_UTILIZATION_PERCENT,
				this.config.pv1ModulePeakPowerW());

		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_UTILIZATION_PERCENT,
				this.config.pv2ModulePeakPowerW());

		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_UTILIZATION_PERCENT,
				this.config.pv3ModulePeakPowerW());

		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_UTILIZATION_PERCENT,
				this.config.pv4ModulePeakPowerW());

		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_UTILIZATION_PERCENT,
				this.config.pv5ModulePeakPowerW());

		updatePvUtilization(
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_UTILIZATION_PERCENT,
				this.config.pv6ModulePeakPowerW());

		// Update alarm/status summary channel (now per-register, not OR-combined)
		updateAlarmSummary();

		// Health-State (Ampel) aus Status- und Alarm-Register ableiten (night-mode aware)
		updateHealthFromStatusAndAlarms(pTotal);

		// Build interpreted status using full status+alarm context (not just hasAlarm)
		final int status = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE);
		final int alarm1 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE);
		final int alarm2 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE);
		final int alarm3 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE);
		final int alarm4 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE);
		final int alarm5 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE);
		final int alarm6 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE);

		final String interpretedStatus = interpretHoymilesStatus(pTotal, status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_INTERPRETED_STATUS).setNextValue(interpretedStatus);

		// Trigger für die erweiterte Debug-Ausgabe
		this.logDebug(this.log, "Next Cycle");
	}

	@Override
	public void setActivePowerLimit(Integer power) throws OpenemsNamedException {
		final Integer normalized = (power == null) ? null : Integer.valueOf(Math.max(0, power.intValue()));

		// For visibility in standard channels + debug log
		this.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT).setNextValue(normalized);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W).setNextValue(normalized);

		//mrdomek Debug-only: prove that the manager/controller actually calls this method.
		if (this.config != null && this.config.debugMode()) {
			this.logInfo(this.log, String.format("Hoymiles: setActivePowerLimit() received [%s W].",
					normalized == null ? "null" : normalized.toString()));
		}
	}

	private void applyActivePowerLimitFromChannel() {
		if (this.portTempLimitActivePower == null) {
			//mrdomek If this is null, we cannot schedule a Modbus write at all (readOnly mode or protocol not initialized).
			if (this.config != null && this.config.debugMode()) {
				this.logInfo(this.log,
						"PowerLimit: portTempLimitActivePower==null -> skipping power limit writes (readOnly/protocol not initialized).");
			}
			return;
		}

		final long now = System.currentTimeMillis();

		final DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
		final int maxTotalPowerW = (model != null) ? model.getMaxTotalPowerW() : 0;

		int minPercent = 0;
		if (model != null && model.getGeneration() != null) {
			minPercent = model.getGeneration().getMinPercent();
		}

		/*
		 * Robust input: ONLY use the standard OpenEMS channel.
		 * If this is UNDEFINED, the issue is upstream (manager/controller), not inside this component.
		 */
		Integer targetLimitW = null;
		Optional<?> opt = this.channel(io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT)
				.value()
				.asOptional();
		if (opt.isPresent() && opt.get() instanceof Number) {
			targetLimitW = Integer.valueOf(((Number) opt.get()).intValue());
		}

		// Mirror for UI/debug
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W).setNextValue(targetLimitW);

		/*
		 * Measurements for closed-loop regulation.
		 */
		final int actualAcPowerW = readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W, 0);

		int actualDcPowerW = 0;
		int dcPeakTotalW = 0;

		final int inputs = (model != null) ? Math.max(0, Math.min(model.getInputChannels(), 6)) : 0;
		for (int i = 1; i <= inputs; i++) {
			switch (i) {
			case 1:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv1ModulePeakPowerW() : 0;
				break;
			case 2:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv2ModulePeakPowerW() : 0;
				break;
			case 3:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv3ModulePeakPowerW() : 0;
				break;
			case 4:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv4ModulePeakPowerW() : 0;
				break;
			case 5:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv5ModulePeakPowerW() : 0;
				break;
			case 6:
				actualDcPowerW += readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W, 0);
				dcPeakTotalW += (this.config != null) ? this.config.pv6ModulePeakPowerW() : 0;
				break;
			default:
				break;
			}
		}

		this.powerLimitHandler.applyAcRegulated(
				targetLimitW,
				maxTotalPowerW,
				minPercent,
				actualAcPowerW,
				actualDcPowerW,
				dcPeakTotalW,
				now,
				new HoymilesPowerLimitHandler.Actions() {

					@Override
					public void setPortOnOff(boolean on) {
						PvInverterHoymilesHMSHMTImpl.this.setPortOnOff(on);
					}

					@Override
					public void setLimitWUi(Integer w) {
						PvInverterHoymilesHMSHMTImpl.this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W)
								.setNextValue(w);
					}

					@Override
					public void setLimitPercentUi(Integer percent) {
						PvInverterHoymilesHMSHMTImpl.this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
								.setNextValue(percent);
					}

					@Override
					public void schedulePercentWrite(short percent) {
						PvInverterHoymilesHMSHMTImpl.this.portTempLimitActivePower.setNextWriteValue(Short.valueOf(percent));
					}

					@Override
					public void debug(String message) {
						if (PvInverterHoymilesHMSHMTImpl.this.config != null && PvInverterHoymilesHMSHMTImpl.this.config.debugMode()) {
							PvInverterHoymilesHMSHMTImpl.this.logInfo(PvInverterHoymilesHMSHMTImpl.this.log, message);
						}
					}
				});
	}

	private static int[] splitPowerByPhase(boolean threePhaseDevice,
			PvInverterHoymilesHMSHMT.Phase phase, int totalPower) {

		int pL1 = 0;
		int pL2 = 0;
		int pL3 = 0;

		if (threePhaseDevice) {
			int perPhase = totalPower / 3;
			pL1 = perPhase;
			pL2 = perPhase;
			pL3 = totalPower - pL1 - pL2;
			return new int[] { pL1, pL2, pL3 };
		}

		if (phase == null) {
			phase = PvInverterHoymilesHMSHMT.Phase.L1;
		}

		switch (phase) {
		case L1:
			pL1 = totalPower;
			break;

		case L2:
			pL2 = totalPower;
			break;

		case L3:
			pL3 = totalPower;
			break;

		default:
			pL1 = totalPower;
			break;
		}

		return new int[] { pL1, pL2, pL3 };
	}

	private void updatePvUtilization(PvInverterHoymilesHMSHMT.ChannelId powerChannelId,
			PvInverterHoymilesHMSHMT.ChannelId utilizationChannelId, int modulePeakPowerW) {

		if (modulePeakPowerW <= 0) {
			this.channel(utilizationChannelId).setNextValue(null);
			return;
		}

		Optional<?> pOpt = this.channel(powerChannelId).value().asOptional();
		if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
			this.channel(utilizationChannelId).setNextValue(null);
			return;
		}

		double powerW = ((Number) pOpt.get()).doubleValue();

		if (powerW < 0) {
			powerW = 0;
		}

		double percent = (powerW / (double) modulePeakPowerW) * 100.0;
		int percentRounded = (int) Math.round(percent);

		this.channel(utilizationChannelId).setNextValue(percentRounded);
	}

	private void updateHealthFromStatusAndAlarms(int totalPowerW) {
		boolean hasData = false;
		Integer status = null;

		// Read status code
		Optional<?> statusOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE)
				.value()
				.asOptional();
		if (statusOpt.isPresent() && statusOpt.get() instanceof Number) {
			status = ((Number) statusOpt.get()).intValue();
			hasData = true;
		}

		// Read alarm registers 1..6 (treat missing/non-number as 0, but keep hasData if we saw any number)
		int alarm1 = 0;
		int alarm2 = 0;
		int alarm3 = 0;
		int alarm4 = 0;
		int alarm5 = 0;
		int alarm6 = 0;

		Optional<?> a1 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE).value().asOptional();
		if (a1.isPresent() && a1.get() instanceof Number) {
			alarm1 = ((Number) a1.get()).intValue();
			hasData = true;
		}

		Optional<?> a2 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE).value().asOptional();
		if (a2.isPresent() && a2.get() instanceof Number) {
			alarm2 = ((Number) a2.get()).intValue();
			hasData = true;
		}

		Optional<?> a3 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE).value().asOptional();
		if (a3.isPresent() && a3.get() instanceof Number) {
			alarm3 = ((Number) a3.get()).intValue();
			hasData = true;
		}

		Optional<?> a4 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE).value().asOptional();
		if (a4.isPresent() && a4.get() instanceof Number) {
			alarm4 = ((Number) a4.get()).intValue();
			hasData = true;
		}

		Optional<?> a5 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE).value().asOptional();
		if (a5.isPresent() && a5.get() instanceof Number) {
			alarm5 = ((Number) a5.get()).intValue();
			hasData = true;
		}

		Optional<?> a6 = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE).value().asOptional();
		if (a6.isPresent() && a6.get() instanceof Number) {
			alarm6 = ((Number) a6.get()).intValue();
			hasData = true;
		}

		final boolean hasAlarm = HoymilesMi1StateLogic.hasAnyAlarm(alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);

		// OpenEMS Level-channels (used by core to compute component STATE)
		final boolean fault = HoymilesMi1StateLogic.isFault(hasData, totalPowerW, status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
		final boolean warning = HoymilesMi1StateLogic.isWarning(hasData, totalPowerW, status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);

		final String health = HoymilesMi1StateLogic.toHealthState(hasData, fault, warning);

		// Info channels for UI/debug
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HAS_ALARM).setNextValue(hasAlarm);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HEALTH_STATE).setNextValue(health);

		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_FAULT).setNextValue(fault);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_WARNING).setNextValue(warning);
	}

	private int readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId channelId, int defaultValue) {
		Optional<?> opt = this.channel(channelId).value().asOptional();
		if (!opt.isPresent() || !(opt.get() instanceof Number)) {
			return defaultValue;
		}
		return ((Number) opt.get()).intValue();
	}

	private static String interpretHoymilesStatus(int totalPower, int rawStatusCode, int alarm1, int alarm2, int alarm3, int alarm4, int alarm5,
			int alarm6) {
		return HoymilesMi1StateLogic.interpretHoymilesStatus(totalPower, rawStatusCode, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
	}

	private void updateStaticPowerLimitsFromModel() {
		if (this.config == null) {
			return;
		}
		final DeviceModel model = this.config.deviceModel();
		if (model == null) {
			return;
		}

		final int pMaxW = model.getMaxTotalPowerW();
		final int sMaxVa = model.getMaxApparentPowerVa();

		// MaxActivePower (W) – capability for OpenEMS algorithms
		this.channel(io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.MAX_ACTIVE_POWER)
				.setNextValue(Integer.valueOf(pMaxW));

		// MaxApparentPower (VA) – required for limitation algorithms (analysis report)
		this.channel(io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.MAX_APPARENT_POWER)
				.setNextValue(Integer.valueOf(sMaxVa));
	}

	private void updateAlarmSummary() {
		// Read all relevant 16-bit words (treat missing/null as 0)
		int status = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE);
		int alarm1 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE);
		int alarm2 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE);
		int alarm3 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE);
		int alarm4 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE);
		int alarm5 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE);
		int alarm6 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE);

		final DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
		final int inputChannels = (model != null) ? model.getInputChannels() : 6;

		String summary = HoymilesMi1StateLogic.buildAlarmSummary(status, inputChannels, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);

		// Write to channel for UI
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM_SUMMARY).setNextValue(summary);
	}

	private int getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId channelId) {
		return this.channel(channelId) //
				.value() //
				.asOptional() //
				.map(v -> ((Number) v).intValue()) //
				.orElse(0);
	}



	private void setPortOnOff(boolean on) {
		if (this.portOnOff == null) {
			return;
		}

		if (this.lastWrittenPortOn != null && this.lastWrittenPortOn.booleanValue() == on) {
			return;
		}

		short value = (short) (on ? 1 : 0);
		this.portOnOff.setNextWriteValue(Short.valueOf(value));
		this.lastWrittenPortOn = Boolean.valueOf(on);
	}

	private void updateWritePortFromDtuTopologyIfDue() {
		// Only log/compute occasionally; tasks are LOW prio and values may update slowly.
		final long now = System.currentTimeMillis();
		if (now - this.lastTopologyRefreshMs < TOPOLOGY_REFRESH_INTERVAL_MS) {
			return;
		}
		this.lastTopologyRefreshMs = now;

		if (this.dtuRegisteredMicroinverterCount == null) {
			if (this.config != null && this.config.debugMode()) {
				log.info("PowerLimit: DTU topology elements not initialized yet -> skipping mapping update");
			}
			return;
		}

		final Integer countU16 = readU16FromElement(this.dtuRegisteredMicroinverterCount);
		if (countU16 == null || countU16.intValue() <= 0) {
			if (this.config != null && this.config.debugMode()) {
				log.info("PowerLimit: DTU inverter-count not available yet (0x3004) -> skipping mapping update");
			}
			return;
		}

		final int inverterCount = Math.min(countU16.intValue(), MAX_MICROINVERTERS);

		if (this.microinverterNumber < 1 || this.microinverterNumber > inverterCount) {
			log.warn(
					"PowerLimit: Config microinverterNumber={} out of range (DTU reports {} inverters) -> mapping unchanged",
					this.microinverterNumber, inverterCount);
			return;
		}

		/*
		 * IMPORTANT:
		 * We intentionally only need MI1..MI(microinverterNumber) to compute the port offset.
		 */
		final int neededMis = this.microinverterNumber;

		// Signature: inverterCount + prefixes of MI1..neededMis
		final StringBuilder sig = new StringBuilder(8 * neededMis);
		sig.append(inverterCount).append(':');

		final int[] prefixes = new int[neededMis];

		for (int mi = 1; mi <= neededMis; mi++) {
			final String serial = this.channel(PvInverterHoymilesHMSHMT.ChannelId
					.valueOf("DTU__CONNECTED_MI" + mi + "_SERIAL"))
					.value().asOptional()
					.map(v -> String.valueOf(v))
					.orElse(null);

			if (serial == null || serial.length() < 4) {
				if (this.config != null && this.config.debugMode()) {
					log.info("PowerLimit: DTU serial not available yet (MI{}) -> mapping unchanged", mi);
				}
				return;
			}

			final int prefixU16;
			try {
				prefixU16 = Integer.parseInt(serial.substring(0, 4), 16) & 0xFFFF;
			} catch (Exception e) {
				log.warn("PowerLimit: DTU serial prefix parse failed for MI{} serial='{}' -> mapping unchanged", mi, serial);
				return;
			}

			prefixes[mi - 1] = prefixU16;
			sig.append(String.format("%04X", prefixU16)).append(',');
		}

		final String signature = sig.toString();

		// Compute start-port index for this.microinverterNumber by summing input-channels of previous MIs
		int portIndex = 1;
		for (int mi = 1; mi < this.microinverterNumber; mi++) {
			final int prefixU16 = prefixes[mi - 1];

			final DeviceModel model = DeviceModel.findBySerialWord0(prefixU16);
			if (model == null) {
				log.warn("PowerLimit: Unknown DeviceModel prefix 0x{} for MI{} -> mapping unchanged",
						String.format("%04X", prefixU16), mi);
				return;
			}

			portIndex += model.getInputChannels();
		}

		if (portIndex < 1 || portIndex > MAX_PORTS) {
			log.warn("PowerLimit: Computed write-port {} out of range (1..{}) -> mapping unchanged", portIndex, MAX_PORTS);
			return;
		}

		// Hoymiles write behavior: only first port of the MI is effective -> use computed start-port as write port
		final int newWritePort = portIndex;

		final boolean signatureChanged = (this.lastTopologySignature == null) || !this.lastTopologySignature.equals(signature);
		final boolean portChanged = (this.selectedWritePort != newWritePort);

		if (!signatureChanged && !portChanged) {
			if (this.config != null && this.config.debugMode()) {
				log.info("PowerLimit: DTU topology unchanged -> writePort stays at {}", this.selectedWritePort);
			}
			return;
		}

		this.lastTopologySignature = signature;

		this.selectedWritePort = newWritePort;
		this.portOnOff = this.portOnOffByPort[newWritePort - 1];
		this.portTempLimitActivePower = this.portTempLimitActivePowerByPort[newWritePort - 1];

		// Force next write to re-assert ON/OFF on the new port (existing logic relies on lastWrittenPortOn)
		this.lastWrittenPortOn = null;

		log.info("PowerLimit: DTU topology mapped microinverterNumber={} -> writePort={} (signatureChanged={})",
				this.microinverterNumber, this.selectedWritePort, signatureChanged);
	}

	private static Integer readU16FromElement(SignedWordElement element) {
		if (element == null) {
			return null;
		}

		//mrdomek Why: OpenEMS ModbusElement APIs differ between branches; use reflection to stay compatible.
		for (String m : new String[] { "getValue", "getValueOptional", "value", "getRawValue", "getUnsignedValue",
				"getSignedValue" }) {
			final Object v = tryInvokeNoArg(element, m);
			final Integer u16 = convertToU16(v);
			if (u16 != null) {
				return u16;
			}
		}

		return null;
	}

	private static Object tryInvokeNoArg(Object target, String methodName) {
		if (target == null) {
			return null;
		}
		try {
			return target.getClass().getMethod(methodName).invoke(target);
		} catch (Exception e) {
			return null;
		}
	}

	static Integer convertToU16(Object v) {
		if (v == null) {
			return null;
		}

		if (v instanceof Number) {
			return Integer.valueOf(((Number) v).intValue() & 0xFFFF);
		}

		if (v instanceof java.util.Optional) {
			final java.util.Optional<?> opt = (java.util.Optional<?>) v;
			if (!opt.isPresent()) {
				return null;
			}
			return convertToU16(opt.get());
		}

		/*
		 * OpenEMS Value-wrapper handling (e.g. io.openems.common.types.Value<?>).
		 * Many OpenEMS APIs return Value<?> instead of raw primitives.
		 */
		try {
			final java.lang.reflect.Method asOptional = v.getClass().getMethod("asOptional");
			final Object opt = asOptional.invoke(v);
			final Integer r = convertToU16(opt);
			if (r != null) {
				return r;
			}
		} catch (Exception e) {
			// ignore
		}

		try {
			final java.lang.reflect.Method get = v.getClass().getMethod("get");
			final Object raw = get.invoke(v);
			if (raw != null && raw != v) {
				final Integer r = convertToU16(raw);
				if (r != null) {
					return r;
				}
			}
		} catch (Exception e) {
			// ignore
		}

		try {
			final java.lang.reflect.Method value = v.getClass().getMethod("value");
			final Object raw = value.invoke(v);
			if (raw != null && raw != v) {
				final Integer r = convertToU16(raw);
				if (r != null) {
					return r;
				}
			}
		} catch (Exception e) {
			// ignore
		}

		return null;
	}

	//mrdomek Modbus FC03/FC04 common max is 125 registers; keep margin for device quirks.
	private static final int DTU_FC3_MAX_WORDS = 120;
	//mrdomek Hoymiles serial list is 3x uint16 words per microinverter.
	private static final int DTU_SERIAL_WORDS_PER_MI = 3;

	private void addDtuSerialListReadTasksLimited(List<Task> tasks, Priority priority, int wordsToRead) {
		final int cappedWords = Math.max(0, Math.min(wordsToRead, MAX_MICROINVERTERS * DTU_SERIAL_WORDS_PER_MI));
		if (cappedWords == 0) {
			if (this.config != null && this.config.debugMode()) {
				log.info("DTU topology: serial list wordsToRead=0 -> skipping FC03 serial list reads");
			}
			return;
		}

		// each MI serial consumes 3 words
		final int itemsToRead = cappedWords / DTU_SERIAL_WORDS_PER_MI;

		// desired chunk = 3 microinverters => 9 words
		final int desiredItemsPerChunk = 3;

		// but never exceed Modbus max words per FC03 read
		final int maxItemsByFcLimit = Math.max(1, DTU_FC3_MAX_WORDS / DTU_SERIAL_WORDS_PER_MI);

		final int itemsPerChunk = Math.min(desiredItemsPerChunk, maxItemsByFcLimit);
		final int chunks = (itemsToRead + itemsPerChunk - 1) / itemsPerChunk;

		if (this.config != null && this.config.debugMode()) {
			log.info("DTU topology: scheduling serial list FC03 reads words={} items={} chunks={} (itemsPerChunk={})",
					cappedWords, itemsToRead, chunks, itemsPerChunk);
		}

		for (int itemOffset = 0; itemOffset < itemsToRead; itemOffset += itemsPerChunk) {
			final int lenItems = Math.min(itemsPerChunk, itemsToRead - itemOffset);

			final ThreeWordHexRegisterElement[] slice = Arrays.copyOfRange(
					this.dtuSerialListSerials, itemOffset, itemOffset + lenItems);

			final int startReg = DTU_SERIAL_LIST_BASE_REG + (itemOffset * DTU_SERIAL_WORDS_PER_MI);

			final int startMiNumber = itemOffset + 1; // 1-based MI number of first element in this chunk

			tasks.add(new FC3ReadRegistersTask(es -> {
				//mrdomek Why: reduce bus traffic; only poll serials for DTU-reported inverter count.
				final Integer cnt = readU16FromElement(this.dtuRegisteredMicroinverterCount);
				if (cnt == null || cnt.intValue() < startMiNumber) {
					trySkipExecuteState(es);
				}
			}, startReg, priority, slice));

		}
	}

	private static void trySkipExecuteState(Object state) {
		if (state == null) {
			return;
		}
		//mrdomek Why: ExecuteState API differs between OpenEMS branches; use reflection to skip task execution.
		for (String m : new String[] { "skip", "setSkip", "setSkipExecution", "setSkipped" }) {
			try {
				var method = state.getClass().getMethod(m);
				method.invoke(state);
				return;
			} catch (NoSuchMethodException e) {
				// try next
			} catch (Exception e) {
				return;
			}
		}
		for (String m : new String[] { "setSkip", "setSkipExecution", "setSkipped" }) {
			try {
				var method = state.getClass().getMethod(m, boolean.class);
				method.invoke(state, true);
				return;
			} catch (NoSuchMethodException e) {
				// try next
			} catch (Exception e) {
				return;
			}
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		/*
		 * Microinverter register block selection:
		 * MI1 base: 0x38E0
		 * MI2 base: 0x3940
		 * block size: 0x60 (96 words) per microinverter
		 */
		final int base = 0x38E0 + (this.microinverterNumber - 1) * MI_REGISTER_BLOCK_SIZE;

		// -----------------------------------------------------------------------------------------
		// Serial + Energy
		// -----------------------------------------------------------------------------------------
		final ThreeWordHexRegisterElement mi1Serial = new ThreeWordHexRegisterElement(base + 0x00);

		final UnsignedDoublewordElement totalProductionWh = new UnsignedDoublewordElement(base + 0x03);
		final UnsignedDoublewordElement todayProductionWh = new UnsignedDoublewordElement(base + 0x05);

		// -----------------------------------------------------------------------------------------
		// AC (Power / PF / Voltages / Currents / Frequency / Temperature)
		// -----------------------------------------------------------------------------------------
		final SignedWordElement activePower = new SignedWordElement(base + 0x07);
		final SignedWordElement reactivePower = new SignedWordElement(base + 0x08);
		final SignedWordElement powerFactor = new SignedWordElement(base + 0x09);

		final SignedWordElement vphA = new SignedWordElement(base + 0x0A);
		final SignedWordElement vphB = new SignedWordElement(base + 0x0B);
		final SignedWordElement vphC = new SignedWordElement(base + 0x0C);

		final SignedWordElement uab = new SignedWordElement(base + 0x0D);
		final SignedWordElement ubc = new SignedWordElement(base + 0x0E);
		final SignedWordElement uca = new SignedWordElement(base + 0x0F);

		final SignedWordElement iphA = new SignedWordElement(base + 0x10);
		final SignedWordElement iphB = new SignedWordElement(base + 0x11);
		final SignedWordElement iphC = new SignedWordElement(base + 0x12);

		final SignedWordElement frequency = new SignedWordElement(base + 0x13);
		final SignedWordElement temperature = new SignedWordElement(base + 0x14);

		// -----------------------------------------------------------------------------------------
		// PV Inputs (PV1..PV6)
		// -----------------------------------------------------------------------------------------
		final SignedWordElement pv1Voltage = new SignedWordElement(base + 0x15);
		final SignedWordElement pv1Current = new SignedWordElement(base + 0x16);
		final SignedWordElement pv1Power = new SignedWordElement(base + 0x17);

		final SignedWordElement pv2Voltage = new SignedWordElement(base + 0x18);
		final SignedWordElement pv2Current = new SignedWordElement(base + 0x19);
		final SignedWordElement pv2Power = new SignedWordElement(base + 0x1A);

		final SignedWordElement pv3Voltage = new SignedWordElement(base + 0x1B);
		final SignedWordElement pv3Current = new SignedWordElement(base + 0x1C);
		final SignedWordElement pv3Power = new SignedWordElement(base + 0x1D);

		final SignedWordElement pv4Voltage = new SignedWordElement(base + 0x1E);
		final SignedWordElement pv4Current = new SignedWordElement(base + 0x1F);
		final SignedWordElement pv4Power = new SignedWordElement(base + 0x20);

		final SignedWordElement pv5Voltage = new SignedWordElement(base + 0x21);
		final SignedWordElement pv5Current = new SignedWordElement(base + 0x22);
		final SignedWordElement pv5Power = new SignedWordElement(base + 0x23);

		final SignedWordElement pv6Voltage = new SignedWordElement(base + 0x24);
		final SignedWordElement pv6Current = new SignedWordElement(base + 0x25);
		final SignedWordElement pv6Power = new SignedWordElement(base + 0x26);

		// -----------------------------------------------------------------------------------------
		// Status / Alarms
		// -----------------------------------------------------------------------------------------
		final SignedWordElement status = new SignedWordElement(base + 0x27);

		final SignedWordElement alarm1 = new SignedWordElement(base + 0x28);
		final SignedWordElement alarm2 = new SignedWordElement(base + 0x29);
		final SignedWordElement alarm3 = new SignedWordElement(base + 0x2A);
		final SignedWordElement alarm4 = new SignedWordElement(base + 0x2B);
		final SignedWordElement alarm5 = new SignedWordElement(base + 0x2C);
		final SignedWordElement alarm6 = new SignedWordElement(base + 0x2D);

		// -----------------------------------------------------------------------------------------
		// DTU topology (count + serial list; required for Microinverter -> Port mapping)
		// -----------------------------------------------------------------------------------------
		this.dtuRegisteredMicroinverterCount = new SignedWordElement(DTU_REGISTERED_MICROINVERTER_COUNT_REG);

		// --- DTU Serial List: map directly to DTU__CONNECTED_MI<n>_SERIAL (String) ---
		for (int mi = 1; mi <= MAX_MICROINVERTERS; mi++) {
			final int index = mi - 1;
			final int reg = DTU_SERIAL_LIST_BASE_REG + (index * DTU_SERIAL_WORDS_PER_MI);

			final ThreeWordHexRegisterElement el = new ThreeWordHexRegisterElement(reg);
			this.dtuSerialListSerials[index] = el;

			//mrdomek Why: bind element to channel so OpenEMS stores the computed string value.
			this.m(PvInverterHoymilesHMSHMT.ChannelId.valueOf("DTU__CONNECTED_MI" + mi + "_SERIAL"), el);
		}

		// -----------------------------------------------------------------------------------------
		// Writes (Per-Port ON/OFF + temporary active power limit in %)
		// We always define all ports (1..99). Effective write port is selected at runtime via DTU topology.
		// -----------------------------------------------------------------------------------------
		for (int port = 1; port <= MAX_PORTS; port++) {
			final int addr = 0xD006 + (port - 1) * 0x0006;
			this.portOnOffByPort[port - 1] = new SignedWordElement(addr + 0x0000);
			this.portTempLimitActivePowerByPort[port - 1] = new SignedWordElement(addr + 0x0001);
		}

		// Default pointer: Port 1 (will be corrected by updateWritePortFromDtuTopologyIfDue())
		this.selectedWritePort = 1;
		this.portOnOff = this.portOnOffByPort[0];
		this.portTempLimitActivePower = this.portTempLimitActivePowerByPort[0];

		// -----------------------------------------------------------------------------------------
		// Channel mappings (Read)
		// -----------------------------------------------------------------------------------------
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL, mi1Serial);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH, totalProductionWh);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TODAY_PRODUCTION_WH, todayProductionWh);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W, activePower,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_REACTIVE_POWER_VAR, reactivePower,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_POWER_FACTOR, powerFactor,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_3);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_mV, vphA,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_mV, vphB,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_mV, vphC,
				ElementToChannelConverter.SCALE_FACTOR_2);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L1_L2_mV, uab,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L2_L3_mV, ubc,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_VOLTAGE_L3_L1_mV, uca,
				ElementToChannelConverter.SCALE_FACTOR_2);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L1_mA, iphA,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L2_mA, iphB,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L3_mA, iphC,
				ElementToChannelConverter.SCALE_FACTOR_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_GRID_FREQUENCY_mHz, frequency,
				ElementToChannelConverter.SCALE_FACTOR_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TEMPERATURE_C, temperature,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_VOLTAGE_mV, pv1Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_VOLTAGE_mV, pv2Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_VOLTAGE_mV, pv3Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_VOLTAGE_mV, pv4Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_VOLTAGE_mV, pv5Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_VOLTAGE_mV, pv6Voltage,
				ElementToChannelConverter.SCALE_FACTOR_2);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_CURRENT_mA, pv1Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_CURRENT_mA, pv2Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_CURRENT_mA, pv3Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_CURRENT_mA, pv4Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_CURRENT_mA, pv5Current,
				ElementToChannelConverter.SCALE_FACTOR_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_CURRENT_mA, pv6Current,
				ElementToChannelConverter.SCALE_FACTOR_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV1_POWER_W, pv1Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV2_POWER_W, pv2Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV3_POWER_W, pv3Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV4_POWER_W, pv4Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV5_POWER_W, pv5Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_PV6_POWER_W, pv6Power,
				ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE, status);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE, alarm1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE, alarm2);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE, alarm3);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE, alarm4);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE, alarm5);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE, alarm6);

		/*
		 * "Last sent" percent limit:
		 * this is a UI/debug mirror; the DTU does not provide a readback register here.
		 */
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT, this.portTempLimitActivePower);

		// -----------------------------------------------------------------------------------------
		// DTU topology channel mappings (Read)
		// NOTE: registers are read via tasks below; without these mappings the channels stay UNDEFINED.
		// -----------------------------------------------------------------------------------------

		// FC04 @ 0x3004 (input register): number of registered microinverters
		this.m(PvInverterHoymilesHMSHMT.ChannelId.DTU_REGISTERED_MICROINVERTERS, this.dtuRegisteredMicroinverterCount);

		// -----------------------------------------------------------------------------------------
		// Protocol tasks (Read + Write)
		// -----------------------------------------------------------------------------------------
		final List<Task> tasks = new ArrayList<>();

		// Main MI data (fast)
		tasks.add(new FC4ReadInputRegistersTask(base, Priority.HIGH,
				mi1Serial,
				totalProductionWh, todayProductionWh,
				activePower, reactivePower, powerFactor,
				vphA, vphB, vphC,
				uab, ubc, uca,
				iphA, iphB, iphC,
				frequency, temperature,
				pv1Voltage, pv1Current, pv1Power,
				pv2Voltage, pv2Current, pv2Power,
				pv3Voltage, pv3Current, pv3Power,
				pv4Voltage, pv4Current, pv4Power,
				pv5Voltage, pv5Current, pv5Power,
				pv6Voltage, pv6Current, pv6Power,
				status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6));

		// DTU topology (slow)
		//mrdomek Read inverter count with HIGH priority so mapping becomes available early after startup.
		tasks.add(new FC4ReadInputRegistersTask(DTU_REGISTERED_MICROINVERTER_COUNT_REG, Priority.HIGH,
				this.dtuRegisteredMicroinverterCount));

		/*
		 * Serial list: We only need prefixes up to the configured microinverterNumber
		 * (for write-port mapping). Each MI is 3 words.
		 *
		 * This keeps reads minimal and avoids Modbus max-register limits without needing dynamic tasks.
		 */
		//mrdomek Why: Protocol task list is static; we cannot depend on runtime value of 0x3004 here.
		//mrdomek Why: To guarantee that DTU__CONNECTED_MIxx_SERIAL channels can be populated for all registered devices,
		//mrdomek      we read the whole DTU serial list (LOW priority, chunked).
		/*
		 * Serial list: We only need prefixes up to the configured microinverterNumber (for write-port mapping).
		 * Each MI is 3 words.
		 *
		 * This keeps reads minimal; remaining chunks are not even scheduled.
		 */
		final int miNeeded = Math.max(0, Math.min(this.microinverterNumber, MAX_MICROINVERTERS));
		final int serialWordsToRead = miNeeded * DTU_SERIAL_WORDS_PER_MI;
		this.addDtuSerialListReadTasksLimited(tasks, Priority.LOW, serialWordsToRead);

		// Writes (only if not readOnly)
		if (!this.config.readOnly()) {
			for (int port = 1; port <= MAX_PORTS; port++) {
				final int addr = 0xD006 + (port - 1) * 0x0006;
				tasks.add(new FC16WriteRegistersTask(addr,
						this.portOnOffByPort[port - 1],
						this.portTempLimitActivePowerByPort[port - 1]));
			}
		}

		return new ModbusProtocol(this, tasks.toArray(new Task[0]));
	}

	@Override
	public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
		return new ModbusSlaveTable(//
				OpenemsComponent.getModbusSlaveNatureTable(accessMode), //
				ElectricityMeter.getModbusSlaveNatureTable(accessMode), //
				ManagedSymmetricPvInverter.getModbusSlaveNatureTable(accessMode));
	}

	@Override
	public MeterType getMeterType() {
		return this.config != null && this.config.useAsProductionMeter()
				? MeterType.PRODUCTION
				: MeterType.CONSUMPTION_NOT_METERED;
	}

	@Override
	public String debugLog() {
		if (!INTERNAL_DEBUG) {
			return "";
		}

		StringBuilder sb = new StringBuilder();

		sb.append("MI#").append(this.microinverterNumber);

		DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
		PvInverterHoymilesHMSHMT.Phase phase = (this.config != null) ? this.config.phase() : null;
		boolean threePhaseDevice = model != null && model.isThreePhase();

		Integer maxTotalPowerW = null;
		Integer minPercent = null;
		if (model != null) {
			maxTotalPowerW = Integer.valueOf(model.getMaxTotalPowerW());
			if (model.getGeneration() != null) {
				minPercent = Integer.valueOf(model.getGeneration().getMinPercent());
			}
		}

		sb.append("|model=").append(model != null ? model.name() : "-");
		sb.append("|genMin%=").append(minPercent != null ? minPercent : "-");
		sb.append("|3ph=").append(threePhaseDevice ? "Y" : "N");
		sb.append("|phase=").append(phase != null ? phase.name() : "-");
		sb.append("|maxP=").append(maxTotalPowerW != null ? maxTotalPowerW : "-").append("W");

		String pAcW = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W)
				.value().asString();
		String limitW = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W)
				.value().asString();
		String limitPercentCh = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
				.value().asString();

		sb.append("|P_ac=").append(pAcW);
		sb.append("|LimitW_ch=").append(limitW);
		sb.append("|Limit%_ch=").append(limitPercentCh);

		Integer lastEffW = this.powerLimitHandler.getLastTargetLimitW();
		Short lastPct = this.powerLimitHandler.getLastWrittenLimitPercent();

		sb.append("|lastEffW=").append(lastEffW != null ? lastEffW : "-");
		sb.append("|lastPct=").append(lastPct != null ? lastPct : "-");
		sb.append("|portOn=").append(
				this.lastWrittenPortOn != null ? (this.lastWrittenPortOn.booleanValue() ? "1" : "0") : "-");

		String health = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HEALTH_STATE)
				.value().asString();
		String interpretedStatus = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_INTERPRETED_STATUS)
				.value().asString();
		String alarmSummary = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM_SUMMARY)
				.value().asString();

		sb.append("|health=").append(health);
		sb.append("|status=").append(interpretedStatus);
		sb.append("|alarms=").append(alarmSummary);

		return sb.toString();
	}

	public String collectDebugData() {
		return Stream.of(
				PvInverterHoymilesHMSHMT.ChannelId.values(),
				OpenemsComponent.ChannelId.values(),
				ModbusComponent.ChannelId.values(),
				io.openems.edge.meter.api.ElectricityMeter.ChannelId.values(),
				io.openems.edge.pvinverter.api.ManagedSymmetricPvInverter.ChannelId.values()
		)
				.flatMap(Arrays::stream)
				.map(id -> {
					try {
						return id.name() + "=" + this.channel(id).value().asString();
					} catch (Exception e) {
						return id.name() + "=n/a";
					}
				})
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
}
