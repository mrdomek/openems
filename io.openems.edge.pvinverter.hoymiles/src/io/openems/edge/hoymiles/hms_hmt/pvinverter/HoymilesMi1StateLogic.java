package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.ArrayList;
import java.util.List;

public final class HoymilesMi1StateLogic {

	private HoymilesMi1StateLogic() {
	}

	/*
	 * Status mapping (empirical + matches your table):
	 * 0 Offline
	 * 1 Startup
	 * 2 Producing
	 * 3 Warning
	 * 4 Fault
	 */
	public enum Mi1Status {
		OFFLINE(0),
		STARTUP(1),
		PRODUCING(2),
		WARNING(3),
		FAULT(4),
		UNKNOWN(-1);

		private final int code;

		Mi1Status(int code) {
			this.code = code;
		}

		public int getCode() {
			return this.code;
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

	// Alarm3 (0x390A) bits from your Excel:
	// B15: Unterspg. MPPT A (PV1/2)
	// B13: Unterspg. MPPT ... (PV5/6)
	private static final int ALARM3_MPPT_UNDERVOLTAGE_MASK = (1 << 15) | (1 << 13);

	// Alarm4 (0x390B) bits from your Excel:
	// B7..B2 => PV6..PV1 "no input"
	private static final int ALARM4_PV_NOINPUT_MASK = ((1 << 7) | (1 << 6) | (1 << 5) | (1 << 4) | (1 << 3) | (1 << 2));

	public static boolean hasAnyAlarm(int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {
		return (u16(alarm1) != 0) || (u16(alarm2) != 0) || (u16(alarm3) != 0) || (u16(alarm4) != 0) || (u16(alarm5) != 0) || (u16(alarm6) != 0);
	}

	/**
	 * Fault rules:
	 * - Status == FAULT => fault
	 * - Any "unknown/critical" alarm bits outside our known benign masks => fault
	 * - Alarm1/2/5/6: we currently have no benign mapping -> any bit set => fault
	 *
	 * Night mode:
	 * - MPPT undervoltage at totalPowerW == 0 is NOT a fault (benign)
	 */
	public static boolean isFault(boolean hasData, int totalPowerW, Integer statusRaw, int alarm1, int alarm2, int alarm3,
			int alarm4, int alarm5, int alarm6) {

		if (!hasData) {
			return false;
		}

		final Mi1Status st = Mi1Status.fromRaw(statusRaw == null ? -1 : statusRaw.intValue());
		if (st == Mi1Status.FAULT) {
			return true;
		}

		// Alarm1/2/5/6 currently unknown -> treat as critical if any bit set
		if (u16(alarm1) != 0 || u16(alarm2) != 0 || u16(alarm5) != 0 || u16(alarm6) != 0) {
			return true;
		}

		// Alarm3: allow only known MPPT undervoltage bits (benign at night)
		final int a3 = u16(alarm3);
		final int a3Unknown = a3 & ~ALARM3_MPPT_UNDERVOLTAGE_MASK;
		if (a3Unknown != 0) {
			return true;
		}

		// Alarm4: allow only PV no-input bits as WARNING (not fault)
		final int a4 = u16(alarm4);
		final int a4Unknown = a4 & ~ALARM4_PV_NOINPUT_MASK;
		if (a4Unknown != 0) {
			return true;
		}

		// MPPT undervoltage during production can still be treated as warning, not fault.
		// Night mode (P==0) => benign.
		return false;
	}

	/**
	 * Warning rules (only if not fault):
	 * - Status == WARNING => warning (but ignore if it's just night mode + only MPPT undervoltage)
	 * - PVx "no input" bits => warning (always)
	 * - MPPT undervoltage bits => warning only if totalPowerW > 0 (during day)
	 */
	public static boolean isWarning(boolean hasData, int totalPowerW, Integer statusRaw, int alarm1, int alarm2, int alarm3,
			int alarm4, int alarm5, int alarm6) {

		if (!hasData) {
			return false;
		}

		if (isFault(hasData, totalPowerW, statusRaw, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6)) {
			return false;
		}

		final Mi1Status st = Mi1Status.fromRaw(statusRaw == null ? -1 : statusRaw.intValue());

		final int a3 = u16(alarm3);
		final int a4 = u16(alarm4);

		final boolean hasPvNoInput = (a4 & ALARM4_PV_NOINPUT_MASK) != 0;
		final boolean hasMpptUndervoltage = (a3 & ALARM3_MPPT_UNDERVOLTAGE_MASK) != 0;

		// PV no-input should remain visible as warning (configuration / wiring issue)
		if (hasPvNoInput) {
			return true;
		}

		// MPPT undervoltage is only meaningful during production
		if (hasMpptUndervoltage && totalPowerW > 0) {
			return true;
		}

		// Status warning: ignore if we are basically at night (P==0) and the only thing is MPPT undervoltage
		if (st == Mi1Status.WARNING) {
			if (totalPowerW == 0 && hasMpptUndervoltage) {
				return false;
			}
			return true;
		}

		// Startup can be treated as OK/Info (not warning)
		return false;
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

	/**
	 * Interpreted status for UI/logging.
	 *
	 * We do NOT map "any alarm" to ERROR anymore, because some alarm bits are benign at night.
	 */
	public static String interpretHoymilesStatus(int totalPowerW, int rawStatusCode, int alarm1, int alarm2, int alarm3, int alarm4,
			int alarm5, int alarm6) {

		final boolean hasData = true; // caller only uses this when it already has values
		final Integer statusObj = Integer.valueOf(rawStatusCode);

		final boolean fault = isFault(hasData, totalPowerW, statusObj, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
		if (fault) {
			return "ERROR";
		}

		final boolean warning = isWarning(hasData, totalPowerW, statusObj, alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
		if (warning) {
			return "WARNING";
		}

		if (totalPowerW > 0) {
			return "PRODUCING";
		}

		final Mi1Status st = Mi1Status.fromRaw(rawStatusCode);
		if (st == Mi1Status.STARTUP) {
			return "STARTUP";
		}

		// Offline at night is expected
		return "STANDBY";
	}

	public static String buildAlarmSummary(int rawStatusCode, int inputChannels, int alarm1, int alarm2, int alarm3, int alarm4, int alarm5,
			int alarm6) {
		final Mi1Status st = Mi1Status.fromRaw(rawStatusCode);

		final List<String> parts = new ArrayList<>();
		parts.add("STATUS:" + st.name());

		final List<String> alarms = new ArrayList<>();

		// Alarm3 known bits
		final int a3 = u16(alarm3);

		if ((a3 & (1 << 15)) != 0) {
			alarms.add("MPPT_A_UNDERVOLTAGE(PV1/2)");
		}
		if ((a3 & (1 << 13)) != 0) {
			//mrdomek For 4-input models the second MPPT group is PV3/4; for 6-input models it is PV5/6.
			if (inputChannels <= 4) {
				alarms.add("MPPT_UNDERVOLTAGE(PV3/4)");
			} else {
				alarms.add("MPPT_UNDERVOLTAGE(PV5/6)");
			}
		}
		addUnknownBits("A3", a3, ALARM3_MPPT_UNDERVOLTAGE_MASK, alarms);

		// Alarm4 known bits: PV no input
		final int a4 = u16(alarm4);
		if ((a4 & (1 << 2)) != 0) {
			alarms.add("PV1_NO_INPUT");
		}
		if ((a4 & (1 << 3)) != 0) {
			alarms.add("PV2_NO_INPUT");
		}
		if ((a4 & (1 << 4)) != 0) {
			alarms.add("PV3_NO_INPUT");
		}
		if ((a4 & (1 << 5)) != 0) {
			alarms.add("PV4_NO_INPUT");
		}
		if ((a4 & (1 << 6)) != 0) {
			alarms.add("PV5_NO_INPUT");
		}
		if ((a4 & (1 << 7)) != 0) {
			alarms.add("PV6_NO_INPUT");
		}
		addUnknownBits("A4", a4, ALARM4_PV_NOINPUT_MASK, alarms);

		// Alarm1/2/5/6: unknown mapping -> list bits if present
		addAllBitsIfAny("A1", u16(alarm1), alarms);
		addAllBitsIfAny("A2", u16(alarm2), alarms);
		addAllBitsIfAny("A5", u16(alarm5), alarms);
		addAllBitsIfAny("A6", u16(alarm6), alarms);

		if (alarms.isEmpty()) {
			parts.add("ALARMS:NO_ALARM");
		} else {
			parts.add("ALARMS:" + String.join(",", alarms));
		}

		return String.join("; ", parts);
	}

	private static void addUnknownBits(String prefix, int word, int knownMask, List<String> out) {
		final int unknown = word & ~knownMask;
		if (unknown == 0) {
			return;
		}
		for (int bit = 0; bit <= 15; bit++) {
			final int m = (1 << bit);
			if ((unknown & m) != 0) {
				out.add(prefix + "_BIT" + bit);
			}
		}
	}

	private static void addAllBitsIfAny(String prefix, int word, List<String> out) {
		if (word == 0) {
			return;
		}
		for (int bit = 0; bit <= 15; bit++) {
			if ((word & (1 << bit)) != 0) {
				out.add(prefix + "_BIT" + bit);
			}
		}
	}

	private static int u16(int v) {
		return v & 0xFFFF;
	}
}
