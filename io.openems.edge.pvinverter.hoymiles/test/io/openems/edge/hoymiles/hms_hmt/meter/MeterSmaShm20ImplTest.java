package io.openems.edge.hoymiles.hms_hmt.meter;

import static io.openems.common.types.MeterType.GRID;

import org.junit.Test;

import io.openems.common.test.DummyConfigurationAdmin;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.hoymiles.hms_hmt.meter.MeterSmaShm20Impl;
import io.openems.edge.common.test.ComponentTest;

public class MeterSmaShm20ImplTest {

	@Test
	public void test() throws Exception {
		new ComponentTest(new MeterSmaShm20Impl()) //
				.addReference("cm", new DummyConfigurationAdmin()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("meter0") //
						.setModbusId("modbus0") //
						.setType(GRID) //
						.build()) //
				.next(new TestCase()) //
				.deactivate();
	}
}