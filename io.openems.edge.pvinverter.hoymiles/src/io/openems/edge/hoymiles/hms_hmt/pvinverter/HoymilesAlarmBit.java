package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.EnumSet;

/**
 * Bit-based view for Hoymiles status / alarm words.
 *
 * This enum is intentionally generic, because the exact meaning of each bit
 * is not yet documented. It can be used for:
 *
 * - Microinverter Status (register 0x3907)
 * - Alarm Code 1..6 (registers 0x3908..0x390D)
 *
 * Each enum constant represents one bit (0..15) in the 16-bit word.
 */
public enum HoymilesAlarmBit {

    BIT0(0, "Bit 0 (reserved / unknown)"),
    BIT1(1, "Bit 1 (reserved / unknown)"),
    BIT2(2, "Bit 2 (reserved / unknown)"),
    BIT3(3, "Bit 3 (reserved / unknown)"),
    BIT4(4, "Bit 4 (reserved / unknown)"),
    BIT5(5, "Bit 5 (reserved / unknown)"),
    BIT6(6, "Bit 6 (reserved / unknown)"),
    BIT7(7, "Bit 7 (reserved / unknown)"),
    BIT8(8, "Bit 8 (reserved / unknown)"),
    BIT9(9, "Bit 9 (reserved / unknown)"),
    BIT10(10, "Bit 10 (reserved / unknown)"),
    BIT11(11, "Bit 11 (reserved / unknown)"),
    BIT12(12, "Bit 12 (reserved / unknown)"),
    BIT13(13, "Bit 13 (reserved / unknown)"),
    BIT14(14, "Bit 14 (reserved / unknown)"),
    BIT15(15, "Bit 15 (reserved / unknown)");

    private final int bitIndex;
    private final String description;

    private HoymilesAlarmBit(int bitIndex, String description) {
        this.bitIndex = bitIndex;
        this.description = description;
    }

    /**
     * Zero-based bit index inside the 16-bit status/alarm word.
     */
    public int bitIndex() {
        return this.bitIndex;
    }

    /**
     * Human-readable placeholder description.
     * Can be refined later once the protocol is fully known.
     */
    public String description() {
        return this.description;
    }

    /**
     * Returns true if this bit is set in the given status/alarm word.
     *
     * @param code 16-bit status / alarm word
     * @return true if the corresponding bit is set
     */
    public boolean isSet(int code) {
        return (code & (1 << this.bitIndex)) != 0;
    }

    /**
     * Decode all set bits from a given 16-bit status/alarm word.
     *
     * @param code 16-bit status / alarm word (0..65535)
     * @return EnumSet of all bits that are set; may be empty
     */
    public static EnumSet<HoymilesAlarmBit> fromCode(int code) {
        EnumSet<HoymilesAlarmBit> result = EnumSet.noneOf(HoymilesAlarmBit.class);
        for (HoymilesAlarmBit bit : HoymilesAlarmBit.values()) {
            if (bit.isSet(code)) {
                result.add(bit);
            }
        }
        return result;
    }

    /**
     * Compact string representation of all set bits, e.g. "BIT0,BIT3,BIT7".
     * Returns "NO_ALARM" if no bit is set.
     *
     * This is handy for debugLog(), log output or a simple UI string channel.
     */
    public static String toShortString(int code) {
        EnumSet<HoymilesAlarmBit> set = fromCode(code);
        if (set.isEmpty()) {
            return "NO_ALARM";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (HoymilesAlarmBit bit : set) {
            if (!first) {
                sb.append(',');
            }
            sb.append(bit.name());
            first = false;
        }
        return sb.toString();
    }
}
