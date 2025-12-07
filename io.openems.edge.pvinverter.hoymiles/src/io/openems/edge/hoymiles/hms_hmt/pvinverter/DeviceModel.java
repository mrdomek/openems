package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Supported Hoymiles device models.
 *
 * This is used for:
 * - number of DC inputs
 * - max power per input
 * - max total AC output power
 * - single-/three-phase behaviour
 * - device generation (Gen2/Gen3) => defines allowed active power range [%]
 */
public enum DeviceModel {

    /*
     * Examples – extend as needed.
     */

    HMS_1600_4T("HMS-1600-4T", //
            4,              // input channels
            400,            // max power per channel [W]
            1600,           // max total power [W]
            false,          // single-phase
            DeviceGeneration.GEN3 // 2–100 % see ModbusDoku
    ),

    HMT_1800_4T("HMT-1800-4T", //
            4,              // input channels
            500,            // max power per channel [W]
            1800,           // max total power [W]
            true,           // three-phase
            DeviceGeneration.GEN3 // 2–100 % see ModbusDoku
    );

    private final String displayName;
    private final int inputChannels;
    private final int maxPowerPerChannelW;
    private final int maxTotalPowerW;
    private final boolean threePhase;
    private final DeviceGeneration generation;

    private DeviceModel(String displayName, int inputChannels, int maxPowerPerChannelW,
            int maxTotalPowerW, boolean threePhase, DeviceGeneration generation) {
        this.displayName = displayName;
        this.inputChannels = inputChannels;
        this.maxPowerPerChannelW = maxPowerPerChannelW;
        this.maxTotalPowerW = maxTotalPowerW;
        this.threePhase = threePhase;
        this.generation = generation;
    }

    public String getDisplayName() {
        return this.displayName;
    }

    public int getInputChannels() {
        return this.inputChannels;
    }

    public int getMaxPowerPerChannelW() {
        return this.maxPowerPerChannelW;
    }

    public int getMaxTotalPowerW() {
        return this.maxTotalPowerW;
    }

    public boolean isThreePhase() {
        return this.threePhase;
    }

    /**
     * Device generation according to Hoymiles documentation.
     *
     * Gen2 : 10–100 %
     * Gen3 :  2–100 %
     */
    public DeviceGeneration getGeneration() {
        return this.generation;
    }

    @Override
    public String toString() {
        return this.displayName;
    }

    /**
     * Working range of active power control for a device generation.
     *
     * Gen2: 10–100 %
     * Gen3:  2–100 %
     */
    public static enum DeviceGeneration {
        GEN2(10),
        GEN3(2);

        private final int minPercent;

        private DeviceGeneration(int minPercent) {
            this.minPercent = minPercent;
        }

        /**
         * Minimum allowed active power percentage for this generation.
         */
        public int getMinPercent() {
            return this.minPercent;
        }
    }
}
