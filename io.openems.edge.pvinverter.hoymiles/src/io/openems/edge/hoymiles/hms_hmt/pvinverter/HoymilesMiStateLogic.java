package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Centralized MI1 status/alarm interpretation.
 *
 * //mrdomek Why: RunState/Health must depend only on FAULT/WARNING, while INFO/IGNORED remain visible.
 * //mrdomek Alarm semantics are defined in HoymilesAlarmBit only (single source of truth).
 */
public final class HoymilesMiStateLogic {

	private HoymilesMiStateLogic() {
	}

	public enum Mi1Status {
		OFFLINE(0), STARTUP(1), PRODUCING(2), WARNING(3), FAULT(4), UNKNOWN(-1);

		public final int code;

		private Mi1Status(int code) {
			this.code = code;
		}

		public static Mi1Status fromRaw(int rawStatus) {
			final int v = u16(rawStatus);
			for (Mi1Status s : Mi1Status.values()) {
				if (s.code == v) {
					return s;
				}
			}
			return UNKNOWN;
		}
	}

	public enum OperationMode {
		OFF, IDLE, PRODUCING;
	}

	public static OperationMode deriveOperationMode(Integer rawStatusCode, Integer activePowerW) {
		//mrdomek Why: Derive MI state only from the two agreed signals; if any input is missing, do not guess.
		if (rawStatusCode == null || activePowerW == null) {
			return null;
		}

		final int status = u16(rawStatusCode.intValue());
		final int p = activePowerW.intValue();

		if (status == 0 && p == 0) {
			return OperationMode.OFF;
		}
		if (status != 0 && p == 0) {
			return OperationMode.IDLE;
		}
		if (status != 0 && p > 0) {
			return OperationMode.PRODUCING;
		}

		//mrdomek Why: Keep UI truthful for unexpected combinations (e.g. status==0 while power>0).
		return null;
	}

	public static final class AlarmClassification {
		public final boolean hasFault;
		public final boolean hasWarning;

		public final List<String> faultSummary;
		public final List<String> warningSummary;
		public final List<String> infoSummary;
		public final List<String> ignoredSummary;

		private AlarmClassification(boolean hasFault, boolean hasWarning, List<String> faultSummary, List<String> warningSummary,
				List<String> infoSummary, List<String> ignoredSummary) {
			this.hasFault = hasFault;
			this.hasWarning = hasWarning;
			this.faultSummary = faultSummary;
			this.warningSummary = warningSummary;
			this.infoSummary = infoSummary;
			this.ignoredSummary = ignoredSummary;
		}
	}

	/**
	 * Backwards-compatible helper used by PvInverterHoymilesHMSHMTImpl.
	 *
	 * //mrdomek Why: PvInverter uses this as a compact state string channel.
	 */
	public static String interpretHoymilesStatus(int totalPowerW, int rawStatusCode, int alarm1, int alarm2, int alarm3, int alarm4,
			int alarm5, int alarm6) {

		final int inputChannels = 6;
		final boolean[] pvInstalled = null; // conservative: unknown => treat as installed

		final AlarmClassification cls = classify(true, totalPowerW, Integer.valueOf(rawStatusCode), inputChannels, pvInstalled, alarm1, alarm2,
				alarm3, alarm4, alarm5, alarm6);

		final OperationMode op = deriveOperationMode(Integer.valueOf(rawStatusCode), Integer.valueOf(totalPowerW));
		if (op == OperationMode.OFF) {
			//mrdomek Why: DTU stays reachable at night; MI being off must not be labeled as offline/fault.
			return "Microinverter is off (night). DTU reachable.";
		}

		final Mi1Status st = Mi1Status.fromRaw(rawStatusCode);

		if (st == Mi1Status.OFFLINE) {
			return "OFFLINE";
		}
		if (st == Mi1Status.STARTUP) {
			return cls.hasFault ? "ERROR" : "STARTUP";
		}
		if (cls.hasFault || st == Mi1Status.FAULT) {
			return "ERROR";
		}
		if (cls.hasWarning || st == Mi1Status.WARNING) {
			return "WARNING";
		}
		if (st == Mi1Status.PRODUCING) {
			return "OK";
		}
		return "UNKNOWN";
	}


