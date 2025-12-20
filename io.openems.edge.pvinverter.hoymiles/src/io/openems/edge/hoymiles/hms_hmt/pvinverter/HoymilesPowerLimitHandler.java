package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Handles conversion of a power limit in W (OpenEMS) to a percent limit (Hoymiles/DTU),
 * including hysteresis and rate-limiting of Modbus writes.
 *
 * Background:
 * - OpenEMS controllers typically work in AC Watts (targetLimitW).
 * - Hoymiles expects a percent value, which (depending on DTU/firmware) effectively limits
 *   DC power per PV input relative to the currently available DC power.
 *
 * This helper supports:
 * 1) Open-loop: W -> % based on nominal max AC power (classic behavior).
 * 2) AC-feedback: tries to reach the requested AC power by adapting the percent based on
 *    current AC power and current DC power (sum of PV inputs).
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

	//mrdomek Closed-loop state (used only by applyAcRegulated()).
	private double integralPercent = 0.0;
	private long lastControlUpdateTimestampMs = 0L;

	//mrdomek Conservative PI gains. We only update when a DTU write is allowed (rate-limited).
	private static final double KP = 0.30;
	private static final double KI = 0.06;

	//mrdomek Anti-windup guard for the integral term (in percent-units).
	private static final double INTEGRAL_LIMIT = 80.0;

	public HoymilesPowerLimitHandler(int hysteresisW, long minWriteIntervalMs) {
		this.hysteresisW = hysteresisW;
		this.minWriteIntervalMs = minWriteIntervalMs;
	}

	public void reset() {
		this.lastTargetLimitW = null;
		this.lastWrittenLimitPercent = null;
		this.integralPercent = 0.0;
		this.lastControlUpdateTimestampMs = 0L;
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
	 * Applies a target limit (open-loop).
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
			this.integralPercent = 0.0;
			this.lastControlUpdateTimestampMs = 0L;
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
			this.integralPercent = 0.0;
			this.lastControlUpdateTimestampMs = 0L;
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
			this.integralPercent = 0.0;
			this.lastControlUpdateTimestampMs = 0L;
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
		double percentExact = (effectiveTargetW * 100.0) / (double) maxTotalPowerW;
		int percentInt = (int) Math.round(percentExact);

		// Clamp
		if (percentInt < 0) {
			percentInt = 0;
		}
		if (percentInt > 100) {
			percentInt = 100;
		}
		if (minPercent > 0 && percentInt < minPercent) {
			percentInt = minPercent;
		}

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percentInt));

		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == (short) percentInt) {
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		//mrdomek Rate-limit DTU writes to avoid stalling live reads.
		if (canWriteNow) {
			a.schedulePercentWrite((short) percentInt);
			this.lastWrittenLimitPercent = Short.valueOf((short) percentInt);
			this.lastLimitWriteTimestampMs = nowMs;
		}

		this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
	}

	/**
	 * Applies a target limit (AC feedback / closed-loop).
	 *
	 * Why:
	 * - Some Hoymiles devices interpret the percent limit as "percent of available DC power",
	 *   which means a fixed percent does not map to a fixed AC power under varying irradiation.
	 *
	 * Strategy:
	 * - Feed-forward: percentFF = targetAcW / dcBaseW * 100 (dcBaseW = sum of PV input powers if available).
	 * - Feedback: PI controller based on AC error (targetAcW - actualAcW), converted to percent-units.
	 *
	 * Note:
	 * - Control updates (integrator + potential write) are rate-limited via minWriteIntervalMs.
	 *
	 * @param targetLimitW      requested AC limit in W; null means "no limit active"
	 * @param maxTotalPowerW    nominal max AC power in W; <=0 means unknown
	 * @param minPercent        minimum percent supported by device generation; may be 0 if unknown
	 * @param actualAcPowerW    measured AC active power in W (positive = production)
	 * @param actualDcPowerW    measured DC power sum in W (sum of PV input powers); may be 0/unknown
	 * @param dcPeakTotalW      configured DC peak sum in W (sum of module peak powers); may be 0/unknown
	 * @param nowMs             current time in ms
	 * @param a                 callbacks to component
	 */
	public void applyAcRegulated(Integer targetLimitW, int maxTotalPowerW, int minPercent, int actualAcPowerW,
			int actualDcPowerW, int dcPeakTotalW, long nowMs, Actions a) {

		// Mirror input in UI (even if null)
		a.setLimitWUi(targetLimitW);

		// If we cannot regulate, fall back to open-loop.
		if (maxTotalPowerW <= 0) {
			this.apply(targetLimitW, maxTotalPowerW, minPercent, nowMs, a);
			return;
		}

		final boolean canWriteNow = (nowMs - this.lastLimitWriteTimestampMs) >= this.minWriteIntervalMs;

		/*
		 * No active limit -> return to 100% (rate-limited), but only if we had sent something else before.
		 */
		if (targetLimitW == null) {
			if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() != 100) {
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(100));

				if (canWriteNow) {
					a.schedulePercentWrite((short) 100);
					this.lastWrittenLimitPercent = Short.valueOf((short) 100);
					this.lastLimitWriteTimestampMs = nowMs;
				}
			}

			this.lastTargetLimitW = null;
			this.integralPercent = 0.0;
			this.lastControlUpdateTimestampMs = 0L;
			return;
		}

		final int normalizedW = Math.max(0, targetLimitW.intValue());

		/*
		 * 0 W -> OFF.
		 */
		if (normalizedW <= 0) {
			a.setPortOnOff(false);
			a.setLimitPercentUi(Integer.valueOf(0));
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			this.integralPercent = 0.0;
			this.lastControlUpdateTimestampMs = 0L;
			return;
		}

		/*
		 * Enforce device minimum percent -> convert to minW (AC-based).
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
		 * If the target changes only slightly, do not re-write immediately.
		 * (We still allow regulation later via PI when canWriteNow.)
		 */
		if (this.lastTargetLimitW != null) {
			int deltaW = Math.abs(effectiveTargetW - this.lastTargetLimitW.intValue());
			if (deltaW < this.hysteresisW) {
				// keep lastTargetLimitW unchanged on purpose
			} else {
				this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			}
		} else {
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
		}

		/*
		 * Choose DC base:
		 * - Prefer actual DC power (sum of PV input powers) because it reflects irradiation.
		 * - Else use configured DC peak sum.
		 * - Else fall back to nominal AC max.
		 */
		final double dcBaseW;
		if (actualDcPowerW > 0) {
			dcBaseW = (double) actualDcPowerW;
		} else if (dcPeakTotalW > 0) {
			dcBaseW = (double) dcPeakTotalW;
		} else {
			dcBaseW = (double) maxTotalPowerW;
		}

		/*
		 * Feed-forward percent based on DC base.
		 */
		double percentFf = (effectiveTargetW * 100.0) / dcBaseW;

		/*
		 * PI feedback based on AC error, converted into percent-units.
		 */
		double percentCmd = percentFf;

		if (canWriteNow) {
			final double dtSeconds;
			if (this.lastControlUpdateTimestampMs <= 0L) {
				dtSeconds = this.minWriteIntervalMs / 1000.0;
			} else {
				dtSeconds = Math.max(0.5, (nowMs - this.lastControlUpdateTimestampMs) / 1000.0);
			}

			int acW = Math.max(0, actualAcPowerW);
			double errorW = (double) (effectiveTargetW - acW);
			double errorPercent = (errorW * 100.0) / dcBaseW;

			// Update integrator (anti-windup via clamp)
			this.integralPercent += (errorPercent * dtSeconds);
			if (this.integralPercent > INTEGRAL_LIMIT) {
				this.integralPercent = INTEGRAL_LIMIT;
			} else if (this.integralPercent < -INTEGRAL_LIMIT) {
				this.integralPercent = -INTEGRAL_LIMIT;
			}

			double correction = (KP * errorPercent) + (KI * this.integralPercent);
			percentCmd = percentFf + correction;

			this.lastControlUpdateTimestampMs = nowMs;
		}

		int percentInt = (int) Math.round(percentCmd);

		// Clamp to 0..100 and to device min.
		if (percentInt < 0) {
			percentInt = 0;
		}
		if (percentInt > 100) {
			percentInt = 100;
		}
		if (minPercent > 0 && percentInt < minPercent) {
			percentInt = minPercent;
		}

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percentInt));

		/*
		 * Avoid redundant writes. Still update the UI percent every cycle.
		 */
		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == (short) percentInt) {
			return;
		}

		if (canWriteNow) {
			a.schedulePercentWrite((short) percentInt);
			this.lastWrittenLimitPercent = Short.valueOf((short) percentInt);
			this.lastLimitWriteTimestampMs = nowMs;
		}
	}
}
