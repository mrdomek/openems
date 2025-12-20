package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Pure MI1 state derivation logic (no OpenEMS component access).
 *
 * //mrdomek Keep "what does it mean?" logic out of the component to stay readable and testable.
 */
public final class HoymilesMi1StateLogic {

	private HoymilesMi1StateLogic() {
	}

	public static boolean hasAnyAlarm(int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {
		return alarm1 != 0 || alarm2 != 0 || alarm3 != 0 || alarm4 != 0 || alarm5 != 0 || alarm6 != 0;
	}

	/**
	 * Derived health state:
	 * - NO_DATA: neither status nor any alarm register had a numeric value
	 * - FAULT  : any alarm register != 0
	 * - WARNING: status != 0 (and no alarms)
	 * - OK     : status == 0 and all alarm registers == 0
	 */
	public static String toHealthState(boolean hasData, boolean hasAlarm, Integer status) {
		if (!hasData) {
			return "NO_DATA";
		}
		if (hasAlarm) {
			return "FAULT";
		}
		if (status != null && status.intValue() != 0) {
			return "WARNING";
		}
		return "OK";
	}

	/**
	 * Current rule of thumb:
	 * - hasAlarm           -> "ERROR"
	 * - !hasAlarm + P > 0  -> "PRODUCING"
	 * - !hasAlarm + P == 0 -> "STANDBY" (or "OFF")
	 */
	public static String interpretHoymilesStatus(int totalPower, int rawStatusCode, boolean hasAlarm) {
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
	 * Build short string like "BIT0,BIT3" or "NO_ALARM".
	 */
	public static String buildAlarmSummary(int status, int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {
		int combined = (status | alarm1 | alarm2 | alarm3 | alarm4 | alarm5 | alarm6) & 0xFFFF;
		return HoymilesAlarmBit.toShortString(combined);
	}
	
	public static boolean isFault(boolean hasData, int alarm1, int alarm2, int alarm3, int alarm4, int alarm5, int alarm6) {
		if (!hasData) {
			return false;
		}
		return hasAnyAlarm(alarm1, alarm2, alarm3, alarm4, alarm5, alarm6);
	}

	public static boolean isWarning(boolean hasData, boolean hasAlarm, Integer status) {
		if (!hasData) {
			return false;
		}
		if (hasAlarm) {
			return false;
		}
		return status != null && status.intValue() != 0;
	}

}
