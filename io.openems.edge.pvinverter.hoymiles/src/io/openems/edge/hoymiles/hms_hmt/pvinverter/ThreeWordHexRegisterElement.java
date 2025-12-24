package io.openems.edge.hoymiles.hms_hmt.pvinverter;

import java.nio.ByteBuffer;

import com.ghgande.j2mod.modbus.procimg.Register;

import io.openems.common.types.OpenemsType;
import io.openems.edge.bridge.modbus.api.element.ModbusRegisterElement;

/**
 * ModbusRegisterElement with length=3:
 * Reads 3 holding-registers (FC03) and converts them to a 12-char HEX string (%04X%04X%04X).
 */
public class ThreeWordHexRegisterElement extends ModbusRegisterElement<ThreeWordHexRegisterElement, String> {

	public ThreeWordHexRegisterElement(int startAddress) {
		super(OpenemsType.STRING, startAddress, 3);
	}

	@Override
	protected ThreeWordHexRegisterElement self() {
		return this;
	}

	@Override
	protected String registersToValue(Register[] registers) {
		final int r1 = registers[0].getValue() & 0xFFFF;
		final int r2 = registers[1].getValue() & 0xFFFF;
		final int r3 = registers[2].getValue() & 0xFFFF;
		return String.format("%04X%04X%04X", r1, r2, r3);
	}

	@Override
	protected String byteBufferToValue(ByteBuffer buff) {
		//mrdomek Why: we intentionally decode via registersToValue(); buffer decoding would require a defined charset/encoding.
		throw new UnsupportedOperationException("Not supported; decode via registersToValue()");
	}

	@Override
	protected void valueToByteBuffer(ByteBuffer buff, String value) {
		//mrdomek Why: read-only element; writing serials is not needed.
		throw new UnsupportedOperationException("Not supported; read-only");
	}

	@Override
	protected Register[] valueToRaw(String value) {
		//mrdomek Why: element is read-only; still implement abstract method to satisfy AbstractModbusElement.
		return new Register[0];
		// Alternative if you ever want write support:
		// return this.valueToRaw(value, WordOrder.MSWLSW);
	}
}
