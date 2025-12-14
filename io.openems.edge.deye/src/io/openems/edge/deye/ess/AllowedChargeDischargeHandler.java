package io.openems.edge.deye.ess;

import org.slf4j.Logger;

import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.common.component.ClockProvider;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.deye.battery.DeyeSunBattery;
import io.openems.edge.deye.dccharger.DeyeDcCharger;
import io.openems.edge.ess.generic.common.AbstractAllowedChargeDischargeHandler;

public class AllowedChargeDischargeHandler extends AbstractAllowedChargeDischargeHandler<DeyeSunHybridImpl> {

	private final DeyeSunBattery battery;
	private final Logger log;

	public AllowedChargeDischargeHandler(DeyeSunHybridImpl parent, DeyeSunBattery battery, DeyeDcCharger dcCharger) {
		super(parent);
		this.battery = battery;
		this.log = this.parent.getLogger();
	}

	@Override
	public void accept(ClockProvider clockProvider, Battery battery, SymmetricBatteryInverter inverter) {
		if (battery == null) {
			parent._setAllowedChargePower(0);
			parent._setAllowedDischargePower(0);
			return;
		}
		this.accept(clockProvider);
	}

	/**
	 * Calculates AllowedChargePower and AllowedDischargePower and sets the Channels.
	 *
	 * @param clockProvider a {@link ClockProvider}
	 */
	public void accept(ClockProvider clockProvider) {
		if (this.battery == null) {
			parent._setAllowedChargePower(0);
			parent._setAllowedDischargePower(0);
			return;
		}

		// Dynamic limits (reported by inverter/BMS path) – register 212/213
		Integer dynChargeA = this.battery.getDynamicChargeCurrentLimit().get();
		Integer dynDischargeA = this.battery.getDynamicDischargeCurrentLimit().get();

		// Manual limits (set in inverter) – register 108/109
		Integer manualChargeA = this.battery.getManualChargeCurrentLimit().get();
		Integer manualDischargeA = this.battery.getManualDischargeCurrentLimit().get();

		// Battery voltage (as provided by your battery implementation)
		Integer batteryVoltageRaw = this.battery.getBatteryVoltage().orElse(0);

		if (dynChargeA == null || dynDischargeA == null || batteryVoltageRaw == null) {
			this.parent.logDebug(log, "[AllowChargeDischarge Handler] Required values not available. Setting 0 W.");
			parent._setAllowedChargePower(0);
			parent._setAllowedDischargePower(0);
			return;
		}

		/*
		 * Apply min() logic:
		 * - If manual limit is configured (>0), it caps the dynamic limit.
		 * - If manual is null/0, use dynamic limit as-is.
		 */
		int effChargeA = dynChargeA;
		if (manualChargeA != null && manualChargeA > 0) {
			effChargeA = Math.min(dynChargeA, manualChargeA);
		}

		int effDischargeA = dynDischargeA;
		if (manualDischargeA != null && manualDischargeA > 0) {
			effDischargeA = Math.min(dynDischargeA, manualDischargeA);
		}

		/*
		 * Voltage handling:
		 * NOTE: keep your existing behavior for now to stay minimal-invasive.
		 * If your BATTERY_VOLTAGE channel is already scaled to V, this is fine.
		 * If it's mV, we adjust later once confirmed.
		 */
		double voltage = batteryVoltageRaw / 1000.0;

		double allowedChargePower = effChargeA * voltage * -1;     // negative for charging
		double allowedDischargePower = effDischargeA * voltage;    // positive for discharging

		this.parent.logDebug(log,
				"[AllowChargeDischarge Handler] dynCharge=" + dynChargeA + "A manualCharge=" + manualChargeA + "A effCharge=" + effChargeA + "A; "
				+ "dynDischarge=" + dynDischargeA + "A manualDischarge=" + manualDischargeA + "A effDischarge=" + effDischargeA + "A; "
				+ "U=" + voltage + "V; "
				+ "AllowedCharge=" + allowedChargePower + "W; AllowedDischarge=" + allowedDischargePower + "W");

		// PV-Production (kept as-is from your code)
		var pvProduction = Math.max(//
				TypeUtils.orElse(//
						TypeUtils.subtract(this.parent.getActivePower().get(), this.parent.getDcDischargePower().get()), //
						0),
				0);

		// Apply AllowedChargePower and AllowedDischargePower
		this.parent._setAllowedChargePower((int) allowedChargePower);                   // 0 or negative
		this.parent._setAllowedDischargePower((int) allowedDischargePower + pvProduction); // positive
	}
}
