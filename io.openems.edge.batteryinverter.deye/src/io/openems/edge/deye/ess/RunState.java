package io.openems.edge.deye.ess;

/**
 * Abbildung des Deye "run state" (Register 500, Wertebereich [0..5]).
 *
 * 0 = standby
 * 1 = selfcheck
 * 2 = normal
 * 3 = alarm
 * 4 = fault
 * 5 = activating
 */
public enum RunState {

    STANDBY(0, "standby"),
    SELFCHECK(1, "selfcheck"),
    NORMAL(2, "normal"),
    ALARM(3, "alarm"),
    FAULT(4, "fault"),
    ACTIVATING(5, "activating"),

    /**
     * Fallback, falls der Wechselrichter einen unbekannten Code liefert.
     */
    UNKNOWN(-1, "unknown");

    private final int code;
    private final String label;

    RunState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * Rohwert aus Register 500.
     */
    public int getCode() {
        return this.code;
    }

    /**
     * Menschlich lesbarer Text (wird im String-Kanal RUN_STATE_TEXT verwendet).
     */
    @Override
    public String toString() {
        return this.label;
    }

    /**
     * Mappt einen int-Code aus Register 500 auf das passende Enum.
     *
     * @param code Wert aus Modbus-Register 500
     * @return RunState oder UNKNOWN
     */
    public static RunState fromCode(int code) {
        for (RunState state : RunState.values()) {
            if (state.code == code) {
                return state;
            }
        }
        return UNKNOWN;
    }
}
