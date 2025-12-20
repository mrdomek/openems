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
     *
     * Wenn sich der eingehende Sollwert (nach Clamping auf Mindestleistung)
     * gegenüber dem letzten übernommenen Wert um weniger als diesen Betrag
     * ändert, wird kein neuer Modbus-Schreibvorgang ausgelöst.
     */
    private static final int LIMIT_HYSTERESIS_W = 100;
    
 // --- PV limit (controller -> percent write) ---
    private volatile Integer pendingLimitW = null; // last limit request from controller (W)
    private volatile long lastLimitWriteMs = 0L;   // throttle timestamp
    private volatile Integer lastLimitPercent = null; // last percent we actually scheduled to write
    private static final long LIMIT_WRITE_MIN_INTERVAL_MS = 5_000L; // do not write faster than every 5s

    
  //mrdomek Rate-limit Modbus writes to the DTU to avoid stalling live data.
    private static final long MIN_LIMIT_WRITE_INTERVAL_MS = 5_000L;

    // Timestamp of last scheduled limit write (percent) to Modbus.
    private long lastLimitWriteTimestampMs = 0L;
    

    // Selected microinverter number (1..99); used to shift the register block.
    private int microinverterNumber = 1;
    
    // Per-Port ON/OFF (0xD006 + 6*(port-1)) und temporary active power limit (0xD007 + 6*(port-1)).
    // Werden nur verwendet, wenn readOnly == false.
    private SignedWordElement portOnOff;
    private SignedWordElement portTempLimitActivePower;

    // Zuletzt auf den Bus geschriebene Werte, um unnötige Schreibvorgänge zu vermeiden.
    private Boolean lastWrittenPortOn = null;
    private Short lastWrittenLimitPercent = null;
    
    
  //mrdomek Cache the latest requested limit in W because Channel#setNextValue is not readable via value() in the same cycle.
    private Integer requestedLimitW = null;


    // Zuletzt "akzeptierter" Zielwert in W für die Limitierung (für Hysterese).
    // Wird verwendet, um kleine Änderungen (z.B. +-100 W) zu ignorieren.
    private Integer lastTargetLimitW = null;

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

        //mrdomek Serial is 3x uint16 words (hex); convert to string each cycle, independent of power validity.
        this.updateMi1SerialFromWords();
        
     // Aktive Leistungsbegrenzung anwenden, falls nicht im Read-Only-Modus
        if (!this.config.readOnly()) {
            this.applyActivePowerLimitFromChannel();
        }


        /*
         * Get total AC active power from microinverter block.
         * This is already scaled to W via Modbus mapping.
         */
        Optional<?> pOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W)
                .value()
                .asOptional();

        if (!pOpt.isPresent() || !(pOpt.get() instanceof Number)) {
            return;
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
        /*
         * Semantik:
         * - power == null  -> externes Limit wird entfernt
         * - power >= 0     -> neuer Sollwert in W
         *
         * Wichtig:
         * - OpenEMS-typisch kommt der wirksame Sollwert über
         *   ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT.
         * - Wir spiegeln den Wert zusätzlich nach MI1_LIMIT_ACTIVE_POWER_W,
         *   damit debugLog() / UI sauber etwas anzeigen können.
         */

        final Integer normalized = (power == null) ? null : Integer.valueOf(Math.max(0, power.intValue()));

        this.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT).setNextValue(normalized);
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W).setNextValue(normalized);

        //mrdomek Debug-only: prove that the manager/controller actually calls this method.
        if (this.config != null && this.config.debugMode()) {
            this.logInfo(this.log, String.format("Hoymiles: setActivePowerLimit() received [%s W].",
                    normalized == null ? "null" : normalized.toString()));
        }

        if (normalized == null) {
            // Hysterese-Zustand zurücksetzen; den Reset auf 100 % macht
            // applyActivePowerLimitFromChannel() beim nächsten Zyklus.
            this.lastTargetLimitW = null;
        }
    }
    
    /**
     * Split total active power to phase powers according to device type and
     * configured phase.
     *
     * threePhaseDevice == false (HMS, single-phase):
     *   - L1/L2/L3 -> all power on the selected phase.
     *
     * threePhaseDevice == true (HMT, three-phase):
     *   - power is evenly distributed to all three phases;
     *     rounding differences are applied to L3.
     */
    private static int[] splitPowerByPhase(boolean threePhaseDevice,
            PvInverterHoymilesHMSHMT.Phase phase, int totalPower) {

        int pL1 = 0;
        int pL2 = 0;
        int pL3 = 0;

        if (threePhaseDevice) {
            // Three-phase HMT: always distribute across all three phases
            int perPhase = totalPower / 3;
            pL1 = perPhase;
            pL2 = perPhase;
            pL3 = totalPower - pL1 - pL2; // carry rounding to L3
            return new int[] { pL1, pL2, pL3 };
        }

        // Single-phase HMS: respect configured phase
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
            // Fallback: everything on L1
            pL1 = totalPower;
            break;
        }

        return new int[] { pL1, pL2, pL3 };
    }

    /**
     * Calculate DC utilization for a PV input:
     * utilization[%] = (PV_power_W / module_peak_W) * 100.
     *
     * If no peak is configured (<= 0) or no valid power is available,
     * the utilization channel is set to null.
     */
    private void updatePvUtilization(PvInverterHoymilesHMSHMT.ChannelId powerChannelId,
            PvInverterHoymilesHMSHMT.ChannelId utilizationChannelId, int modulePeakPowerW) {

        // No module configured -> no utilization value
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

        // Protect against negative values; clamp at 0
        if (powerW < 0) {
            powerW = 0;
        }

        double percent = (powerW / (double) modulePeakPowerW) * 100.0;
        int percentRounded = (int) Math.round(percent);

        this.channel(utilizationChannelId).setNextValue(percentRounded);
    }

    /**
     * Derive a simple health state and aggregated alarm flag from the
     * status and alarm registers.
     *
     * - NO_DATA: no valid status/alarm values available
     * - FAULT  : at least one alarm register != 0
     * - WARNING: no alarm, but status code != 0
     * - OK     : status == 0 and all alarm registers == 0
     */
    private void updateHealthFromStatusAndAlarms() {
        boolean hasData = false;
        boolean hasAlarm = false;
        Integer status = null;

        // Read status code
        Optional<?> statusOpt = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE)
                .value()
                .asOptional();
        if (statusOpt.isPresent() && statusOpt.get() instanceof Number) {
            status = ((Number) statusOpt.get()).intValue();
            hasData = true;
        }

        // Read alarm registers 1..6
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
                    // kein break: wir lesen alle, um Cache aktuell zu halten
                }
            }
        }

        String health;
        if (!hasData) {
            health = "NO_DATA";
        } else if (hasAlarm) {
            health = "FAULT";
        } else if (status != null && status != 0) {
            // Kann später verfeinert werden, wenn Status-Code-Mapping bekannt ist.
            health = "WARNING";
        } else {
            health = "OK";
        }

        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HAS_ALARM).setNextValue(hasAlarm);
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_HEALTH_STATE).setNextValue(health);
    }

    /**
     * Check if any of the Hoymiles alarm codes is non-zero.
     */
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

    /**
     * Read an integer channel or return defaultValue if not present/invalid.
     */
    private int readIntChannelOrDefault(PvInverterHoymilesHMSHMT.ChannelId channelId, int defaultValue) {
        Optional<?> opt = this.channel(channelId).value().asOptional();
        if (!opt.isPresent() || !(opt.get() instanceof Number)) {
            return defaultValue;
        }
        return ((Number) opt.get()).intValue();
    }

    /**
     * Very coarse interpretation of Hoymiles status for UI / "Ampel".
     *
     * This does NOT yet map exact vendor codes – that can be refined once
     * we implement a full enum based on the official documentation.
     *
     * Current rule of thumb:
     * - hasAlarm           -> "ERROR"
     * - !hasAlarm + P > 0  -> "PRODUCING"
     * - !hasAlarm + P == 0 -> "STANDBY" (or "OFF")
     */
    private static String interpretHoymilesStatus(int totalPower, int rawStatusCode, boolean hasAlarm) {
        if (hasAlarm) {
            return "ERROR";
        }

        if (totalPower > 0) {
            return "PRODUCING";
        }

        // totalPower == 0: differentiate a bit using rawStatusCode if needed
        // For now keep it simple, can be refined later.
        if (rawStatusCode == 0) {
            return "STANDBY";
        }

        return "OFF_OR_UNKNOWN";
    }
    
    /**
     * Read status + alarm codes, combine all bits and write a compact
     * alarm summary string to MI1_ALARM_SUMMARY.
     */
    private void updateAlarmSummary() {
        // Read all relevant 16-bit words (treat missing/null as 0)
        int status = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE);
        int alarm1 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE);
        int alarm2 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE);
        int alarm3 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE);
        int alarm4 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE);
        int alarm5 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE);
        int alarm6 = getWordChannelOrZero(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE);

        // Combine all bits (status + alarm1..6)
        int combined = (status | alarm1 | alarm2 | alarm3 | alarm4 | alarm5 | alarm6) & 0xFFFF;

        // Build short string like "BIT0,BIT3" or "NO_ALARM"
        String summary = HoymilesAlarmBit.toShortString(combined);

        // Write to channel for UI
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM_SUMMARY).setNextValue(summary);
    }

    /**
     * Helper: read a 16-bit status/alarm channel as int.
     * Returns 0 if the channel is null or not a Number.
     */
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
    
    
        
    /**
     * Helper: set per-port ON/OFF register.
     *
     * According to Hoymiles documentation:
     * 0 = OFF / stop generating
     * 1 = ON / normal operation
     *
     * Es wird nur geschrieben, wenn sich der Zustand tatsächlich geändert hat,
     * um den Modbus-Bus und die DTU zu schonen.
     */
    private void setPortOnOff(boolean on) {
        if (this.portOnOff == null) {
            return;
        }

        // Wenn wir denselben Zustand bereits geschrieben haben -> nichts tun
        if (this.lastWrittenPortOn != null && this.lastWrittenPortOn.booleanValue() == on) {
            return;
        }

        short value = (short) (on ? 1 : 0);
        this.portOnOff.setNextWriteValue(Short.valueOf(value));
        this.lastWrittenPortOn = Boolean.valueOf(on);
    }

    /**
     * Apply the active power limit in W from channel MI1_LIMIT_ACTIVE_POWER_W
     * to the Hoymiles per-port temporary limit register as percentage.
     *
     * Skalierung:
     * - Basis: maxTotalPowerW aus dem konfigurierten DeviceModel
     * - percent = round(effectiveTargetW / maxTotalPowerW * 100)
     *
     * Zusätzliche Logik (generisch für alle Generationen):
     * - DeviceGeneration.getMinPercent() liefert den unteren Arbeitsbereich [%]
     *   z.B.:
     *      GEN2: 10–100 %
     *      GEN3:  2–100 %
     *
     * Verhalten:
     * - targetLimitW <= 0:
     *      → Wechselrichter AUS (einziger echter OFF-Fall)
     * - targetLimitW > 0:
     *      → auf minW klemmen, falls targetLimitW < minW
     *         (minW = round(maxTotalPowerW * minPercent / 100))
     *
     * Hysterese:
     * - Kleine Änderungen am Sollwert (in W) sollen nicht sofort neue
     *   Modbus-Schreibvorgänge auslösen.
     * - Wenn |effectiveTargetW - lastTargetLimitW| < LIMIT_HYSTERESIS_W, wird der neue
     *   Sollwert ignoriert und nichts geschrieben.
     *
     * Schreib-Optimierung:
     * - portOnOff wird nur geschrieben, wenn sich der Zustand geändert hat.
     * - portTempLimitActivePower wird nur geschrieben, wenn sich der Prozentwert
     *   gegenüber lastWrittenLimitPercent geändert hat.
     *
     * Fallback bei deaktivierten Controllern:
     * - Wenn ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT INVALID ist
     *   (kein Number), gilt: kein aktiver Controller → auf 100 % zurücksetzen.
     *
     * Hinweis:
     * - Detailinformationen zur Limit-Logik werden zentral über debugLog()
     *   für ctrlDebugLog0 bereitgestellt.
     */
    private void applyActivePowerLimitFromChannel() {
        if (this.portTempLimitActivePower == null) {
            return; // sollte nicht passieren
        }

        final long now = System.currentTimeMillis();
        final boolean canWriteNow = (now - this.lastLimitWriteTimestampMs) >= MIN_LIMIT_WRITE_INTERVAL_MS;

        /*
         * Fallback: kein aktiver Controller / Manager.
         *
         * Konvention (OpenEMS-typisch):
         * - Der Manager schreibt den aggregierten Limit-Wert in
         *   ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT.
         * - Ist dieser Channel INVALID (kein Number), gibt es aktuell
         *   keinen gültigen Limit-Vorgabewert eines Controllers.
         *
         * In diesem Fall:
         * - WR sicher EIN schalten
         * - 100 % Limit in das Hoymiles-Register schreiben
         * - interne Hysterese-Zustände zurücksetzen
         *
         * Dadurch fällt der Wechselrichter bei deaktivierten
         * Controllern automatisch auf "volle Leistung" zurück.
         */
        Optional<?> opt = this.channel(ManagedSymmetricPvInverter.ChannelId.ACTIVE_POWER_LIMIT)
                .value()
                .asOptional();

        /*
         * Kein Sollwert vom Controller:
         * - Bedeutet "kein externes Limit" -> wir wollen wieder auf 100 % zurück.
         * - Aber nur dann schreiben, wenn vorher wirklich ein Limit gesetzt war
         *   (lastWrittenLimitPercent != null && != 100).
         */
        if (!opt.isPresent() || !(opt.get() instanceof Number)) {
            // Für UI/Debug sichtbar machen
            this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W).setNextValue(null);

            if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() != 100) {
                DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
                int maxTotalPowerW = (model != null) ? model.getMaxTotalPowerW() : 0;

                if (maxTotalPowerW > 0) {
                    // WR EIN + 100 %
                    this.setPortOnOff(true);
                    short pct = 100;

                    // Channel immer setzen (UI), Write nur rate-limited
                    this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                            .setNextValue(Integer.valueOf(pct));

                    //mrdomek Enforce rate-limit for DTU writes (percent limit).
                    if (canWriteNow) {
                        this.portTempLimitActivePower.setNextWriteValue(Short.valueOf(pct));
                        this.lastWrittenLimitPercent = Short.valueOf(pct);
                        this.lastLimitWriteTimestampMs = now;
                    }
                }
            }

            // Kein aktives Limit -> Hysterese zurücksetzen
            this.lastTargetLimitW = null;
            return;
        }

        int targetLimitW = Math.max(0, ((Number) opt.get()).intValue());

        // Für UI/Debug spiegeln (damit nicht mehr UNDEFINED)
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W)
                .setNextValue(Integer.valueOf(targetLimitW));

        DeviceModel model = (this.config != null) ? this.config.deviceModel() : null;
        int maxTotalPowerW = (model != null) ? model.getMaxTotalPowerW() : 0;
        int minPercent = 0;
        if (model != null && model.getGeneration() != null) {
            minPercent = model.getGeneration().getMinPercent(); // z.B. 10 oder 2
        }

        /*
         * Kein sinnvoller Max-Wert bekannt -> nur einfache Ein/Aus-Logik.
         * Hier gibt es keine echte %-Limitierung, nur ON/OFF.
         */
        if (maxTotalPowerW <= 0) {
            if (targetLimitW <= 0) {
                // 0 W -> Wechselrichter AUS
                this.setPortOnOff(false);
                this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                        .setNextValue(0);
            } else {
                // >0 W -> Wechselrichter EIN, aber ohne aktive Limitierung
                this.setPortOnOff(true);
                this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                        .setNextValue(null);
            }

            // Keine Prozent-Limits im Gerät hinterlegt
            this.lastWrittenLimitPercent = null;
            this.lastTargetLimitW = null;
            return;
        }

        /*
         * Explizit 0 W -> WR AUS.
         * Das ist der einzige Fall, in dem wirklich abgeschaltet wird.
         */
        if (targetLimitW <= 0) {
            this.setPortOnOff(false);
            this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                    .setNextValue(0);
            this.lastWrittenLimitPercent = null;
            this.lastTargetLimitW = null;
            return;
        }

        /*
         * Mindestleistung in W aus DeviceGeneration ableiten.
         * minPercent kommt direkt aus DeviceGeneration (GEN2, GEN3, future).
         */
        int minW = 0;
        if (minPercent > 0) {
            double minWExact = (maxTotalPowerW * (double) minPercent) / 100.0;
            minW = (int) Math.round(minWExact);
            if (minW <= 0) {
                minW = 1; // Sicherheitsnetz, falls Rundung 0 ergäbe
            }
        }

        int effectiveTargetW = targetLimitW;

        // Wenn Sollwert kleiner als minimale Regelbarkeit ist, auf minW klemmen.
        if (minW > 0 && targetLimitW < minW) {
            effectiveTargetW = minW;
        }

        /*
         * Hysterese in W:
         * Nur dann neue Werte schreiben, wenn sich der effektive Sollwert
         * um mindestens LIMIT_HYSTERESIS_W verändert hat.
         */
        if (this.lastTargetLimitW != null) {
            int deltaW = Math.abs(effectiveTargetW - this.lastTargetLimitW.intValue());
            if (deltaW < LIMIT_HYSTERESIS_W) {
                return;
            }
        }

        /*
         * W -> % umrechnen.
         */
        double ratio = (double) effectiveTargetW / (double) maxTotalPowerW;
        double percD = ratio * 100.0;
        int percent = (int) Math.round(percD);

        if (percent > 100) {
            percent = 100;
        }
        if (minPercent > 0 && percent < minPercent) {
            // zusätzliche Absicherung gegen Rundungsfehler
            percent = minPercent;
        }

        // Normalfall: innerhalb des Bereichs -> WR EIN + Limit
        this.setPortOnOff(true);

        // Channel immer setzen (UI/Debug), Write nur wenn geändert + rate-limited
        this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                .setNextValue(Integer.valueOf(percent));

        short newPercentShort = (short) percent;

        // Nur schreiben, wenn sich der Prozentwert tatsächlich geändert hat
        if (this.lastWrittenLimitPercent == null
                || this.lastWrittenLimitPercent.shortValue() != newPercentShort) {

            //mrdomek Enforce rate-limit for DTU writes (percent limit).
            if (!canWriteNow) {
                return;
            }

            this.portTempLimitActivePower.setNextWriteValue(Short.valueOf(newPercentShort));
            this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
            this.lastLimitWriteTimestampMs = now;
        }

        // Diesen effektiven Sollwert in W als Basis für die nächste Hysterese merken
        this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
    }

    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    // Modbus / Meter
    // ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

    @Override
    protected ModbusProtocol defineModbusProtocol() {
        /*
         * Hoymiles "Realtime microinverter data" block:
         *
         * MI1 base address: 0x38E0
         * MI2 base address: 0x3940
         * -> Block size per MI: 0x60 (96 words)
         */
        final int base = 0x38E0 + (this.microinverterNumber - 1) * MI_REGISTER_BLOCK_SIZE;

        /*
         * Per-Port Status-/Limit-Register:
         * 0xD006 ff.
         */
        final int portBase = 0xD006 + (this.microinverterNumber - 1) * 0x0006;

        /*
         * Modbus elements
         */
        // Serial number: 3 words at base (0x38E0..0x38E2)
        //mrdomek Do NOT decode as ASCII; read 3x uint16 and convert to hex string in handleEvent().
        final SignedWordElement serialW0 = new SignedWordElement(base + 0x00); // 0x38E0
        final SignedWordElement serialW1 = new SignedWordElement(base + 0x01); // 0x38E1
        final SignedWordElement serialW2 = new SignedWordElement(base + 0x02); // 0x38E2

        // Production counters
        final UnsignedDoublewordElement totalProductionWh = new UnsignedDoublewordElement(base + 0x03); // 0x38E3..0x38E4
        final UnsignedDoublewordElement todayProductionWh = new UnsignedDoublewordElement(base + 0x05); // 0x38E5..0x38E6

        // Realtime block starting at 0x38E7 (Active power)
        final SignedWordElement activePower = new SignedWordElement(base + 0x07);   // 0x38E7
        final SignedWordElement reactivePower = new SignedWordElement(base + 0x08); // 0x38E8
        final SignedWordElement powerFactor = new SignedWordElement(base + 0x09);   // 0x38E9

        final SignedWordElement vphA = new SignedWordElement(base + 0x0A);          // 0x38EA
        final SignedWordElement vphB = new SignedWordElement(base + 0x0B);          // 0x38EB
        final SignedWordElement vphC = new SignedWordElement(base + 0x0C);          // 0x38EC

        final SignedWordElement uab = new SignedWordElement(base + 0x0D);           // 0x38ED
        final SignedWordElement ubc = new SignedWordElement(base + 0x0E);           // 0x38EE
        final SignedWordElement uca = new SignedWordElement(base + 0x0F);           // 0x38EF

        final SignedWordElement iphA = new SignedWordElement(base + 0x10);          // 0x38F0
        final SignedWordElement iphB = new SignedWordElement(base + 0x11);          // 0x38F1
        final SignedWordElement iphC = new SignedWordElement(base + 0x12);          // 0x38F2

        final SignedWordElement frequency = new SignedWordElement(base + 0x13);     // 0x38F3
        final SignedWordElement temperature = new SignedWordElement(base + 0x14);   // 0x38F4

        // PV registers
        final SignedWordElement pv1Voltage = new SignedWordElement(base + 0x15);    // 0x38F5
        final SignedWordElement pv1Current = new SignedWordElement(base + 0x16);    // 0x38F6
        final SignedWordElement pv1Power = new SignedWordElement(base + 0x17);      // 0x38F7

        final SignedWordElement pv2Voltage = new SignedWordElement(base + 0x18);    // 0x38F8
        final SignedWordElement pv2Current = new SignedWordElement(base + 0x19);    // 0x38F9
        final SignedWordElement pv2Power = new SignedWordElement(base + 0x1A);      // 0x38FA

        final SignedWordElement pv3Voltage = new SignedWordElement(base + 0x1B);    // 0x38FB
        final SignedWordElement pv3Current = new SignedWordElement(base + 0x1C);    // 0x38FC
        final SignedWordElement pv3Power = new SignedWordElement(base + 0x1D);      // 0x38FD

        final SignedWordElement pv4Voltage = new SignedWordElement(base + 0x1E);    // 0x38FE
        final SignedWordElement pv4Current = new SignedWordElement(base + 0x1F);    // 0x38FF
        final SignedWordElement pv4Power = new SignedWordElement(base + 0x20);      // 0x3900

        final SignedWordElement pv5Voltage = new SignedWordElement(base + 0x21);    // 0x3901
        final SignedWordElement pv5Current = new SignedWordElement(base + 0x22);    // 0x3902
        final SignedWordElement pv5Power = new SignedWordElement(base + 0x23);      // 0x3903

        final SignedWordElement pv6Voltage = new SignedWordElement(base + 0x24);    // 0x3904
        final SignedWordElement pv6Current = new SignedWordElement(base + 0x25);    // 0x3905
        final SignedWordElement pv6Power = new SignedWordElement(base + 0x26);      // 0x3906

        final SignedWordElement status = new SignedWordElement(base + 0x27);        // 0x3907

        final SignedWordElement alarm1 = new SignedWordElement(base + 0x28);        // 0x3908
        final SignedWordElement alarm2 = new SignedWordElement(base + 0x29);        // 0x3909
        final SignedWordElement alarm3 = new SignedWordElement(base + 0x2A);        // 0x390A
        final SignedWordElement alarm4 = new SignedWordElement(base + 0x2B);        // 0x390B
        final SignedWordElement alarm5 = new SignedWordElement(base + 0x2C);        // 0x390C
        final SignedWordElement alarm6 = new SignedWordElement(base + 0x2D);        // 0x390D

        // Per-Port ON/OFF (0xD006 + 6*(port-1)) und "Temporary Limit Active Power" (0xD007 + 6*(port-1))
        this.portOnOff = new SignedWordElement(portBase + 0x0000);
        this.portTempLimitActivePower = new SignedWordElement(portBase + 0x0001);

        /*
         * Channel mapping
         */

        // Serial + Production
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_0, serialW0);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_1, serialW1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_SERIAL_WORD_2, serialW2);

        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TOTAL_PRODUCTION_WH, totalProductionWh);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TODAY_PRODUCTION_WH, todayProductionWh);

        // Active power: 0.1 W/bit -> W (loses precision 0.1W -> 0W, standard OpenEMS int)
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W, activePower,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Reactive power: 0.1 var/bit -> var
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_REACTIVE_POWER_VAR, reactivePower,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // Power factor: 0.001/bit -> Double
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_POWER_FACTOR, powerFactor,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_3);

        // Grid Voltages: 0.1 V/bit -> Millivolt (x100)
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

        // Grid Currents: 0.01 A/bit -> Milliampere (x10)
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L1_mA, iphA,
                ElementToChannelConverter.SCALE_FACTOR_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L2_mA, iphB,
                ElementToChannelConverter.SCALE_FACTOR_1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_AC_CURRENT_L3_mA, iphC,
                ElementToChannelConverter.SCALE_FACTOR_1);

        // Frequency: 0.01 Hz/bit -> Millihertz (x10)
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_GRID_FREQUENCY_mHz, frequency,
                ElementToChannelConverter.SCALE_FACTOR_1);

        // Temperature: 0.1 °C/bit -> °C (rounds to Int)
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_TEMPERATURE_C, temperature,
                ElementToChannelConverter.SCALE_FACTOR_MINUS_1);

        // PV Voltages: 0.1 V/bit -> Millivolt (x100)
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

        // PV Currents: 0.01 A/bit -> Milliampere (x10)
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

        // PV power: 0.1 W/bit -> W (rounds to Int)
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

        // Status + alarms
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_STATUS_CODE, status);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM1_CODE, alarm1);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM2_CODE, alarm2);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM3_CODE, alarm3);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM4_CODE, alarm4);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM5_CODE, alarm5);
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_ALARM6_CODE, alarm6);

        // Per-Port Limit in Prozent
        this.m(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT, this.portTempLimitActivePower);

        return new ModbusProtocol(this,

                // Single read: 0x38E0..0x390D
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

                // Write task: per-port ON/OFF + Temporary Limit Active Power [%]
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
        /*
         * If configured as "production meter", the inverter power will be taken
         * into account for production sums. Otherwise it is only informational.
         */
        return this.config != null && this.config.useAsProductionMeter()
                ? MeterType.PRODUCTION
                : MeterType.CONSUMPTION_NOT_METERED;
    }

    /**
     * Liefert eine kompakte Debug-Zeile für ctrlDebugLog0 mit allen
     * Regel-/Statusinformationen.
     */
    @Override
    public String debugLog() {
        // globaler Schalter
        if (!INTERNAL_DEBUG) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // Basisinfo: MI-Nummer
        sb.append("MI#").append(this.microinverterNumber);

        /*
         * Konfiguration / Enums:
         * - DeviceModel (liefert maxTotalPowerW + Generation)
         * - Generation (liefert minPercent)
         * - Phase + Drei-Phasen-Flag
         */
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

        /*
         * Regelrelevante Kanäle:
         * - aktuelle AC-Leistung
         * - Leistungs-Limit in W (Sollwert vom Controller)
         * - Leistungs-Limit in % (berechnet + geschrieben)
         */
        String pAcW = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_ACTIVE_POWER_W)
                .value().asString();
        String limitW = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_W)
                .value().asString();
        String limitPercentCh = this.channel(PvInverterHoymilesHMSHMT.ChannelId.MI1_LIMIT_ACTIVE_POWER_PERCENT)
                .value().asString();

        sb.append("|P_ac=").append(pAcW);            // z.B. "800 W"
        sb.append("|LimitW_ch=").append(limitW);     // z.B. "500 W"
        sb.append("|Limit%_ch=").append(limitPercentCh); // z.B. "33 %"

        /*
         * Interne Werte der Limit-Regelung:
         * - lastTargetLimitW          = letzter effektiver Zielwert in W (nach minW/Hysterese)
         * - lastWrittenLimitPercent   = zuletzt tatsächlich geschriebener Prozentwert
         * - lastWrittenPortOn         = zuletzt geschriebenes ON/OFF am Port-Register
         */
        sb.append("|lastEffW=").append(this.lastTargetLimitW != null ? this.lastTargetLimitW : "-");
        sb.append("|lastPct=").append(this.lastWrittenLimitPercent != null ? this.lastWrittenLimitPercent : "-");
        sb.append("|portOn=").append(
                this.lastWrittenPortOn != null ? (this.lastWrittenPortOn.booleanValue() ? "1" : "0") : "-");

        /*
         * Zustands-/Alarm-Infos:
         * - Health-State (OK/WARNING/FAULT/NO_DATA)
         * - interpretierter Status (PRODUCING/STANDBY/... )
         * - zusammengefasste Alarmbits
         */
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
    
    

    /**
     * Sammelt alle Channel-Werte.
     * 
     */
    public String collectDebugData() {
        return Stream.of(
                // Eigene Channels
                PvInverterHoymilesHMSHMT.ChannelId.values(),
                // Standard OpenEMS Channels
                OpenemsComponent.ChannelId.values(),
                // Modbus Channels
                ModbusComponent.ChannelId.values(),
                // WICHTIG: Meter Werte (ActivePower, L1, L2, L3)
                io.openems.edge.meter.api.ElectricityMeter.ChannelId.values(),
                // WICHTIG: Inverter Limit Werte (ActivePowerLimit)
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
    
    /**
     * Überschreibt/Nutzt logDebug für erweiterte Ausgaben.
     */
    @Override
    protected void logDebug(Logger log, String message) {
        // Prüfen, ob Debugging generell in der Config aktiv ist
        if (this.config.debugMode()) {
            
            // Prüfen, ob der ERWEITERTE Modus aktiv ist
            if (this.config.extendedDebugMode()) {
                this.logInfo(log, "\n #################### EXTENDED DEBUG START ####################");
                this.logInfo(log, this.collectDebugData());
                this.logInfo(log, "\n #################### EXTENDED DEBUG END ####################");
            }
            
            // Die eigentliche Nachricht loggen
            this.logInfo(log, message);
        }
    }
}
