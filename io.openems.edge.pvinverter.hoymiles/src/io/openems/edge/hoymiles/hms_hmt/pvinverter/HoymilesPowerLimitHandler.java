package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Computes and schedules Hoymiles "Temporary Power Limit %" writes.
 *
 * Variant B (static MPPT-based effective max power):
 * - Percent is always computed relative to an effective maximum AC power.
 * - Effective max is derived from DeviceModel max power scaled by active MPPT count.
 * - No "available DC" / closed-loop compensation logic.
 */
public class HoymilesPowerLimitHandler {

	public interface Actions {
		void setPortOnOff(boolean on);

		/**
		 * UI mirror only (not Modbus mapped).
		 */
		void setLimitWUi(Integer watt);

		/**
		 * UI mirror only (not Modbus mapped).
		 */
		void setLimitPercentUi(Integer percent);

		/**
		 * Schedule FC16 write to DTU "Temporary Power Limit %".
		 */
		void schedulePercentWrite(short percent);

		void debug(String message);
	}

	private final long minWriteIntervalMs;

	private long lastLimitWriteTimestampMs = 0L;
	private Short lastWrittenLimitPercent = null;
	private Integer lastTargetLimitW = null;

	public HoymilesPowerLimitHandler(long minWriteIntervalMs) {
		this.minWriteIntervalMs = minWriteIntervalMs;
	}

	public Integer getLastTargetLimitW() {
		return this.lastTargetLimitW;
	}

	public Short getLastWrittenLimitPercent() {
		return this.lastWrittenLimitPercent;
	}

	/**
	 * Apply a target limit as "percent of effective max power".
	 *
	 * @param targetLimitW    AC target in W; null means "no limit active" => write 100%
	 * @param effectiveMaxW   effective max AC power in W (already MPPT-scaled); <=0 means unknown
	 * @param minPercent      minimum percent supported by device generation (e.g. Gen3=2)
	 * @param mpptActive      number of active MPPTs derived from config
	 * @param mpptTotal       total MPPTs according to device model
	 * @param nowMs           current time for rate limiting
	 * @param a               callbacks
	 */
	public void applyMpptScaled(Integer targetLimitW, int effectiveMaxW, int minPercent,
			int mpptActive, int mpptTotal, long nowMs, Actions a) {

		final boolean canWriteNow = (nowMs - this.lastLimitWriteTimestampMs) >= this.minWriteIntervalMs;

		// Mirror input in UI (even if null)
		a.setLimitWUi(targetLimitW);

		if (targetLimitW == null) {
			// Remove limit => 100%
			if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() != 100) {
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(100));

				if (canWriteNow) {
					a.debug("PowerLimit: target=null -> write 100% (remove limit).");
					a.schedulePercentWrite((short) 100);

					this.lastWrittenLimitPercent = Short.valueOf((short) 100);
					this.lastTargetLimitW = null;
					this.lastLimitWriteTimestampMs = nowMs;
				} else {
					a.debug("PowerLimit: target=null -> skip (rate-limit).");
				}
			} else {
				a.setLimitPercentUi(Integer.valueOf(100));
				a.debug("PowerLimit: target=null -> skip (already 100%).");
			}
			return;
		}

		int baseW = effectiveMaxW;
		if (baseW <= 0) {
			//mrdomek Why: avoid divide-by-zero; if base is unknown, fall back to 1 so we end up clamped to min/100.
			baseW = 1;
		}

		int effectiveTargetW = targetLimitW.intValue();
		if (effectiveTargetW < 0) {
			effectiveTargetW = 0;
		}

		// Percent of effective max power
		int percent = (int) Math.ceil((effectiveTargetW * 100.0) / (double) baseW);

		if (percent > 100) {
			percent = 100;
		}
		if (percent < minPercent) {
			a.debug("PowerLimit: clamp percent to minPercent. percent=" + percent + " -> " + minPercent);
			percent = minPercent;
		}

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percent));

		final short newPercentShort = (short) percent;

		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == newPercentShort) {
			a.debug("PowerLimit: skip (percent unchanged). percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);
			return;
		}

		if (!canWriteNow) {
			a.debug("PowerLimit: skip (rate-limit). percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);
			return;
		}

		a.debug("PowerLimit: WRITE percent=" + percent
				+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
				+ " mpptActive=" + mpptActive + "/" + mpptTotal);

		a.schedulePercentWrite(newPercentShort);

		this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
		this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
		this.lastLimitWriteTimestampMs = nowMs;
	}
}