	public static AlarmClassification classify(boolean hasData, int totalPowerW, Integer statusRaw, int inputChannels, boolean[] pvInstalled,
			int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {

		final List<String> faults = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();
		final List<String> infos = new ArrayList<>();
		final List<String> ignored = new ArrayList<>();

		if (!hasData) {
			return new AlarmClassification(false, false, faults, warnings, infos, ignored);
		}

		final Mi1Status st = Mi1Status.fromRaw(statusRaw == null ? -1 : statusRaw.intValue());
		if (st == Mi1Status.FAULT) {
			faults.add("STATUS_FAULT");
		}

		// Named bits (from enum) + unknown bits (fallback)
		classifyWord(1, alarm1, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);
		classifyWord(2, alarm2, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);
		classifyWord(3, alarm3, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);
		classifyWord(4, alarm4, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);
		classifyWord(5, alarm5, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);
		classifyWord(6, alarm6, totalPowerW, inputChannels, pvInstalled, faults, warnings, infos, ignored);

		// Status WARNING: keep behaviour, but reduce noise in night-mode if only INFO exists
		if (st == Mi1Status.WARNING) {
			final boolean hasOnlyInfos = faults.isEmpty() && warnings.isEmpty();
			if (totalPowerW == 0 && hasOnlyInfos) {
				infos.add("STATUS_WARNING (night-mode)");
			} else {
				warnings.add("STATUS_WARNING");
			}
		}

		final boolean hasFault = !faults.isEmpty();
		final boolean hasWarning = !warnings.isEmpty();

		return new AlarmClassification(hasFault, hasWarning, faults, warnings, infos, ignored);
	}

	private static void classifyWord(int alarmWordIndex, int wordValue, int totalPowerW, int inputChannels, boolean[] pvInstalled,
			List<String> faults, List<String> warnings, List<String> infos, List<String> ignored) {

		final int v = u16(wordValue);

		// 1) Named bits
		final EnumSet<HoymilesAlarmBit> named = HoymilesAlarmBit.fromAlarmWord(alarmWordIndex, v);
		for (HoymilesAlarmBit bit : named) {
			final HoymilesAlarmBit.Severity sev = applyOverrides(bit, totalPowerW, inputChannels, pvInstalled);
			addBySeverity(sev, bit.name(), faults, warnings, infos, ignored);
		}

		// 2) Unknown bits -> stable tokens
		final List<Integer> unknownBits = HoymilesAlarmBit.unknownSetBitsInWord(alarmWordIndex, v);
		for (Integer b : unknownBits) {
			final int bitIndex = b.intValue();
			final HoymilesAlarmBit generic = HoymilesAlarmBit.genericBit(bitIndex);

			//mrdomek Why: Unknown bits are conservative by default (FAULT) until we document them.
			final HoymilesAlarmBit.Severity sev = (generic != null) ? generic.defaultSeverity() : HoymilesAlarmBit.Severity.FAULT;
			addBySeverity(sev, "ALARM" + alarmWordIndex + "_BIT" + bitIndex, faults, warnings, infos, ignored);
		}
	}

	private static HoymilesAlarmBit.Severity applyOverrides(HoymilesAlarmBit bit, int totalPowerW, int inputChannels, boolean[] pvInstalled) {

		//mrdomek Why: MPPT undervoltage must be ignored if the whole MPPT is configured as "not installed" (pvPeak=0 on all PVs of that MPPT).
		//mrdomek If installed, we still reduce severity at night (P==0) to INFO to avoid noise.
		if (bit == HoymilesAlarmBit.ALARM3_MPPT_A_UNDERVOLTAGE_PV1_PV2) {
			if (!isAnyPvInstalled(pvInstalled, inputChannels, 1, 2)) {
				return HoymilesAlarmBit.Severity.IGNORED;
			}
			if (totalPowerW == 0) {
				return HoymilesAlarmBit.Severity.INFO;
			}
		}
		if (bit == HoymilesAlarmBit.ALARM3_MPPT_B_UNDERVOLTAGE_PV3_PV4) {
			if (!isAnyPvInstalled(pvInstalled, inputChannels, 3, 4)) {
				return HoymilesAlarmBit.Severity.IGNORED;
			}
			if (totalPowerW == 0) {
				return HoymilesAlarmBit.Severity.INFO;
			}
		}
		if (bit == HoymilesAlarmBit.ALARM3_MPPT_C_UNDERVOLTAGE_PV5_PV6) {
			if (!isAnyPvInstalled(pvInstalled, inputChannels, 5, 6)) {
				return HoymilesAlarmBit.Severity.IGNORED;
			}
			if (totalPowerW == 0) {
				return HoymilesAlarmBit.Severity.INFO;
			}
		}

		//mrdomek Why: PVx_NO_INPUT is irrelevant if PV input is configured as "not installed" (pvPeak==0).
		if (bit == HoymilesAlarmBit.ALARM4_PV1_NO_INPUT) {
			return isPvInstalled(pvInstalled, 1, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}
		if (bit == HoymilesAlarmBit.ALARM4_PV2_NO_INPUT) {
			return isPvInstalled(pvInstalled, 2, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}
		if (bit == HoymilesAlarmBit.ALARM4_PV3_NO_INPUT) {
			return isPvInstalled(pvInstalled, 3, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}
		if (bit == HoymilesAlarmBit.ALARM4_PV4_NO_INPUT) {
			return isPvInstalled(pvInstalled, 4, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}
		if (bit == HoymilesAlarmBit.ALARM4_PV5_NO_INPUT) {
			return isPvInstalled(pvInstalled, 5, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}
		if (bit == HoymilesAlarmBit.ALARM4_PV6_NO_INPUT) {
			return isPvInstalled(pvInstalled, 6, inputChannels) ? bit.defaultSeverity() : HoymilesAlarmBit.Severity.IGNORED;
		}

		return bit.defaultSeverity();
	}

	private static boolean isPvInstalled(boolean[] pvInstalled, int pvIndex1Based, int inputChannels) {
		if (pvIndex1Based > inputChannels) {
			return false;
		}
		if (pvInstalled == null) {
			return true; // conservative default
		}
		final int idx = pvIndex1Based - 1;
		if (idx < 0 || idx >= pvInstalled.length) {
			return true;
		}
		return pvInstalled[idx];
	}
	
	private static boolean isAnyPvInstalled(boolean[] pvInstalled, int inputChannels, int pvA1Based, int pvB1Based) {
		//mrdomek Why: If neither PV input of a MPPT is installed (pvPeak=0), MPPT undervoltage alarms are irrelevant.
		return isPvInstalled(pvInstalled, pvA1Based, inputChannels) || isPvInstalled(pvInstalled, pvB1Based, inputChannels);
	}


	private static void addBySeverity(HoymilesAlarmBit.Severity sev, String token, List<String> faults, List<String> warnings,
			List<String> infos, List<String> ignored) {
		switch (sev) {
		case FAULT:
			faults.add(token);
			break;
		case WARNING:
			warnings.add(token);
			break;
		case INFO:
			infos.add(token);
			break;
		case IGNORED:
			ignored.add(token);
			break;
		default:
			faults.add(token);
			break;
		}
	}

	public static boolean hasAnyAlarm(int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {
		return (u16(alarm1) != 0) || (u16(alarm2) != 0) || (u16(alarm3) != 0) || (u16(alarm4) != 0) || (u16(alarm5) != 0)
				|| (u16(alarm6) != 0);
	}

	public static String toHealthState(boolean hasData, boolean fault, boolean warning) {
		if (!hasData) {
			return "NO_DATA";
		}
		if (fault) {
			return "FAULT";
		}
		if (warning) {
			return "WARNING";
		}
		return "OK";
	}

	public static String buildSummaryString(List<String> items) {
		if (items == null || items.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(items.get(i));
		}
		return sb.toString();
	}

	private static int u16(int v) {
		return v & 0xFFFF;
	}
}
