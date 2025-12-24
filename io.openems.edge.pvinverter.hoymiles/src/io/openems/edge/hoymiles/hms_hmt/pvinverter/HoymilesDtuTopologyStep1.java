package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 1 helper: build DTU topology table (MI -> inputChannels -> global port offsets)
 * from DTU data (inverterCount + serial-word0 prefix list).
 */
//mrdomek Why: keep parsing/math isolated and testable; the Impl just feeds raw values and logs the result.
public final class HoymilesDtuTopologyStep1 {

	public static final int MAX_PORTS = 99;

	public static final class Entry {
		public final int miIndex; // 1-based
		public final int serialWord0U16; // 0..65535
		public final DeviceModel modelOrNull;
		public final int inputChannels; // derived
		public final int globalPortStart; // 1-based
		public final int globalPortEnd; // inclusive

		private Entry(int miIndex, int serialWord0U16, DeviceModel modelOrNull, int inputChannels, int globalPortStart) {
			this.miIndex = miIndex;
			this.serialWord0U16 = serialWord0U16;
			this.modelOrNull = modelOrNull;
			this.inputChannels = inputChannels;
			this.globalPortStart = globalPortStart;
			this.globalPortEnd = globalPortStart + inputChannels - 1;
		}

		public String prefixHex4() {
			return String.format("0x%04X", this.serialWord0U16 & 0xFFFF);
		}

		@Override
		public String toString() {
			final String model = this.modelOrNull == null ? "UNKNOWN" : this.modelOrNull.toString();
			return "MI" + this.miIndex + " prefix=" + this.prefixHex4() + " model=" + model + " inputs="
					+ this.inputChannels + " ports=[" + this.globalPortStart + ".." + this.globalPortEnd + "]";
		}
	}

	public static final class Result {
		public final int inverterCountReported;
		public final int inverterCountBuilt;
		public final List<Entry> entries;
		public final int totalPorts;
		public final int selectedMiIndex;
		public final int selectedWritePort; // global port index for control (start-port of selected MI)
		public final String signature; // stable signature for change detection (prefix list only, built entries only)
		public final String debugSummary;

		private Result(int inverterCountReported, int inverterCountBuilt, List<Entry> entries, int totalPorts,
				int selectedMiIndex, int selectedWritePort, String signature, String debugSummary) {
			this.inverterCountReported = inverterCountReported;
			this.inverterCountBuilt = inverterCountBuilt;
			this.entries = entries;
			this.totalPorts = totalPorts;
			this.selectedMiIndex = selectedMiIndex;
			this.selectedWritePort = selectedWritePort;
			this.signature = signature;
			this.debugSummary = debugSummary;
		}
	}

	private HoymilesDtuTopologyStep1() {
	}

	/**
	 * Builds DTU topology based on:
	 * - inverterCount (how many microinverters are registered)
	 * - serialWord0 list (one uint16 per microinverter, first 4 hex digits of serial)
	 *
	 * @param inverterCount count from DTU (clamped 0..99)
	 * @param selectedMiIndex config.microinverterNumber (1..99)
	 * @param serialWord0U16List list of word0 prefixes; size defines how many MIs are actually available
	 */
	public static Result build(int inverterCount, int selectedMiIndex, int[] serialWord0U16List) {
		final int reported = clamp(inverterCount, 0, MAX_PORTS);
		final int selected = clamp(selectedMiIndex, 1, MAX_PORTS);

		final int available = serialWord0U16List == null ? 0 : serialWord0U16List.length;
		final int built = Math.min(reported, available);

		final List<Entry> entries = new ArrayList<>(built);

		int nextPortStart = 1;

		final StringBuilder sig = new StringBuilder(256);
		sig.append(reported).append(':').append(built).append(':');

		for (int mi = 1; mi <= built; mi++) {
			final int word0 = (serialWord0U16List[mi - 1] & 0xFFFF);

			if (mi > 1) {
				sig.append(',');
			}
			sig.append(String.format("%04X", word0));

			final DeviceModel model = DeviceModel.findBySerialWord0(word0);
			final int inputs = model != null ? model.getInputChannels() : 1; // fail-safe minimal assumption

			entries.add(new Entry(mi, word0, model, inputs, nextPortStart));
			nextPortStart += inputs;
		}

		final int totalPorts = Math.max(0, nextPortStart - 1);

		int selectedWritePort = 1;
		for (Entry e : entries) {
			if (e.miIndex == selected) {
				selectedWritePort = e.globalPortStart; // control port == first port of this MI
				break;
			}
		}

		final String debug = buildDebug(reported, built, selected, selectedWritePort, totalPorts, entries, available);
		return new Result(reported, built, entries, totalPorts, selected, selectedWritePort, sig.toString(), debug);
	}

	private static String buildDebug(int reported, int built, int selectedMi, int selectedPort, int totalPorts,
			List<Entry> entries, int available) {
		final StringBuilder sb = new StringBuilder(1024);

		sb.append("DTU Topology Step1: inverterCountReported=").append(reported)
				.append(" inverterCountBuilt=").append(built)
				.append(" serialPrefixesAvailable=").append(available)
				.append(" selectedMI=").append(selectedMi)
				.append(" selectedWritePort=").append(selectedPort)
				.append(" totalPorts=").append(totalPorts)
				.append(" (max=").append(MAX_PORTS).append(")\n");

		for (Entry e : entries) {
			sb.append(" - ").append(e.toString()).append('\n');
		}
		return sb.toString();
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
