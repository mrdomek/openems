package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * OSGi configuration for PvInverterHoymilesHMSHMTImpl.
 */
@ObjectClassDefinition(
        name = "PV-Inverter Hoymiles HMS/HMT",
        description = "Direct Modbus-TCP integration of Hoymiles HMS/HMT micro inverters via DTU-Pro-S."
)
public @interface Config {

    @AttributeDefinition(
            name = "Component-ID",
            description = "Unique ID of this component."
    )
    String id() default "pvInverter0";

    @AttributeDefinition(
            name = "Alias",
            description = "Human readable name."
    )
    String alias() default "pvInverter0";

    @AttributeDefinition(
            name = "Enabled",
            description = "Enable this component"
    )
    boolean enabled() default true;

    @AttributeDefinition(
            name = "Read only",
            description = "If enabled, OpenEMS will not write any control registers (e.g. power limit)."
    )
    boolean readOnly() default true;

    @AttributeDefinition(
            name = "Modbus bridge ID",
            description = "ID of the Modbus bridge component that connects to the DTU."
    )
    String modbus_id() default "modbus0";

    @AttributeDefinition(
            name = "Modbus Unit-ID",
            description = "Unit-ID / Slave-ID on the DTU. Default 201."
    )
    int modbusUnitId() default 201;

    @AttributeDefinition(
            name = "Use as production meter",
            description = "If enabled, this inverter's power is treated as production in OpenEMS. "
                    + "If disabled, values are only shown but not used in energy balances."
    )
    boolean useAsProductionMeter() default true;
    
    @AttributeDefinition(
            name = "AC phase",
            description = "Phase where the Hoymiles inverter is connected (L1/L2/L3).")
    PvInverterHoymilesHMSHMT.Phase phase() default PvInverterHoymilesHMSHMT.Phase.L1;
}
