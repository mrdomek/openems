package io.openems.edge.deye.ess;

import org.slf4j.Logger;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.edge.deye.battery.DeyeSunBattery;
import io.openems.edge.deye.dccharger.DeyeDcCharger;
import io.openems.edge.deye.enums.EnableDisable;
import io.openems.edge.deye.enums.EnergyManagementModel;
import io.openems.edge.deye.enums.LimitControlFunction;
import io.openems.edge.deye.enums.WorkState;

public class ApplyPowerHandler {

	// === Dependencies ===
	private final DeyeSunHybridImpl ess;
	private final DeyeSunBattery battery;
	private final DeyeDcCharger dcCharger;
	private final Logger log;

	// === Initialization ===
	private boolean initialized = false;

	// === TIMERS ===
	// Timer 1: Watchdog (sehr langsam, alle 30s)
	private static final long WATCHDOG_INTERVAL_MS = 30_000L;
	private long lastWatchdogMs = 0L;

	// Timer 2: Power Setpoint (alle 3s)
	private static final long POWER_INTERVAL_MS = 3_000L;
	private long lastPowerWriteMs = 0L;

	// === State ===
	// mrdomek: Letzten geschriebenen Wert merken, um unnötige Duplikate zu vermeiden
	private Integer lastSetpoint1111 = null;

	// mrdomek: Minimale Änderung 0.5% (5 Einheiten), damit der Bus nicht bei Rauschen zugemüllt wird.
	// Wenn du es GANZ stumpf willst (auch 0.1% Rauschen schreiben), setze das auf 0.
	private static final int MIN_STEP_1111 = 5;

	public ApplyPowerHandler(DeyeSunHybridImpl ess, DeyeSunBattery battery, DeyeDcCharger dcCharger) {
		this.ess = ess;
		this.battery = battery;
		this.dcCharger = dcCharger;
		this.log = this.ess.getLogger();
	}

	public void apply(int activePowerTarget, int reactivePower, int configuredMaxApparentPower)
			throws OpenemsNamedException {

		// --- Guards ---
		if (!ess.isManaged())
			return;
		if (ess.getWorkState() != WorkState.NORMAL) {
			log.debug("ESS not in normal state. Skipping ApplyPower");
			return;
		}

		// mrdomek: consume dcCharger field once so Eclipse does not mark it as "unused"
		if (this.dcCharger != null) { /* mrdomek: reserved for later integration */ }

		// --- Read inputs ---
		Integer maxApparentPower = ess.getMaxApparentPower().get();
		if (maxApparentPower == null || maxApparentPower <= 0)
			return;

		if (battery.getBatteryVoltage().get() == null)
			return;

		long now = System.currentTimeMillis();

		// ========================================================================
		// 1. INITIALISIERUNG (Einmalig beim Start)
		// ========================================================================
		if (!this.initialized) {
			this.writeFlags();

			this.ess.setSetRemoteMode(1);
			this.ess.setSetRemoteWatchdogTime(600);

			// Einmaliges Setzen der Modi (Registers 1102-1110)
			this.ess.setFuckOff1(0);
			this.ess.setFuckOff2(0);
			this.ess.setSetControlMode(0);
			this.ess.setSetBatteryControlMode(2);
			this.ess.setSet3PControlMode(0);
			this.ess.setBatteryConstantVoltage(0);
			this.ess.setBatteryConstantCurrent(0);
			this.ess.setSetBatteryPowerPercent(0);
			this.ess.setSetBatteryPowerSoc(0);

			this.initialized = true;
			this.lastWatchdogMs = now;
			this.lastPowerWriteMs = now;
		}

		// ========================================================================
		// 2. WATCHDOG TIMER (Alle 30 Sekunden)
		// ========================================================================
		if ((now - this.lastWatchdogMs) >= WATCHDOG_INTERVAL_MS) {
			this.lastWatchdogMs = now;
			// Watchdog auf 10 Minuten setzen (Countdown), wir erneuern ihn aber alle 30s
			this.ess.setSetRemoteWatchdogTime(600);
			// log.info("Watchdog refreshed (30s)");
		}

		// ========================================================================
		// 3. POWER WRITE TIMER (Alle 3 Sekunden)
		// ========================================================================
		if ((now - this.lastPowerWriteMs) >= POWER_INTERVAL_MS) {
			this.lastPowerWriteMs = now;

			// --- RAW LOGIC: Keine Mittelwerte, keine Dämpfung ---
			// Wir nehmen den Target direkt vom Controller
			int targetW = activePowerTarget;

			// Einzige Modifikation: Umrechnung in % für Deye
			int powerDeciPercentToWrite = calculateDeciPercentFromPower(maxApparentPower, targetW);

			// --- Schreib-Entscheidung ---
			boolean writeRequired = false;

			if (this.lastSetpoint1111 == null) {
				writeRequired = true;
			} else {
				// Prüfe auf Änderung (Hysterese gegen Bus-Überlastung bei minimalem Rauschen)
				if (Math.abs(powerDeciPercentToWrite - this.lastSetpoint1111) >= MIN_STEP_1111) {
					writeRequired = true;
				}
				// WICHTIG: Richtungswechsel (Laden <-> Entladen) IMMER schreiben
				if (Integer.signum(powerDeciPercentToWrite) != Integer.signum(this.lastSetpoint1111)) {
					writeRequired = true;
				}
			}

			if (writeRequired) {
				this.ess.setSetAcSetpoint3pPercent(powerDeciPercentToWrite); // Register 1111
				this.lastSetpoint1111 = powerDeciPercentToWrite;

				log.info("ApplyPower (3s): TargetRaw=" + targetW + "W -> Setpoint=" + powerDeciPercentToWrite);
			}
		}

		// Optional: Debug pro Zyklus (Achtung: Log-Flut bei 1s Zyklen)
		// ess.logDebug(log, "Cycle: Target=" + activePowerTarget + " Grid=" + activePower);
	}

