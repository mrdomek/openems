package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Word+Bit based view for Hoymiles MI alarm words.
 *
 * //mrdomek Why: This enum is the SINGLE place where we name bits and define a default severity.
 * //mrdomek Future changes should be fast: adjust only the enum constants below.
 * //mrdomek No scattered if/else across the component.
 */
public enum HoymilesAlarmBit {

	/*
	 * ---------------------------
	 * Known / named bits (clear names)
	 * ---------------------------
	 */

	// Alarm3 (register 0x390A)
	ALARM3_MPPT_A_UNDERVOLTAGE_PV1_PV2(3, 12, Severity.INFO, "MPPT A undervoltage (PV1/PV2)"),
	ALARM3_MPPT_B_UNDERVOLTAGE_PV3_PV4(3, 13, Severity.INFO, "MPPT B undervoltage (PV3/PV4)"),
	ALARM3_MPPT_C_UNDERVOLTAGE_PV5_PV6(3, 14, Severity.INFO, "MPPT C undervoltage (PV5/PV6)"),
	ALARM3_PV2_UNDERVOLTAGE(3, 15, Severity.INFO, "Undervoltage (PV2)"),

	// Alarm4 (register 0x390B)
	ALARM4_PV1_NO_INPUT(4, 2, Severity.WARNING, "PV1 no input"),
	ALARM4_PV2_NO_INPUT(4, 3, Severity.WARNING, "PV2 no input"),
	ALARM4_PV3_NO_INPUT(4, 4, Severity.WARNING, "PV3 no input"),
	ALARM4_PV4_NO_INPUT(4, 5, Severity.WARNING, "PV4 no input"),
	ALARM4_PV5_NO_INPUT(4, 6, Severity.WARNING, "PV5 no input"),
	ALARM4_PV6_NO_INPUT(4, 7, Severity.WARNING, "PV6 no input"),

	/*
	 * ---------------------------
	 * Generic bits (fallback for unknown meanings)
	 * ---------------------------
	 *
	 * These are NOT tied to a specific alarm word.
	 * They are used to represent unknown bits in a word while still keeping the output consistent.
	 */
	BIT0_GENERIC(0, 0, Severity.FAULT, "Generic bit 0 (unknown)"),
	BIT1_GENERIC(0, 1, Severity.FAULT, "Generic bit 1 (unknown)"),
	BIT2_GENERIC(0, 2, Severity.FAULT, "Generic bit 2 (unknown)"),
	BIT3_GENERIC(0, 3, Severity.FAULT, "Generic bit 3 (unknown)"),
	BIT4_GENERIC(0, 4, Severity.FAULT, "Generic bit 4 (unknown)"),
	BIT5_GENERIC(0, 5, Severity.FAULT, "Generic bit 5 (unknown)"),
	BIT6_GENERIC(0, 6, Severity.FAULT, "Generic bit 6 (unknown)"),
	BIT7_GENERIC(0, 7, Severity.FAULT, "Generic bit 7 (unknown)"),
	BIT8_GENERIC(0, 8, Severity.FAULT, "Generic bit 8 (unknown)"),
	BIT9_GENERIC(0, 9, Severity.FAULT, "Generic bit 9 (unknown)"),
	BIT10_GENERIC(0, 10, Severity.FAULT, "Generic bit 10 (unknown)"),
	BIT11_GENERIC(0, 11, Severity.FAULT, "Generic bit 11 (unknown)"),
	BIT12_GENERIC(0, 12, Severity.FAULT, "Generic bit 12 (unknown)"),
	BIT13_GENERIC(0, 13, Severity.FAULT, "Generic bit 13 (unknown)"),
	BIT14_GENERIC(0, 14, Severity.FAULT, "Generic bit 14 (unknown)"),
	BIT15_GENERIC(0, 15, Severity.FAULT, "Generic bit 15 (unknown)");

	public enum Severity {
		FAULT, WARNING, INFO, IGNORED;
	}

	private final int alarmWordIndex; // 1..6 for specific, 0 for generic
	private final int bitIndex; // 0..15
	private final Severity defaultSeverity;
	private final String description;

	private HoymilesAlarmBit(int alarmWordIndex, int bitIndex, Severity defaultSeverity, String description) {
		this.alarmWordIndex = alarmWordIndex;
		this.bitIndex = bitIndex;
		this.defaultSeverity = defaultSeverity;
		this.description = description;
	}

	public int alarmWordIndex() {
		return this.alarmWordIndex;
	}

	public int bitIndex() {
		return this.bitIndex;
	}

	public Severity defaultSeverity() {
		return this.defaultSeverity;
	}

	public String description() {
		return this.description;
	}

	public boolean isSet(int code) {
		return ((code & 0xFFFF) & (1 << this.bitIndex)) != 0;
	}

	public boolean isGeneric() {
		return this.alarmWordIndex == 0;
	}

	/**
	 * Returns all named bits for a specific alarm word that are set in the given word value.
	 *
	 * @param alarmWordIndex 1..6
	 * @param code           16-bit alarm word
	 */
	public static EnumSet<HoymilesAlarmBit> fromAlarmWord(int alarmWordIndex, int code) {
		EnumSet<HoymilesAlarmBit> result = EnumSet.noneOf(HoymilesAlarmBit.class);
		for (HoymilesAlarmBit bit : HoymilesAlarmBit.values()) {
			if (bit.isGeneric()) {
				continue;
			}
			if (bit.alarmWordIndex != alarmWordIndex) {
				continue;
			}
			if (bit.isSet(code)) {
				result.add(bit);
			}
		}
		return result;
	}

	/**
	 * Returns a list of unknown bit positions (0..15) that are set in a word but not covered by a named enum constant.
	 *
	 * //mrdomek Why: We want explicit visibility for unknown bits while keeping a stable naming scheme.
	 */
	public static List<Integer> unknownSetBitsInWord(int alarmWordIndex, int code) {
		final int v = code & 0xFFFF;

		int knownMask = 0;
		for (HoymilesAlarmBit bit : HoymilesAlarmBit.values()) {
			if (bit.isGeneric()) {
				continue;
			}
			if (bit.alarmWordIndex != alarmWordIndex) {
				continue;
			}
			knownMask |= (1 << bit.bitIndex);
		}

		final int unknown = v & ~knownMask;
		List<Integer> out = new ArrayList<>();
		for (int b = 0; b <= 15; b++) {
			if ((unknown & (1 << b)) != 0) {
				out.add(Integer.valueOf(b));
			}
		}
		return out;
	}

	public static HoymilesAlarmBit genericBit(int bitIndex) {
		for (HoymilesAlarmBit bit : HoymilesAlarmBit.values()) {
			if (bit.isGeneric() && bit.bitIndex == bitIndex) {
				return bit;
			}
		}
		return null;
	}
}
