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
import io.openems.edge.common.channel.EnumWriteChannel;
import io.openems.edge.common.channel.FloatWriteChannel;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.IntegerWriteChannel;
import io.openems.edge.common.channel.StateChannel;
import io.openems.edge.common.channel.WriteChannel;
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
        name = "Deye.BatteryInverter",
        immediate = true,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        service = {
            SymmetricEss.class,
            ManagedSymmetricEss.class,
            //HybridEss.class,
            TimedataProvider.class,
            OpenemsComponent.class,
            ModbusComponent.class,
            ModbusSlave.class,
            EventHandler.class
        }
    )
@EventTopics({ //
        EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, //
        EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS //
})
public class DeyeSunHybridImpl extends AbstractOpenemsModbusComponent implements DeyeSunHybrid {

    // Konstanten
    protected static final int MAX_APPARENT_POWER = 40000;
    protected static final int NET_CAPACITY = 10000; // Hardcoded
    private static final int MIN_REACTIVE_POWER = -10000;
    private static final int MAX_REACTIVE_POWER = 10000;
    private static final int POWER_LIMIT_HYSTERESIS = 500;
    private final Logger log = LoggerFactory.getLogger(DeyeSunHybridImpl.class);

    // Referenzen
    @Reference protected ComponentManager componentManager;
    @Reference private Power power;
    @Reference private ConfigurationAdmin cm;
    @Reference(policy = ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.OPTIONAL)
    private volatile Timedata timedata = null;
    private Config config;

