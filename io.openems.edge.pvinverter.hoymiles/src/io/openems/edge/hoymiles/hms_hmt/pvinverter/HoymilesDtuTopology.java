package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds DTU topology table (MI -> DeviceModel -> inputChannels -> global port offsets)
 * from DTU data (inverterCount + serial strings).
 */
//mrdomek Why: keep parsing/math isolated and testable; the Impl just feeds raw values and logs the result.
public final class HoymilesDtuTopology {

	public static final int MAX_PORTS = 99;

	public static final class Entry {
		public final int miIndex; // 1-based
		public final String serialHex12; // may be null
		public final int prefixU16; // 0..65535, -1 if invalid
		public final DeviceModel modelOrNull;
		public final int inputChannels; // derived; 0 if unknown
		public final int globalPortStart; // 1-based
		public final int globalPortEnd; // inclusive; may be < start if inputChannels==0

		private Entry(int miIndex, String serialHex12, int prefixU16, DeviceModel modelOrNull, int inputChannels,
				int globalPortStart) {
			this.miIndex = miIndex;
			this.serialHex12 = serialHex12;
			this.prefixU16 = prefixU16;
			this.modelOrNull = modelOrNull;
			this.inputChannels = inputChannels;
			this.globalPortStart = globalPortStart;
			this.globalPortEnd = (inputChannels <= 0) ? (globalPortStart - 1) : (globalPortStart + inputChannels - 1);
		}

		public String prefixHex4() {
			if (this.prefixU16 < 0) {
				return "n/a";
			}
			return String.format("0x%04X", this.prefixU16 & 0xFFFF);
		}

		@Override
		public String toString() {
			final String model = this.modelOrNull == null ? "UNKNOWN" : this.modelOrNull.toString();
			return "MI" + this.miIndex + " serial=" + (this.serialHex12 == null ? "null" : this.serialHex12)
					+ " prefix=" + this.prefixHex4() + " model=" + model + " inputs=" + this.inputChannels
					+ " ports=[" + this.globalPortStart + ".." + this.globalPortEnd + "]";
		}
	}

	public static final class Result {
		public final int inverterCountReported;
		public final int inverterCountBuilt;
		public final List<Entry> entries;
		public final int totalPorts;
		public final int selectedMiIndex;
		public final int selectedWritePort; // global port index for control (start-port of selected MI)
		public final boolean ready;
		public final String signature; // stable signature for change detection (reported count + needed prefixes)
		public final String debugSummary;

		private Result(int inverterCountReported, int inverterCountBuilt, List<Entry> entries, int totalPorts,
				int selectedMiIndex, int selectedWritePort, boolean ready, String signature, String debugSummary) {
			this.inverterCountReported = inverterCountReported;
			this.inverterCountBuilt = inverterCountBuilt;
			this.entries = entries;
			this.totalPorts = totalPorts;
			this.selectedMiIndex = selectedMiIndex;
			this.selectedWritePort = selectedWritePort;
			this.ready = ready;
			this.signature = signature;
			this.debugSummary = debugSummary;
		}
	}

	private HoymilesDtuTopology() {
	}

	/**
	 * Builds DTU topology based on:
	 * - inverterCount (how many microinverters are registered)
	 * - serialHex12 list (12 hex chars per microinverter; we only require the first 4 chars)
	 *
	 * @param inverterCount count from DTU (clamped 0..99)
	 * @param selectedMiIndex config.microinverterNumber (1..99)
	 * @param serialHex12List list of serial strings for MI1..MI(n); size defines how many MIs are available
	 */
	public static Result buildFromSerials(int inverterCount, int selectedMiIndex, List<String> serialHex12List) {
		final int reported = clamp(inverterCount, 0, MAX_PORTS);

		final int sel = clamp(selectedMiIndex, 1, MAX_PORTS);

		// We only need MI1..MI(sel) to compute the selected write port.
		final int needed = Math.min(sel, serialHex12List == null ? 0 : serialHex12List.size());

		final List<Entry> entries = new ArrayList<>(needed);

		final StringBuilder sig = new StringBuilder(8 * (needed + 1));
		sig.append(reported).append(':');

		int portStart = 1;
		int totalPorts = 0;

		boolean ready = true;
		int selectedPort = 1;

		for (int mi = 1; mi <= needed; mi++) {
			final String serial = serialHex12List.get(mi - 1);

			final int prefixU16 = parsePrefixU16(serial);
			final DeviceModel model = (prefixU16 < 0) ? null : DeviceModel.findBySerialWord0(prefixU16);
			final int inputs = (model == null) ? 0 : model.getInputChannels();

			sig.append(prefixU16 < 0 ? "????" : String.format("%04X", prefixU16 & 0xFFFF)).append(',');

			entries.add(new Entry(mi, serial, prefixU16, model, inputs, portStart));

			if (inputs <= 0) {
				//mrdomek Why: without a known model we cannot compute offsets safely.
				ready = false;
			} else {
				portStart += inputs;
				totalPorts += inputs;
			}
		}

		// If DTU reports fewer inverters than selected, offset is unsafe.
		if (reported < sel) {
			ready = false;
		}

		// If we couldn't build all needed entries (e.g. missing serials), offset is unsafe.
		if (needed < sel) {
			ready = false;
		}

		// Selected write port is the start-port of the selected MI (if we have it)
		if (sel >= 1 && sel <= entries.size()) {
			selectedPort = entries.get(sel - 1).globalPortStart;
		} else {
			selectedPort = 1;
		}

		// Total port count sanity
		if (totalPorts > MAX_PORTS) {
			ready = false;
		}

		final String signature = sig.toString();
		final String debugSummary = buildDebugSummary(reported, sel, selectedPort, totalPorts, entries, signature, ready);

		return new Result(reported, needed, entries, totalPorts, sel, selectedPort, ready, signature, debugSummary);
	}

	private static String buildDebugSummary(int reported, int selectedMiIndex, int selectedPort, int totalPorts,
			List<Entry> entries, String signature, boolean ready) {
		final StringBuilder sb = new StringBuilder(512);
		sb.append("DTU topology: reportedInverters=").append(reported)
				.append(" builtEntries=").append(entries.size())
				.append(" selectedMi=").append(selectedMiIndex)
				.append(" selectedWritePort=").append(selectedPort)
				.append(" totalPorts=").append(totalPorts)
				.append(" ready=").append(ready)
				.append(" signature=").append(signature)
				.append('\n');

		for (Entry e : entries) {
			sb.append(" - ").append(e.toString()).append('\n');
		}
		return sb.toString();
	}

	private static int parsePrefixU16(String serialHex12) {
		if (serialHex12 == null) {
			return -1;
		}
		if (serialHex12.length() < 4) {
			return -1;
		}
		try {
			return Integer.parseInt(serialHex12.substring(0, 4), 16) & 0xFFFF;
		} catch (Exception e) {
			return -1;
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}
}
