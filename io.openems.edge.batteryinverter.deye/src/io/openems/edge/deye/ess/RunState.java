package io.openems.edge.deye.ess;

public enum RunState {
	OFF(0, "Off"),
	WAITING(1, "Waiting"),
	CHECKING(2, "Checking"),
	RUNNING(3, "Running"),
	FAULT(4, "Fault"),
	UNKNOWN(-1, "Unknown");

	private final int code;
	private final String description;

	RunState(int code, String description) {
		this.code = code;
		this.description = description;
	}

	public static RunState fromCode(int code) {
		for (RunState state : values()) {
			if (state.code == code) {
				return state;
			}
		}
		return UNKNOWN;
	}

	@Override
	public String toString() {
		return description;
	}
}