    // Energieberechnung
    private final CalculateEnergyFromPower calculateAcChargeEnergy = new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_CHARGE_ENERGY);
    private final CalculateEnergyFromPower calculateAcDischargeEnergy = new CalculateEnergyFromPower(this, SymmetricEss.ChannelId.ACTIVE_DISCHARGE_ENERGY);
    private final CalculateEnergyFromPower calculateDcChargeEnergy = new CalculateEnergyFromPower(this, HybridEss.ChannelId.DC_CHARGE_ENERGY);
    private final CalculateEnergyFromPower calculateDcDischargeEnergy = new CalculateEnergyFromPower(this, HybridEss.ChannelId.DC_DISCHARGE_ENERGY);

    // forceToggle Variable entfernt

    public DeyeSunHybridImpl() {
        super(
                OpenemsComponent.ChannelId.values(),
                ModbusComponent.ChannelId.values(),
                SymmetricEss.ChannelId.values(),
                ManagedSymmetricEss.ChannelId.values(),
                HybridEss.ChannelId.values(),
                DeyeSunHybrid.ChannelId.values(),
                DeyeSunHybrid.SystemErrorChannelId.values(),
                DeyeSunHybrid.InsufficientGridParametersChannelId.values(),
                DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values()
        );
        this._setMaxApparentPower(MAX_APPARENT_POWER);
    }

    @Activate
    private void activate(ComponentContext context, Config config) throws OpenemsException {
        if (super.activate(context, config.id(), config.alias(), config.enabled(), config.unit_id(), this.cm, "Modbus", config.modbus_id())) {
            return;
        }
        this.config = config;

        if (NET_CAPACITY > 0) { this.logInfo(log, "Using hardcoded Net Capacity [" + NET_CAPACITY + " Wh]. Consider adding 'netCapacity' parameter to Config interface."); this._setCapacity(NET_CAPACITY); } else { this.logWarn(log, "Net Capacity is 0 or not set. Battery visualization and some statistics might be inaccurate."); }

        // === Listener für Leistungslimits registrieren (Angepasst für neg. Charge Limit) ===
        IntegerReadChannel calculatedChargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
        Channel<Integer> finalChargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER);
        calculatedChargeLimitChannel.onUpdate((value) -> { Optional<Integer> calculatedOpt = value.asOptional(); Optional<Integer> currentFinalOpt = finalChargeLimitChannel.value().asOptional(); final int finalValue; if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) finalValue = 0; else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) finalValue = calculatedOpt.get(); else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) finalValue = currentFinalOpt.get(); else { int calculatedMagnitude = Math.abs(calculatedOpt.get()); int currentFinalMagnitude = Math.abs(currentFinalOpt.get()); int finalMagnitude = Math.max(calculatedMagnitude, currentFinalMagnitude - POWER_LIMIT_HYSTERESIS); finalValue = -finalMagnitude; } finalChargeLimitChannel.setNextValue(Math.min(0, finalValue)); });

        IntegerReadChannel calculatedDischargeLimitChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
        Channel<Integer> finalDischargeLimitChannel = this.channel(ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER);
        calculatedDischargeLimitChannel.onUpdate((value) -> { Optional<Integer> calculatedOpt = value.asOptional(); Optional<Integer> currentFinalOpt = finalDischargeLimitChannel.value().asOptional(); final int finalValue; if (!calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) finalValue = 0; else if (calculatedOpt.isPresent() && !currentFinalOpt.isPresent()) finalValue = calculatedOpt.get(); else if (!calculatedOpt.isPresent() && currentFinalOpt.isPresent()) finalValue = currentFinalOpt.get(); else finalValue = Math.min(calculatedOpt.get(), currentFinalOpt.get() + POWER_LIMIT_HYSTERESIS); finalDischargeLimitChannel.setNextValue(finalValue); });
        // === Ende Listener Leistungslimits ===


        // === Trigger für zusammengefasste StateChannels registrieren ===
        this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.SYSTEM_ERROR, DeyeSunHybrid.SystemErrorChannelId.values());
        this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.INSUFFICIENT_GRID_PARAMTERS, DeyeSunHybrid.InsufficientGridParametersChannelId.values());
        this.addStateChannelTrigger(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE, DeyeSunHybrid.PowerDecreaseCausedByOvertemperatureChannelId.values());
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


    // applyPower() - Wieder mit Original-Logik
    @Override
    public void applyPower(int activePower, int reactivePower) throws OpenemsNamedException {
        // Wird vom Power-Framework aufgerufen, wenn isManaged() true ist
        if (this.config.readOnlyMode()) {
            // Im Read-Only-Modus nichts tun (oder ggf. SET_...EQUALS auf 0 setzen)
             return;
        }
        // Setze die Werte, die vom Solver kommen
        IntegerWriteChannel setActivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER);
        setActivePowerChannel.setNextWriteValue(activePower);
        IntegerWriteChannel setReactivePowerChannel = this.channel(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER);
        setReactivePowerChannel.setNextWriteValue(reactivePower);
    }

    @Override
    public String getModbusBridgeId() {
        return this.config.modbus_id();
    }

    @Override
    protected ModbusProtocol defineModbusProtocol() {
         return new ModbusProtocol(this,
            new FC3ReadRegistersTask(1, Priority.LOW, m(SymmetricEss.ChannelId.GRID_MODE, new UnsignedWordElement(1)), new DummyRegisterElement(2), m(DeyeSunHybrid.ChannelId.SERIAL_NUMBER, new StringWordElement(3, 5))),
            new FC3ReadRegistersTask(212, Priority.HIGH, m(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT, new SignedWordElement(212)), m(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT, new SignedWordElement(213)), new DummyRegisterElement(214), m(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW, new SignedWordElement(215))),
            new FC3ReadRegistersTask(588, Priority.HIGH, m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(588))),
            new FC3ReadRegistersTask(500, Priority.LOW, m(DeyeSunHybrid.ChannelId.INVERTER_RUN_STATE, new UnsignedWordElement(500))),
            new FC3ReadRegistersTask(590, Priority.HIGH, m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(590))),
            new FC3ReadRegistersTask(620, Priority.LOW, m(DeyeSunHybrid.ChannelId.APPARENT_POWER, new UnsignedWordElement(620))),
            // Hier Fehlerquellen-Register hinzufügen!
            new FC16WriteRegistersTask(1111, m(DeyeSunHybrid.ChannelId.SET_ACTIVE_POWER, new SignedWordElement(1111))),
            new FC16WriteRegistersTask(1118, m(DeyeSunHybrid.ChannelId.SET_REACTIVE_POWER, new SignedWordElement(1118)))
         );
    }


    @Override
    public String debugLog() {
         String soc = this.getSoc().asOptional().map(s -> s + "%").orElse("N/A");
         String activePower = this.getActivePower().asOptional().map(p -> p + "W").orElse("N/A");
         String origCharge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER).value().asOptional().map(String::valueOf).orElse("N/A");
         String origDischarge = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER).value().asOptional().map(String::valueOf).orElse("N/A");
         String finalCharge = this.getAllowedChargePower().asOptional().map(String::valueOf).orElse("N/A"); // Negativ
         String finalDischarge = this.getAllowedDischargePower().asOptional().map(String::valueOf).orElse("N/A"); // Positiv
         String voltage = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE).value().asOptional().map(v -> String.format("%.1fV", v)).orElse("N/A");
         // NextWriteActive entfernt
         return "SoC:" + soc + "|L:" + activePower + "|V:" + voltage
                + "|Allowed(Orig):[Chg:" + origCharge + ";Dschg:" + origDischarge + "]"
                + "|Allowed(Final):[Chg:" + finalCharge + ";Dschg:" + finalDischarge + "] W";
    }

    @Override
    public void handleEvent(Event event) {
        if (!this.isEnabled()) return;
        switch (event.getTopic()) {
        case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
            // Standard-Logik wiederhergestellt
            this.calculateAndUpdatePowerLimits();
            this.applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError();
            this.calculateEnergy();
            // --- TESTCODE ENTFERNT ---
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
            if (this.config != null && this.config.readOnlyMode()) return;
            IntegerWriteChannel setWorkStateChannel = this.channel(DeyeSunHybrid.ChannelId.SET_WORK_STATE);
            setWorkStateChannel.setNextValue(SetWorkState.START.ordinal());
        }
    }

    private void calculateAndUpdatePowerLimits() {
        IntegerReadChannel batteryVoltageRawChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE_RAW);
        IntegerReadChannel chargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_CHARGE_CURRENT_LIMIT);
        IntegerReadChannel dischargeCurrentLimitChannel = this.channel(DeyeSunHybrid.ChannelId.RAW_DISCHARGE_CURRENT_LIMIT);
        Channel<Float> batteryVoltageScaledChannel = this.channel(DeyeSunHybrid.ChannelId.BATTERY_VOLTAGE);
        Channel<Integer> originalAllowedChargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_CHARGE_POWER);
        Channel<Integer> originalAllowedDischargePowerChannel = this.channel(DeyeSunHybrid.ChannelId.ORIGINAL_ALLOWED_DISCHARGE_POWER);
        Optional<Integer> batteryVoltageRawOpt = batteryVoltageRawChannel.value().asOptional();
        Optional<Integer> chargeCurrentLimitOpt = chargeCurrentLimitChannel.value().asOptional();
        Optional<Integer> dischargeCurrentLimitOpt = dischargeCurrentLimitChannel.value().asOptional();
        if (batteryVoltageRawOpt.isPresent() && chargeCurrentLimitOpt.isPresent() && dischargeCurrentLimitOpt.isPresent()) {
            float voltage = batteryVoltageRawOpt.get() / 100.0f;
            batteryVoltageScaledChannel.setNextValue(voltage);
            int chargeCurrentRaw = Math.abs(chargeCurrentLimitOpt.get());
            int dischargeCurrentRaw = Math.abs(dischargeCurrentLimitOpt.get());
            int calculatedChargePowerMagnitude = Math.round(voltage * chargeCurrentRaw);
            int calculatedDischargePowerLimit = Math.round(voltage * dischargeCurrentRaw);
            originalAllowedChargePowerChannel.setNextValue(-calculatedChargePowerMagnitude); // Negativ!
            originalAllowedDischargePowerChannel.setNextValue(calculatedDischargePowerLimit); // Positiv
        } else {
             batteryVoltageScaledChannel.setNextValue(null);
             originalAllowedChargePowerChannel.setNextValue(0);
             originalAllowedDischargePowerChannel.setNextValue(0);
        }
    }


    @Override
    public Power getPower() {
        return this.power;
    }

    // isManaged() - Wieder mit Original-Logik
    @Override
    public boolean isManaged() {
        return this.config != null && !this.config.readOnlyMode();
    }

    @Override
    public int getPowerPrecision() {
        return 1; // Annahme: W/VAR
    }

    @Override
    public Constraint[] getStaticConstraints() throws OpenemsNamedException {
         if (this.config != null && this.config.readOnlyMode()) {
            return new Constraint[] {
                this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.ACTIVE, Relationship.EQUALS, 0),
                this.createPowerConstraint("Read-Only-Mode", Phase.ALL, Pwr.REACTIVE, Relationship.EQUALS, 0) };
        }
        int maxActivePower = MAX_APPARENT_POWER;
        return new Constraint[] {
            this.createPowerConstraint("Deye Min Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.GREATER_OR_EQUALS, -maxActivePower),
            this.createPowerConstraint("Deye Max Active Power", Phase.ALL, Pwr.ACTIVE, Relationship.LESS_OR_EQUALS, maxActivePower),
            this.createPowerConstraint("Deye Min Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.GREATER_OR_EQUALS, MIN_REACTIVE_POWER),
            this.createPowerConstraint("Deye Max Reactive Power", Phase.ALL, Pwr.REACTIVE, Relationship.LESS_OR_EQUALS, MAX_REACTIVE_POWER) };
    }

    @Override
    public ModbusSlaveTable getModbusSlaveTable(AccessMode accessMode) {
         return new ModbusSlaveTable(
                OpenemsComponent.getModbusSlaveNatureTable(accessMode),
                SymmetricEss.getModbusSlaveNatureTable(accessMode),
                ManagedSymmetricEss.getModbusSlaveNatureTable(accessMode),
                ModbusSlaveNatureTable.of(DeyeSunHybrid.class, accessMode, 100).build()
        );
    }

    // --- Log-Methoden ---
    @Override protected void logInfo(Logger log, String message) { super.logInfo(log, message); }
    @Override protected void logWarn(Logger log, String message) { super.logWarn(log, message); }
    @Override protected void logError(Logger log, String message) { super.logError(log, message); }

    /**
     * Wendet Overtemperature Limits an. Setzt ALLOWED_CHARGE_POWER negativ!
     */
    private void applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError() {
        if (this.config != null && this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel() > 0) {
            StateChannel errorChannel = this.channel(DeyeSunHybrid.ChannelId.POWER_DECREASE_CAUSED_BY_OVERTEMPERATURE);
            if (errorChannel.value().orElse(false)) {
                int limit = Math.abs(this.config.powerLimitOnPowerDecreaseCausedByOvertemperatureChannel());
                this.logWarn(this.log, String.format("[%s] Overtemperature constraint active! OVERRIDING AllowedCharge to %d W / Discharge to %d W", this.id(), -limit, limit));
                this._setAllowedChargePower(-limit); // Negativ setzen!
                this._setAllowedDischargePower(limit); // Positiv setzen
            }
        }
    }

    @Override
    public Timedata getTimedata() {
        return this.timedata;
    }

    private void calculateEnergy() {
        Optional<Integer> acActivePowerOpt = this.getActivePowerChannel().value().asOptional();
        if (acActivePowerOpt.isPresent()) { int acPower = acActivePowerOpt.get(); if (acPower > 0) { this.calculateAcChargeEnergy.update(0); this.calculateAcDischargeEnergy.update(acPower); } else { this.calculateAcChargeEnergy.update(-acPower); this.calculateAcDischargeEnergy.update(0); } } else { this.calculateAcChargeEnergy.update(null); this.calculateAcDischargeEnergy.update(null); }
        Optional<Integer> dcPowerOpt = acActivePowerOpt;
        if (dcPowerOpt.isPresent()) { int dcPower = dcPowerOpt.get(); if (dcPower > 0) { this.calculateDcChargeEnergy.update(0); this.calculateDcDischargeEnergy.update(dcPower); } else { this.calculateDcChargeEnergy.update(-dcPower); this.calculateDcDischargeEnergy.update(0); } } else { this.calculateDcChargeEnergy.update(null); this.calculateDcDischargeEnergy.update(null); }
    }

    /**
     * Hilfsmethode zum Verknüpfen von Quell-Boolean-Kanälen mit einem zusammengefassten StateChannel.
     */
    private void addStateChannelTrigger(io.openems.edge.common.channel.ChannelId targetChannelId,
            io.openems.edge.common.channel.ChannelId[] sourceChannelIds) {
        StateChannel targetChannel = this.channel(targetChannelId);
        List<Channel<Boolean>> sourceChannels = Arrays.stream(sourceChannelIds).map(id -> { try { @SuppressWarnings("unchecked") Channel<Boolean> channel = (Channel<Boolean>) this.channel(id); return channel; } catch (IllegalArgumentException e) { this.logWarn(log, "Could not find source channel [" + id.id() + "] for target [" + targetChannelId.id() + "]. " + e.getMessage()); return null; } }).filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (sourceChannels.isEmpty()) { targetChannel.setNextValue(false); return; }
        for (Channel<Boolean> sourceChannel : sourceChannels) { sourceChannel.onUpdate(value -> { boolean isAnySourceTrue = sourceChannels.stream().anyMatch(ch -> ch.value().orElse(false)); targetChannel.setNextValue(isAnySourceTrue); }); }
        boolean isInitiallyAnySourceTrue = sourceChannels.stream().anyMatch(ch -> ch.value().orElse(false)); targetChannel.setNextValue(isInitiallyAnySourceTrue);
    }

     @Override
     public Integer getUnitId() {
          return super.getUnitId();
     }

} // Ende der Klasse