package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Supported Hoymiles HMS/HMT device models.
 *
 * This is used for:
 * - Identifying the device via Serial Number Prefix (Hex)
 * - number of DC inputs
 * - number of MPPTs
 * - max power per input
 * - max total AC output power
 * - single-/three-phase behaviour
 * - device generation (Gen3 for HMS/HMT)
 */
public enum DeviceModel {

    /*
     * --- HMS-1T Series (1-Phase, 1-Input) ---
     */
    HMS_300_1T("HMS-300-1T", 0x1124, 1, 1, 300, 300, false),
    HMS_350_1T("HMS-350-1T", 0x1124, 1, 1, 350, 350, false),
    HMS_400_1T("HMS-400-1T", 0x1124, 1, 1, 400, 400, false),
    
    // Batch/Special variants for 1T
    HMS_450_1T("HMS-450-1T", 0x1400, 1, 1, 450, 450, false),
    HMS_500_1T("HMS-500-1T", 0x1125, 1, 1, 500, 500, false),

    /*
     * --- HMS-2T Series (1-Phase, 2-Inputs) ---
     * Prefix 1144 covers HMS-600 to HMS-1000 standard
     * Prefix 114A is specific for HMS-800-2T
     */
    HMS_600_2T("HMS-600-2T", 0x1144, 2, 1, 300, 600, false),
    HMS_700_2T("HMS-700-2T", 0x1144, 2, 1, 350, 700, false),
    
    // HMS-800 has multiple prefixes (1144, 114A, 1410). 
    // Using 0x114A as generic identifier for the popular 2T model.
    HMS_800_2T("HMS-800-2T", 0x114A, 2, 1, 400, 800, false),

    HMS_900_2T("HMS-900-2T", 0x1144, 2, 1, 450, 900, false),
    HMS_1000_2T("HMS-1000-2T", 0x1144, 2, 1, 500, 1000, false),

    /*
     * --- HMS-4T Series (1-Phase, 4-Inputs) ---
     * Prefix 1164 covers standard HMS-1600 to 2000
     */
    HMS_1600_4T("HMS-1600-4T", 0x1164, 4, 2, 400, 1600, false),
    HMS_1800_4T("HMS-1800-4T", 0x1164, 4, 2, 450, 1800, false),
    
    // HMS-2000 exists as 1164 (Standard) and 1165-67 (High Current)
    HMS_2000_4T("HMS-2000-4T", 0x1164, 4, 2, 500, 2000, false),

    /*
     * --- HMT Series (3-Phase, Industrial) ---
     */
    // HMT-4T (4-Inputs)
    HMT_1800_4T("HMT-1800-4T", 0x1361, 4, 2, 450, 1800, true),
    HMT_2250_4T("HMT-2250-4T", 0x1361, 4, 2, 560, 2250, true), // ~560W per channel to reach 2250

    // HMT-6T (6-Inputs)
    HMT_2250_6T("HMT-2250-6T", 0x1382, 6, 3, 375, 2250, true);


    public enum DeviceGeneration {
        GEN2(10),
        GEN3(2);

        private final int minPercent;

        private DeviceGeneration(int minPercent) {
            this.minPercent = minPercent;
        }

        public int getMinPercent() {
            return this.minPercent;
        }
    }

    private final String displayName;
    private final int prefix; // First 4 hex digits of serial number
    private final int inputChannels;
    private final int mpptTotal;
    private final int maxPowerPerChannelW;
    private final int maxTotalPowerW;
    private final boolean threePhase;
    private final DeviceGeneration generation;

    private DeviceModel(String displayName, int prefix, int inputChannels, int mpptTotal, int maxPowerPerChannelW,
            int maxTotalPowerW, boolean threePhase) {
        this.displayName = displayName;
        this.prefix = prefix;
        this.inputChannels = inputChannels;

        //mrdomek Why: explicit per model; future devices may not follow a generic derivation rule.
        this.mpptTotal = mpptTotal;

        this.maxPowerPerChannelW = maxPowerPerChannelW;
        this.maxTotalPowerW = maxTotalPowerW;
        this.threePhase = threePhase;

        // All HMS/HMT are considered Gen3 (Sub-1G) in this context
        this.generation = DeviceGeneration.GEN3;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getPrefix() {
        return this.prefix;
    }

    public int getInputChannels() {
        return this.inputChannels;
    }

    public int getMpptTotal() {
        return this.mpptTotal;
    }

    public int getMaxPowerPerChannelW() {
        return this.maxPowerPerChannelW;
    }

    public int getMaxTotalPowerW() {
        return this.maxTotalPowerW;
    }
    
    public int getMaxApparentPowerVa() {
    	//mrdomek Why: No explicit apparent power is defined per model yet; keep backward compatibility.
    	return this.maxTotalPowerW;
    }


    public boolean isThreePhase() {
        return this.threePhase;
    }

    public DeviceGeneration getGeneration() {
        return this.generation;
    }

    /**
     * Lookup by first serial word prefix.
     *
     * @param serialWord0 the first serial word (uint16); may be negative if sourced from SignedWord
     * @return the matching DeviceModel or null if unknown
     */
    public static DeviceModel findBySerialWord0(int serialWord0) {
        final int prefix = serialWord0 & 0xFFFF; //mrdomek treat as uint16
        for (var m : DeviceModel.values()) {
            if (m.prefix == prefix) {
                return m;
            }
        }
        return null;
    }

    
}