	// ========================= Helper =========================

	private int calculateDeciPercentFromPower(int maxApparentPower, int targetPower) {
		if (maxApparentPower <= 0)
			return 0;
		// Einfache Prozentrechnung ohne Glättung
		double percent = (double) targetPower * 100.0 / (double) maxApparentPower;
		// Hard Limits [-100% ... +100%]
		percent = Math.max(-100.0, Math.min(100.0, percent));
		// Skalierung auf 0.1% Schritte (Deye Format)
		return (int) Math.round(percent * 10.0);
	}

	/**
	 * Choose the effective current limit based on Modbus doc rule:
	 * if dynamic(BMS/inverter reported) > manual(inverter setting) -> use manual, else use dynamic.
	 * //mrdomek: this is effectively min(manual,dynamic) with null-safety
	 */
	private Integer effectiveLimit(Integer dynamicLimit, Integer manualLimit) {
		// mrdomek: if one side is missing, fall back to the other to avoid writing garbage
		if (dynamicLimit == null && manualLimit == null) {
			return null;
		}
		if (dynamicLimit == null) {
			return manualLimit;
		}
		if (manualLimit == null) {
			return dynamicLimit;
		}
		return Math.min(dynamicLimit, manualLimit);
	}

	private void writeFlags() throws OpenemsNamedException {
		// Statische Konfiguration beim Start
		if (!this.ess.getGeneratorCharingEnabled())
			this.ess.setGeneratorCharingEnabled(false);
		if (!this.ess.getGridCharingEnabled())
			this.ess.setGridCharingEnabled(true);

		if (this.ess.getEnergyManagementModel() != EnergyManagementModel.LOAD_FIRST)
			this.ess.setEnergyManagementModel(EnergyManagementModel.LOAD_FIRST);

		if (this.ess.getLimitControlFunction() != LimitControlFunction.SELLING_ACTIVE)
			this.ess.setLimitControlFunction(LimitControlFunction.SELLING_ACTIVE);

		if (this.ess.getSolarSellMode() != EnableDisable.ENABLED)
			this.ess.setSolarSellMode(EnableDisable.ENABLED);

		if (this.ess.getSellModeTimePoint1Capacity().get() != 100)
			this.ess.setSellModeTimePoint1Capacity(100);
		if (this.ess.getChargeModeTimePoint1().get() != 1)
			this.ess.setChargeModeTimePoint1(1);

		/*
		 * Current limit logic (per Modbus doc):
		 *  - Manual limits are Reg 108/109 (user-set in inverter)
		 *  - Dynamic limits are Reg 212/213 (reported)
		 *  - Effective = min(manual,dynamic)
		 */
		final Integer manualChargeA = this.battery.getManualChargeCurrentLimit().get();
		final Integer manualDischargeA = this.battery.getManualDischargeCurrentLimit().get();
		final Integer dynamicChargeA = this.battery.getDynamicChargeCurrentLimit().get();
		final Integer dynamicDischargeA = this.battery.getDynamicDischargeCurrentLimit().get();

		final Integer effectiveChargeA = effectiveLimit(dynamicChargeA, manualChargeA);
		final Integer effectiveDischargeA = effectiveLimit(dynamicDischargeA, manualDischargeA);

		// mrdomek: do NOT overwrite manual registers here; they are the user's inverter setting (108/109)
		// We only apply the effective limit to inverter-controlled charging currents (e.g., grid charge current).
		if (effectiveChargeA != null) {
			Integer current = this.ess.getGridChargeCurrent().get();
			if (current == null || !current.equals(effectiveChargeA)) {
				this.ess.setGridChargeCurrent(effectiveChargeA);
			}
		}

		// mrdomek: effectiveDischargeA computed and kept for the next step (where a discharge-current target exists)
		@SuppressWarnings("unused")
		final Integer _keepEffectiveDischargeA = effectiveDischargeA;
	}
}
