package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import static io.openems.edge.common.event.EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE;
import static org.osgi.service.component.annotations.ConfigurationPolicy.REQUIRE;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.Arrays;
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
import io.openems.edge.bridge.modbus.api.task.FC4ReadInputRegistersTask;
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

	//mrdomek Cache the last limit received via setActivePowerLimit() (W). null means "no limit".
	private volatile Integer pendingLimitW = null;

	// Selected microinverter number (1..99); used to shift the register block.
	private int microinverterNumber = 1;

	// Per-Port ON/OFF (0xD006 + 6*(port-1)) und temporary active power limit (0xD007 + 6*(port-1)).
	// Werden nur verwendet, wenn readOnly == false.
	private SignedWordElement portOnOff;
	private SignedWordElement portTempLimitActivePower;

	// Zuletzt auf den Bus geschriebene Werte, um unnötige Schreibvorgänge zu vermeiden.
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
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	public void handleEvent(Event event) {
		// React only on cycle write events
		if (!TOPIC_CYCLE_EXECUTE_WRITE.equals(event.getTopic())) {
			return;
		}

		if (this.config == null) {
			return;
		}

		this.updateMetaAndLimits();

		final Integer pTotal = this.updateMeterPowersAndGetTotalPowerW();
		if (pTotal == null) {
			return;
		}

		this.updateStatusAndUtilization(pTotal);
	}

	private void updateMetaAndLimits() {
		//mrdomek Serial is 3x uint16 words (hex); convert to string each cycle, independent of power validity.
		this.updateMi1SerialFromWords();

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

		// Update combined alarm/status summary channel
		updateAlarmSummary();

		// Health-State (Ampel) aus Status- und Alarm-Register ableiten
		updateHealthFromStatusAndAlarms();

		/*
		 * Status / "Ampel"-Logik:
		 * - hasAlarm  -> es liegt irgendein Hoymiles-Alarmcode an
		 * - interpretedStatus -> grobe Interpretation für UI
		 */
		boolean hasAlarm = hasAnyHoymilesAlarm();

		String interpretedStatus = interpretHoymilesStatus(
				pTotal,
				readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE, -1),
				hasAlarm);

		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HAS_ALARM).setNextValue(hasAlarm);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_INTERPRETED_STATUS)
				.setNextValue(interpretedStatus);

		// Trigger für die erweiterte Debug-Ausgabe
		this.logDebug(this.log, "Next Cycle");
	}

	@Override
	public void setActivePowerLimit(Integer power) throws OpenemsNamedException {
		final Integer normalized = (power == null) ? null : Integer.valueOf(Math.max(0, power.intValue()));

		//mrdomek Cache the last value received from controller/manager for immediate use in the same cycle.
		this.pendingLimitW = normalized;

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
			return;
		}

		final long now = System.currentTimeMillis();

		DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
		int maxTotalPowerW = (model != null) ? model.getMaxTotalPowerW() : 0;

		int minPercent = 0;
		if (model != null && model.getGeneration() != null) {
			minPercent = model.getGeneration().getMinPercent();
		}

		final Integer targetLimitW = this.pendingLimitW;

		this.powerLimitHandler.apply(targetLimitW, maxTotalPowerW, minPercent, now, new HoymilesPowerLimitHandler.Actions() {

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

	private void updateHealthFromStatusAndAlarms() {
		boolean hasData = false;
		boolean hasAlarm = false;
		Integer status = null;

		Optional<?> statusOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE)
				.value()
				.asOptional();
		if (statusOpt.isPresent() && statusOpt.get() instanceof Number) {
			status = ((Number) statusOpt.get()).intValue();
			hasData = true;
		}

		PvInverterHoymilesHMSHMT.ChannelId[] alarmIds = new PvInverterHoymilesHMSHMT.ChannelId[] {
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE,
				PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE
		};

		for (PvInverterHoymilesHMSHMT.ChannelId alarmId : alarmIds) {
			Optional<?> alarmOpt = this.channel(alarmId).value().asOptional();
			if (alarmOpt.isPresent() && alarmOpt.get() instanceof Number) {
				int alarmValue = ((Number) alarmOpt.get()).intValue();
				hasData = true;
				if (alarmValue != 0) {
					hasAlarm = true;
				}
			}
		}

		String health;
		if (!hasData) {
			health = "NO_DATA";
		} else if (hasAlarm) {
			health = "FAULT";
		} else if (status != null && status != 0) {
			health = "WARNING";
		} else {
			health = "OK";
		}

		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HAS_ALARM).setNextValue(hasAlarm);
		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HEALTH_STATE).setNextValue(health);
	}

	private boolean hasAnyHoymilesAlarm() {
		return isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE)
				|| isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE)
				|| isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE)
				|| isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE)
				|| isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE)
				|| isNonZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE);
	}

	private boolean isNonZero(PvInverterHoymilesHMSHMT.ChannelId channelId) {
		Optional<?> opt = this.channel(channelId).value().asOptional();
		if (!opt.isPresent() || !(opt.get() instanceof Number)) {
			return false;
		}
		return ((Number) opt.get()).intValue() != 0;
	}

	private int readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId channelId, int defaultValue) {
		Optional<?> opt = this.channel(channelId).value().asOptional();
		if (!opt.isPresent() || !(opt.get() instanceof Number)) {
			return defaultValue;
		}
		return ((Number) opt.get()).intValue();
	}

	private static String interpretHoymilesStatus(int totalPower, int rawStatusCode, boolean hasAlarm) {
		if (hasAlarm) {
			return "ERROR";
		}

		if (totalPower > 0) {
			return "PRODUCING";
		}

		if (rawStatusCode == 0) {
			return "STANDBY";
		}

		return "OFF_OR_UNKNOWN";
	}

	private void updateAlarmSummary() {
		int status = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE);
		int alarm1 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE);
		int alarm2 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE);
		int alarm3 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE);
		int alarm4 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE);
		int alarm5 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE);
		int alarm6 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE);

		int combined = (status | alarm1 | alarm2 | alarm3 | alarm4 | alarm5 | alarm6) & 0xFFFF;

		String summary = HoymilesAlarmBit.toShortString(combined);

		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM_SUMMARY).setNextValue(summary);
	}

	private int getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId channelId) {
		return this.channel(channelId) //
				.value() //
				.asOptional() //
				.map(v -> ((Number) v).intValue()) //
				.orElse(0);
	}

	private void updateMi1SerialFromWords() {
		final Optional<?> w0Opt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_0).value().asOptional();
		final Optional<?> w1Opt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_1).value().asOptional();
		final Optional<?> w2Opt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_2).value().asOptional();

		if (!w0Opt.isPresent() || !w1Opt.isPresent() || !w2Opt.isPresent()) {
			return;
		}
		if (!(w0Opt.get() instanceof Number) || !(w1Opt.get() instanceof Number) || !(w2Opt.get() instanceof Number)) {
			return;
		}

		final int w0 = ((Number) w0Opt.get()).intValue();
		final int w1 = ((Number) w1Opt.get()).intValue();
		final int w2 = ((Number) w2Opt.get()).intValue();

		//mrdomek Serial is 3x uint16; mask avoids negative values from SignedWordElement.
		final String sn = String.format("%04X%04X%04X", (w0 & 0xFFFF), (w1 & 0xFFFF), (w2 & 0xFFFF));

		this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL).setNextValue(sn);
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

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		/*
		 * Microinverter register block selection:
		 * MI1 base: 0x38E0
		 * MI2 base: 0x3940
		 * block size: 0x60 (96 words) per microinverter
		 */
		final int base = 0x38E0 + (this.microinverterNumber - 1) * MI_REGISTER_BLOCK_SIZE;

		/*
		 * Per-port write register block:
		 * base: 0xD006
		 * step: 0x0006 per microinverter
		 */
		final int portBase = 0xD006 + (this.microinverterNumber - 1) * 0x0006;

		// -----------------------------------------------------------------------------------------
		// Serial + Energy
		// -----------------------------------------------------------------------------------------
		final SignedWordElement serialW0 = new SignedWordElement(base + 0x00);
		final SignedWordElement serialW1 = new SignedWordElement(base + 0x01);
		final SignedWordElement serialW2 = new SignedWordElement(base + 0x02);

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
		// Writes (Port ON/OFF + temporary active power limit in %)
		// -----------------------------------------------------------------------------------------
		this.portOnOff = new SignedWordElement(portBase + 0x0000);
		this.portTempLimitActivePower = new SignedWordElement(portBase + 0x0001);

		// -----------------------------------------------------------------------------------------
		// Channel mappings (Read)
		// -----------------------------------------------------------------------------------------
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_0, serialW0);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_1, serialW1);
		this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_2, serialW2);

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
		// Protocol tasks (Read + Write)
		// -----------------------------------------------------------------------------------------
		return new ModbusProtocol(this,

				new FC4ReadInputRegistersTask(base, Priority.HIGH,
						serialW0, serialW1, serialW2,
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
						status, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6),

				new FC16WriteRegistersTask(portBase, this.portOnOff, this.portTempLimitActivePower));
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
