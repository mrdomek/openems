package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Handles conversion of a power limit in W (OpenEMS) to a percent limit (Hoymiles/DTU),
 * including hysteresis and rate-limiting of Modbus writes.
 */
//mrdomek Keep all power-limit state here to keep the component class readable.
public final class HoymilesPowerLimitHandler {

	public interface Actions {
		void setPortOnOff(boolean on);

		/**
		 * Mirrors the requested limit in W for UI/debug (may be null).
		 */
		void setLimitWUi(Integer w);

		/**
		 * Mirrors the percent that is considered "last sent/active" for UI/debug (may be null).
		 */
		void setLimitPercentUi(Integer percent);

		/**
		 * Schedules the actual Modbus write of the percent value (DTU expects percent).
		 */
		void schedulePercentWrite(short percent);
	}

	private final int hysteresisW;
	private final long minWriteIntervalMs;

	private Integer lastTargetLimitW = null;
	private Short lastWrittenLimitPercent = null;
	private long lastLimitWriteTimestampMs = 0L;

	public HoymilesPowerLimitHandler(int hysteresisW, long minWriteIntervalMs) {
		this.hysteresisW = hysteresisW;
		this.minWriteIntervalMs = minWriteIntervalMs;
	}

	public void reset() {
		this.lastTargetLimitW = null;
		this.lastWrittenLimitPercent = null;
	}

	public Integer getLastTargetLimitW() {
		return this.lastTargetLimitW;
	}

	public Short getLastWrittenLimitPercent() {
		return this.lastWrittenLimitPercent;
	}

	public long getLastLimitWriteTimestampMs() {
		return this.lastLimitWriteTimestampMs;
	}

	/**
	 * Applies a target limit.
	 *
	 * @param targetLimitW      limit in W; null means "no limit active"
	 * @param maxTotalPowerW    nominal max power in W; <=0 means unknown
	 * @param minPercent        minimum percent supported by device generation; may be 0 if unknown
	 * @param nowMs             current time in ms
	 * @param a                 callbacks to component
	 */
	public void apply(Integer targetLimitW, int maxTotalPowerW, int minPercent, long nowMs, Actions a) {
		final boolean canWriteNow = (nowMs - this.lastLimitWriteTimestampMs) >= this.minWriteIntervalMs;

		// Mirror input in UI (even if null)
		a.setLimitWUi(targetLimitW);

		/*
		 * No active limit -> return to 100% (rate-limited), but only if we had sent something else before.
		 */
		if (targetLimitW == null) {
			if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() != 100) {
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(100));

				//mrdomek Rate-limit DTU writes to avoid stalling live reads.
				if (canWriteNow) {
					a.schedulePercentWrite((short) 100);
					this.lastWrittenLimitPercent = Short.valueOf((short) 100);
					this.lastLimitWriteTimestampMs = nowMs;
				}
			}

			this.lastTargetLimitW = null;
			return;
		}

		final int normalizedW = Math.max(0, targetLimitW.intValue());

		/*
		 * If we don't know max power: only on/off decision; no % limit write.
		 */
		if (maxTotalPowerW <= 0) {
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

		/*
		 * 0 W -> OFF (no percent write required).
		 */
		if (normalizedW <= 0) {
			a.setPortOnOff(false);
			a.setLimitPercentUi(Integer.valueOf(0));
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			return;
		}

		/*
		 * Enforce device minimum percent -> convert to minW.
		 */
		int minW = 0;
		if (minPercent > 0) {
			double minWExact = (maxTotalPowerW * (double) minPercent) / 100.0;
			minW = (int) Math.round(minWExact);
			if (minW <= 0) {
				minW = 1;
			}
		}

		int effectiveTargetW = normalizedW;
		if (minW > 0 && normalizedW < minW) {
			effectiveTargetW = minW;
		}

		/*
		 * Watt hysteresis.
		 */
		if (this.lastTargetLimitW != null) {
			int deltaW = Math.abs(effectiveTargetW - this.lastTargetLimitW.intValue());
			if (deltaW < this.hysteresisW) {
				return;
			}
		}

		/*
		 * W -> % (Hoymiles expects percent).
		 */
		double ratio = (double) effectiveTargetW / (double) maxTotalPowerW;
		int percent = (int) Math.round(ratio * 100.0);

		if (percent > 100) {
			percent = 100;
		}
		if (minPercent > 0 && percent < minPercent) {
			percent = minPercent;
		}
		if (percent < 0) {
			percent = 0;
		}

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percent));

		final short newPercentShort = (short) percent;

		/*
		 * Write only on change + rate-limit.
		 */
		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == newPercentShort) {
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		//mrdomek Rate-limit DTU writes to avoid stalling live reads.
		if (!canWriteNow) {
			return;
		}

		a.schedulePercentWrite(newPercentShort);
		this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
		this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
		this.lastLimitWriteTimestampMs = nowMs;
	}
}
