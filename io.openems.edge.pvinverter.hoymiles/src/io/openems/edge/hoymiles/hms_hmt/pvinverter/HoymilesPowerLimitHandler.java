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

	//mrdomek Why: allow the component to force a re-write after config/topology changes.
	public void reset() {
		this.lastLimitWriteTimestampMs = 0L;
		this.lastWrittenLimitPercent = null;
		this.lastTargetLimitW = null;
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
	public void applyMpptScaled(Integer targetLimitW, int defaultPercentIfNoTarget, int effectiveMaxW, int minPercent,
			int mpptActive, int mpptTotal, long nowMs, Actions a) {

		final boolean canWriteNow = (nowMs - this.lastLimitWriteTimestampMs) >= this.minWriteIntervalMs;

		// Mirror input in UI (even if null)
		a.setLimitWUi(targetLimitW);

		if (targetLimitW == null) {
			int percent = defaultPercentIfNoTarget;

			if (percent > 100) {
				percent = 100;
			}
			if (percent < 0) {
				percent = 0;
			}

			// Respect device minimum percent (only if percent > 0)
			if (minPercent > 0 && percent > 0 && percent < minPercent) {
				a.debug("PowerLimit: target=null -> clamp default percent to minPercent. " +
						"defaultPercent=" + defaultPercentIfNoTarget + " -> " + percent + " -> " + minPercent);
				percent = minPercent;
			}

			if (percent <= 0) {
				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
				this.lastWrittenLimitPercent = null;
				this.lastTargetLimitW = null;
				return;
			}

			a.setPortOnOff(true);
			a.setLimitPercentUi(Integer.valueOf(percent));

			final short newPercentShort = (short) percent;

			if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == newPercentShort) {
				a.debug("PowerLimit: target=null -> skip (default percent unchanged). percent=" + percent);
				this.lastTargetLimitW = null;
				return;
			}

			if (!canWriteNow) {
				a.debug("PowerLimit: target=null -> skip (rate-limit). percent=" + percent);
				this.lastTargetLimitW = null;
				return;
			}

			a.debug("PowerLimit: WRITE default percent=" + percent);
			a.schedulePercentWrite(newPercentShort);

			this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
			this.lastTargetLimitW = null;
			this.lastLimitWriteTimestampMs = nowMs;
			return;
		}

		// Target given in W -> map to percent of effectiveMaxW
		final int normalizedW = Math.max(0, targetLimitW.intValue());

		if (effectiveMaxW <= 0) {
			// Cannot compute percent reliably; fail-safe: just keep port ON if target>0, else OFF.
			if (normalizedW <= 0) {
				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
			} else {
				a.setPortOnOff(true);
				a.setLimitPercentUi(null);
			}
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			return;
		}

		if (normalizedW <= 0) {
			a.setPortOnOff(false);
			a.setLimitPercentUi(Integer.valueOf(0));
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			return;
		}

		// Clamp to effective max
		final int effectiveTargetW = Math.min(normalizedW, effectiveMaxW);

		double ratioExact = (double) effectiveTargetW / (double) effectiveMaxW;
		int percent = (int) Math.ceil(ratioExact * 100.0);

		if (percent > 100) {
			percent = 100;
		}
		if (percent < 0) {
			percent = 0;
		}

		if (minPercent > 0 && percent > 0 && percent < minPercent) {
			a.debug("PowerLimit: clamp percent to minPercent. percent=" + percent + " -> " + minPercent);
			percent = minPercent;
		}

		final int baseW = effectiveMaxW;

		final short newPercentShort = (short) percent;

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percent));

		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == newPercentShort) {
			a.debug("PowerLimit: skip (percent unchanged). percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
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
