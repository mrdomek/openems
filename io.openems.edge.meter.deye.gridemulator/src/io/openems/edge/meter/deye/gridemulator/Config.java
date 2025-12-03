package io.openems.edge.meter.deye.gridemulator;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Configuration for the Deye Gridmeter Emulator.
 */
@ObjectClassDefinition(//
		name = "Deye Gridmeter Emulator (SDM630_V2)", //
		description = "Emulates an Eastron SDM630_V2 for a Deye inverter by forwarding values from an existing ElectricityMeter.")
public @interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "deyeGrid0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Source Meter-ID", description = "Component-ID of the ElectricityMeter that provides the grid values (e.g. meter0).")
	String meter_id() default "meter0";

	@AttributeDefinition(name = "TCP Port", description = "TCP port on which the SDM630 emulator listens. This is the port to which the Deye (or an RS485/TCP-Gateway) connects.")
	int port() default 502;

	@AttributeDefinition(name = "Unit-ID", description = "Modbus Unit-ID (slave address) that the Deye will use.")
	int unitId() default 1;

	@AttributeDefinition(
			name = "Write interval [ms]",
			description = "Minimum interval in milliseconds between updates of the Modbus input registers. "
					+ "Note: This does NOT make data fresher than the global OpenEMS scheduler cycle – "
					+ "it only limits how often the emulator writes the latest values into its Modbus register image."
	)
	long writeIntervalMs() default 1000;

	String webconsole_configurationFactory_nameHint() default "Deye Gridmeter Emulator [{id}] -> Meter [{meter_id}]";
}
