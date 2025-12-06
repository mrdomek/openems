package io.openems.edge.hoymiles.hms_hmt.pvinverter;

/**
 * Supported Hoymiles device models.
 *
 * Contains basic meta information:
 * - number of PV input channels
 * - max power per channel [W]
 * - max total output power [W]
 * - single- or three-phase device
 */
public enum DeviceModel {

    HMS_1600_4T( //
            4,    // input channels
            400,  // max power per channel [W] (adjust if needed)
            1600, // max total power [W] (adjust if needed)
            false // single-phase device
    ),

    HMT_1600_4T( //
            4,    // input channels
            450,  // max power per channel [W] (adjust if needed)
            1600, // max total power [W] (adjust if needed)
            true  // three-phase device
    ),

    
    HMT_1800_4T( //
            4,    // input channels
            450,  // max power per channel [W] (adjust if needed)
            1800, // max total power [W] (adjust if needed)
            true  // three-phase device
    ),
	
    HMT_2000_4T( //
            4,    // input channels
            450,  // max power per channel [W] (adjust if needed)
            2000, // max total power [W] (adjust if needed)
            true  // three-phase device
    );


    private final int inputChannels;
    private final int maxPowerPerChannelW;
    private final int maxTotalPowerW;
    private final boolean threePhase;

    private DeviceModel(int inputChannels, int maxPowerPerChannelW, int maxTotalPowerW, boolean threePhase) {
        this.inputChannels = inputChannels;
        this.maxPowerPerChannelW = maxPowerPerChannelW;
        this.maxTotalPowerW = maxTotalPowerW;
        this.threePhase = threePhase;
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
}
