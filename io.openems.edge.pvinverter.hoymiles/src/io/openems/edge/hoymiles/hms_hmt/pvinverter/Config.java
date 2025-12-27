package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.hoymiles.hms_hmt.pvinverter.PvInverterHoymilesHMSHMT.Phase;

@ObjectClassDefinition(
        name = "PV-Inverter Hoymiles HMS/HMT",
        description = "Direct ModbusTCP integration of Hoymiles HMS/HMT microinverters via DTU-Pro/Pro-S."
)
public @interface Config {

    @AttributeDefinition(
            name = "Component-ID",
            description = "Unique ID of this Component")
    String id() default "pvInverter0";

    @AttributeDefinition(
            name = "Alias",
            description = "Human readable name of this inverter")
    String alias() default "Hoymiles HMS/HMT";

    @AttributeDefinition(
            name = "Enabled",
            description = "If disabled, this Component is ignored.")
    boolean enabled() default true;

    @AttributeDefinition(
            name = "Read-only mode",
            description = "If true, no control registers are written to the DTU/inverter.")
    boolean readOnly() default true;

    @AttributeDefinition(
            name = "Modbus-Bridge-ID",
            description = "ID of the Modbus bridge that connects to the DTU-Pro/Pro-S.")
    String modbus_id();

    @AttributeDefinition(
            name = "Modbus Unit-ID",
            description = "Modbus Unit-ID of the DTU / RS485 gateway.")
    int modbusUnitId() default 1;

    @AttributeDefinition(
            name = "Device model",
            description = "Select the supported Hoymiles device model. "
                    + "This defines single-/three-phase behavior and supported PV inputs.")
    DeviceModel deviceModel() default DeviceModel.HMS_1600_4T;

    @AttributeDefinition(
            name = "Microinverter number",
            description = "Hoymiles microinverter number (1–99) as configured in the DTU. "
                    + "MI1 = 1, MI2 = 2, ..., MI99 = 99.")
    int microinverterNumber() default 1;

    @AttributeDefinition(
            name = "AC phase (for single-phase HMS)",
            description = "AC phase where the inverter is connected. "
                    + "For HMT (3-phase) this is ignored.")
    Phase phase() default Phase.L1;

    @AttributeDefinition(
            name = "PV1 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 1 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv1ModulePeakPowerW() default 0;

    @AttributeDefinition(
            name = "PV2 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 2 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv2ModulePeakPowerW() default 0;

    @AttributeDefinition(
            name = "PV3 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 3 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv3ModulePeakPowerW() default 0;

    @AttributeDefinition(
            name = "PV4 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 4 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv4ModulePeakPowerW() default 0;

    @AttributeDefinition(
            name = "PV5 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 5 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv5ModulePeakPowerW() default 0;

    @AttributeDefinition(
            name = "PV6 module peak power [W]",
            description = "Nominal DC peak power of the PV module on input 6 in W (STC). "
                    + "Used to calculate relative loading in %. Set to 0 if unused.")
    int pv6ModulePeakPowerW() default 0;
    
    @AttributeDefinition(
            name = "Default power limit [%]",
            description = "Percent power limit that is applied if no Controller provides ACTIVE_POWER_LIMIT. "
                    + "100 = no limit, 0 = OFF. This value is written to the DTU as percent.")
    int defaultPowerPercent() default 100;

    @AttributeDefinition(
            name = "Use as production meter",
            description = "If enabled, the inverter power is counted as production in OpenEMS sums. "
                    + "If disabled, it is only informational.")
    boolean useAsProductionMeter() default true;

	@AttributeDefinition(name = "Debug mode", description = "Enables Debug mode")
	boolean debugMode() default false;	
	
	@AttributeDefinition(name = "Extended Debug mode", description = "Enables extended Debug mode")
	boolean extendedDebugMode() default false;		
   
    @AttributeDefinition(
            name = "Modbus target filter",
            description = "OSGi target filter for the Modbus bridge service.")
    String Modbus_target() default "(enabled=true)";

    String webconsole_configurationFactory_nameHint() default "PV-Inverter Hoymiles HMS/HMT [{id}]";
}
