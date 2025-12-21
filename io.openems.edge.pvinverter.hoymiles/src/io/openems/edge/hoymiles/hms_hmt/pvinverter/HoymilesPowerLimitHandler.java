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

		/**
		 * Debug hook; implementation may be no-op if debugMode=false.
		 */
		//mrdomek Use this to log *why* a write was skipped (rate-limit/hysteresis/etc.).
		void debug(String message);
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
	 * Applies a target limit (simple W -> % based on nominal max power).
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
					a.debug("PowerLimit: target=null -> write 100% (remove limit).");
					a.schedulePercentWrite((short) 100);
					this.lastWrittenLimitPercent = Short.valueOf((short) 100);
					this.lastLimitWriteTimestampMs = nowMs;
				} else {
					a.debug("PowerLimit: target=null -> skip 100% write (rate-limit active).");
				}
			} else {
				a.debug("PowerLimit: target=null -> no action (already at 100% or never limited).");
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
				a.debug("PowerLimit: maxTotalPowerW<=0 and target<=0 -> OFF (no % writes possible).");
				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
			} else {
				a.debug("PowerLimit: maxTotalPowerW<=0 and target>0 -> ON (no % writes possible).");
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
			a.debug("PowerLimit: target<=0 -> OFF (no % write).");
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
			a.debug("PowerLimit: target below minW -> clamp to minW.");
			effectiveTargetW = minW;
		}

		/*
		 * Watt hysteresis.
		 */
		if (this.lastTargetLimitW != null) {
			int deltaW = Math.abs(effectiveTargetW - this.lastTargetLimitW.intValue());
			if (deltaW < this.hysteresisW) {
				a.debug("PowerLimit: skip (W-hysteresis). deltaW=" + deltaW + "W < " + this.hysteresisW + "W");
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
			a.debug("PowerLimit: skip (percent unchanged). percent=" + percent);
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		//mrdomek Rate-limit DTU writes to avoid stalling live reads.
		if (!canWriteNow) {
			long remaining = this.minWriteIntervalMs - (nowMs - this.lastLimitWriteTimestampMs);
			if (remaining < 0) {
				remaining = 0;
			}
			a.debug("PowerLimit: skip (rate-limit). remaining=" + remaining + "ms");
			return;
		}

		a.debug("PowerLimit: WRITE percent=" + percent + " (simple W->%).");
		a.schedulePercentWrite(newPercentShort);
		this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
		this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
		this.lastLimitWriteTimestampMs = nowMs;
	}

	/**
	 * Applies a target limit using AC closed-loop friendly conversion:
	 * percent is calculated relative to "available DC" (observed Hoymiles behavior).
	 *
	 * @param targetLimitW      AC target in W; null means "no limit active"
	 * @param maxTotalPowerW    nominal AC max power in W; <=0 means unknown
	 * @param minPercent        minimum percent supported by device generation; may be 0 if unknown
	 * @param actualAcPowerW    current AC output power in W
	 * @param actualDcPowerW    current sum of PV input powers in W (may be 0 at night or if limited)
	 * @param dcPeakTotalW      configured sum of module peak powers in W (fallback)
	 * @param nowMs             current time in ms
	 * @param a                 callbacks to component
	 */
	public void applyAcRegulated(Integer targetLimitW, int maxTotalPowerW, int minPercent, int actualAcPowerW,
			int actualDcPowerW, int dcPeakTotalW, long nowMs, Actions a) {

		final boolean canWriteNow = (nowMs - this.lastLimitWriteTimestampMs) >= this.minWriteIntervalMs;

		// Mirror input in UI (even if null)
		a.setLimitWUi(targetLimitW);

		if (targetLimitW == null) {
			if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() != 100) {
				a.setPortOnOff(true);
				a.setLimitPercentUi(Integer.valueOf(100));

				if (canWriteNow) {
					a.debug("PowerLimit: target=null -> write 100% (remove limit).");
					a.schedulePercentWrite((short) 100);
					this.lastWrittenLimitPercent = Short.valueOf((short) 100);
					this.lastLimitWriteTimestampMs = nowMs;
				} else {
					a.debug("PowerLimit: target=null -> skip 100% write (rate-limit active).");
				}
			} else {
				a.debug("PowerLimit: target=null -> no action (already at 100% or never limited).");
			}

			this.lastTargetLimitW = null;
			return;
		}

		final int normalizedW = Math.max(0, targetLimitW.intValue());

		if (maxTotalPowerW <= 0) {
			if (normalizedW <= 0) {
				a.debug("PowerLimit: maxTotalPowerW<=0 and target<=0 -> OFF (no % writes possible).");
				a.setPortOnOff(false);
				a.setLimitPercentUi(Integer.valueOf(0));
			} else {
				a.debug("PowerLimit: maxTotalPowerW<=0 and target>0 -> ON (no % writes possible).");
				a.setPortOnOff(true);
				a.setLimitPercentUi(null);
			}

			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			return;
		}

		if (normalizedW <= 0) {
			a.debug("PowerLimit: target<=0 -> OFF (no % write).");
			a.setPortOnOff(false);
			a.setLimitPercentUi(Integer.valueOf(0));
			this.lastWrittenLimitPercent = null;
			this.lastTargetLimitW = null;
			return;
		}

		//mrdomek Clamp to inverter AC max; controller may request higher.
		final int effectiveTargetW = Math.min(normalizedW, maxTotalPowerW);

		/*
		 * Percent calculation:
		 * Observed: percent behaves like "x% of currently available DC".
		 * Use actualDcPowerW as primary base; fallback to configured dcPeakTotalW.
		 */
		int baseDcW = actualDcPowerW;
		String baseReason = "actualDcPowerW";
		if (baseDcW <= 0) {
			baseDcW = dcPeakTotalW;
			baseReason = "dcPeakTotalW";
		}
		if (baseDcW <= 0) {
			baseDcW = maxTotalPowerW;
			baseReason = "maxTotalPowerW";
		}

		final double ratioExact = (double) effectiveTargetW / (double) baseDcW;
		int percent = (int) Math.round(ratioExact * 100.0);

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

		//mrdomek If target exceeds available DC base, we saturate at 100% (cannot reach target power).
		if (percent == 100 && effectiveTargetW > baseDcW) {
			a.debug("PowerLimit: SATURATED to 100% because target=" + effectiveTargetW + "W > baseDc=" + baseDcW + "W (" + baseReason + "). "
					+ "AC=" + actualAcPowerW + "W DC=" + actualDcPowerW + "W dcPeak=" + dcPeakTotalW + "W");
		}

		a.setPortOnOff(true);
		a.setLimitPercentUi(Integer.valueOf(percent));

		final short newPercentShort = (short) percent;

		if (this.lastWrittenLimitPercent != null && this.lastWrittenLimitPercent.shortValue() == newPercentShort) {
			a.debug("PowerLimit: skip (percent unchanged). percent=" + percent
					+ " baseDc=" + baseDcW + "W (" + baseReason + ")"
					+ " target=" + effectiveTargetW + "W"
					+ " ac=" + actualAcPowerW + "W dc=" + actualDcPowerW + "W");
			this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
			return;
		}

		if (!canWriteNow) {
			long remaining = this.minWriteIntervalMs - (nowMs - this.lastLimitWriteTimestampMs);
			if (remaining < 0) {
				remaining = 0;
			}
			a.debug("PowerLimit: skip (rate-limit). remaining=" + remaining + "ms"
					+ " nextPercent=" + percent
					+ " baseDc=" + baseDcW + "W (" + baseReason + ")"
					+ " target=" + effectiveTargetW + "W"
					+ " ac=" + actualAcPowerW + "W dc=" + actualDcPowerW + "W");
			return;
		}

		a.debug("PowerLimit: WRITE percent=" + percent
				+ " baseDc=" + baseDcW + "W (" + baseReason + ")"
				+ " target=" + effectiveTargetW + "W"
				+ " ac=" + actualAcPowerW + "W dc=" + actualDcPowerW + "W"
				+ " dcPeak=" + dcPeakTotalW + "W");
		a.schedulePercentWrite(newPercentShort);

		this.lastWrittenLimitPercent = Short.valueOf(newPercentShort);
		this.lastTargetLimitW = Integer.valueOf(effectiveTargetW);
		this.lastLimitWriteTimestampMs = nowMs;
	}

}
