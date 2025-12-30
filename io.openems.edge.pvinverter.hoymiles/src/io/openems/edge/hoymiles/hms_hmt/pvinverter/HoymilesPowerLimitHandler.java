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
		 * Schedule Modbus write to DTU "Temporary Power Limit %".
		 */
		void schedulePercentWrite(short percent);

		void debug(String message);
	}

	//mrdomek Why: Some DTU/MI require a short processing time after turning ON before accepting the limit register.
	private static final long TURN_ON_TO_LIMIT_WRITE_DELAY_MS = 500L;

	private final long minWriteIntervalMs;

	private long lastLimitWriteTimestampMs = 0L;
	private Short lastWrittenLimitPercent = null;
	private Integer lastTargetLimitW = null;

	//mrdomek Why: Track the commanded ON/OFF state to apply the delay only for OFF->ON transitions after 0%.
	private boolean lastPortOn = true;

	//mrdomek Why: Buffer the desired percent during OFF->ON delay window; always write the latest value when due.
	private Short pendingPercentWrite = null;
	private long pendingPercentWriteDueAtMs = 0L;

	public HoymilesPowerLimitHandler(long minWriteIntervalMs) {
		this.minWriteIntervalMs = minWriteIntervalMs;
	}

	public void reset() {
		this.lastLimitWriteTimestampMs = 0L;
		this.lastWrittenLimitPercent = null;
		this.lastTargetLimitW = null;
		this.lastPortOn = true;
		this.pendingPercentWrite = null;
		this.pendingPercentWriteDueAtMs = 0L;
	}

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
				//mrdomek Why: A 0% command is modeled as OFF; clear any pending write and mark state as OFF.
				this.pendingPercentWrite = null;
				this.pendingPercentWriteDueAtMs = 0L;
				this.lastPortOn = false;

				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
				this.lastWrittenLimitPercent = null;
				this.lastTargetLimitW = null;
				return;
			}

			final short newPercentShort = (short) percent;

			//mrdomek Why: After OFF->ON, delay the percent write so the device can accept it reliably.
			if (!this.lastPortOn) {
				this.lastPortOn = true;
				this.pendingPercentWrite = Short.valueOf(newPercentShort);
				this.pendingPercentWriteDueAtMs = nowMs + TURN_ON_TO_LIMIT_WRITE_DELAY_MS;

				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(percent));
				a.debug("PowerLimit: OFF->ON (default) -> delay percent write by " + TURN_ON_TO_LIMIT_WRITE_DELAY_MS + "ms. percent=" + percent);
				this.lastTargetLimitW = null;
				return;
			}

			// If we are in a pending delay window, keep updating the pending percent and return.
			if (this.pendingPercentWrite != null && nowMs < this.pendingPercentWriteDueAtMs) {
				this.pendingPercentWrite = Short.valueOf(newPercentShort);
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(percent));
				a.debug("PowerLimit: pending (default) -> update percent during delay window. percent=" + percent);
				this.lastTargetLimitW = null;
				return;
			}

			// If delay elapsed, write the latest pending percent now.
			if (this.pendingPercentWrite != null && nowMs >= this.pendingPercentWriteDueAtMs) {
				this.pendingPercentWrite = Short.valueOf(newPercentShort);

				if (!canWriteNow) {
					a.setPortOnOff(true);
					a.setLimitPercentUi(Integer.valueOf(percent));
					a.debug("PowerLimit: pending (default) -> skip (rate-limit). percent=" + percent);
					this.lastTargetLimitW = null;
					return;
				}

				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(percent));

				a.debug("PowerLimit: WRITE pending default percent=" + percent);
				a.schedulePercentWrite(newPercentShort);

				this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
				this.lastTargetLimitW = null;
				this.lastLimitWriteTimestampMs = nowMs;

				this.pendingPercentWrite = null;
				this.pendingPercentWriteDueAtMs = 0L;
				return;
			}

			a.setPortOnOff(true);
			a.setLimitPercentUi(Integer.valueOf(percent));

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
				//mrdomek Why: A 0W target is modeled as OFF; clear any pending write and mark state as OFF.
				this.pendingPercentWrite = null;
				this.pendingPercentWriteDueAtMs = 0L;
				this.lastPortOn = false;

				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
			} else {
				this.lastPortOn = true;
				a.setPortOnOff(true);
				a.setLimitPercentUi(null);
			}
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

		if (newPercentShort <= 0) {
			//mrdomek Why: A 0% command is modeled as OFF; clear any pending write and mark state as OFF.
			this.pendingPercentWrite = null;
			this.pendingPercentWriteDueAtMs = 0L;
			this.lastPortOn = false;

			a.setPortOnOff(false);
			a.setLimitPercentUi(Integer.valueOf(0));
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		//mrdomek Why: After OFF->ON, delay the percent write so the device can accept it reliably.
		if (!this.lastPortOn) {
			this.lastPortOn = true;
			this.pendingPercentWrite = Short.valueOf(newPercentShort);
			this.pendingPercentWriteDueAtMs = nowMs + TURN_ON_TO_LIMIT_WRITE_DELAY_MS;

			a.setPortOnOff(true);
			a.setLimitPercentUi(Integer.valueOf(percent));
			a.debug("PowerLimit: OFF->ON (target) -> delay percent write by " + TURN_ON_TO_LIMIT_WRITE_DELAY_MS + "ms. percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);

			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		// If we are in a pending delay window, keep updating the pending percent and return.
		if (this.pendingPercentWrite != null && nowMs < this.pendingPercentWriteDueAtMs) {
			this.pendingPercentWrite = Short.valueOf(newPercentShort);

			a.setPortOnOff(true);
			a.setLimitPercentUi(Integer.valueOf(percent));
			a.debug("PowerLimit: pending (target) -> update percent during delay window. percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);

			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		// If delay elapsed, write the latest pending percent now.
		if (this.pendingPercentWrite != null && nowMs >= this.pendingPercentWriteDueAtMs) {
			this.pendingPercentWrite = Short.valueOf(newPercentShort);

			if (!canWriteNow) {
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(percent));
				a.debug("PowerLimit: pending (target) -> skip (rate-limit). percent=" + percent
						+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
						+ " mpptActive=" + mpptActive + "/" + mpptTotal);
				this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
				return;
			}

			a.setPortOnOff(true);
			a.setLimitPercentUi(Integer.valueOf(percent));

			a.debug("PowerLimit: WRITE pending percent=" + percent
					+ " target=" + effectiveTargetW + "W baseW=" + baseW + "W"
					+ " mpptActive=" + mpptActive + "/" + mpptTotal);

			a.schedulePercentWrite(newPercentShort);

			this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			this.lastLimitWriteTimestampMs = nowMs;

			this.pendingPercentWrite = null;
			this.pendingPercentWriteDueAtMs = 0L;
			return;
		}

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
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
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
	//mrdomek Why: Expose internal state for component debug output without changing write logic.
	public Integer getLastTargetLimitW() {
		return this.lastTargetLimitW;
	}

	//mrdomek Why: Expose internal state for component debug output without changing write logic.
	public Short getLastWrittenLimitPercent() {
		return this.lastWrittenLimitPercent;
	}

}
