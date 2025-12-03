package io.openems.edge.meter.deye.gridemulator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;

import io.openems.common.exceptions.OpenemsException;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.meter.api.ElectricityMeter;

/**
 * Deye Gridmeter Emulator.
 *
 * Emuliert ein Eastron SDM630_V2 für den Deye-Wechselrichter, indem Werte
 * eines vorhandenen {@link ElectricityMeter} (z.B. Carlo Gavazzi EM24/EM300)
 * auf die entsprechenden Modbus-Register gespiegelt werden.
 *
 * Der Deye liest laut Log:
 *
 *  - FC4, ab 0x000C, Länge 6 -> 3x float: Power L1/L2/L3
 *  - FC4, ab 0x0048, Länge 4 -> 2x float: Total Import kWh / Total Export kWh
 */
@Designate(ocd = Config.class, factory = true)
@Component( //
		name = "Meter.Deye.GridmeterEmulator", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
public class DeyeGridmeterEmulatorImpl extends AbstractOpenemsComponent implements OpenemsComponent {

	private final Logger log = LoggerFactory.getLogger(DeyeGridmeterEmulatorImpl.class);

	/**
	 * Zugriff auf andere OpenEMS-Komponenten (hier: Quell-Meter).
	 */
	@Reference
	private ComponentManager componentManager;

	private Config config;

	/*
	 * j2mod: Prozessbild und TCP-Slave
	 */
	private SimpleProcessImage processImage;
	private ModbusSlave slave;

	/*
	 * Hintergrund-Thread für das regelmäßige Aktualisieren der Register.
	 */
	private ExecutorService executor;
	private volatile boolean running = false;

	/*
	 * Letzte Werte nur für debugLog()
	 */
	private volatile float lastPowerL1 = 0f;
	private volatile float lastPowerL2 = 0f;
	private volatile float lastPowerL3 = 0f;
	private volatile float lastImportKwh = 0f;
	private volatile float lastExportKwh = 0f;

	/*
	 * SDM630 V2 Registerlayout (0-basiert, wie im Modbus-PDU):
	 *
	 *  0x000C..0x0011 -> P_L1, P_L2, P_L3 (float32, je 2 Register)
	 *  0x0048..0x004B -> E_import, E_export (float32, je 2 Register)
	 */
	private static final int REG_POWER_L1 = 0x000C;
	private static final int REG_POWER_L2 = 0x000E;
	private static final int REG_POWER_L3 = 0x0010;

	private static final int REG_IMPORT_KWH = 0x0048;
	private static final int REG_EXPORT_KWH = 0x004A;

	public DeyeGridmeterEmulatorImpl() {
		// Nur OpenemsComponent-Kanäle, keine zusätzlichen Natures
		super(OpenemsComponent.ChannelId.values());
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;

		// Standard-OpenEMS-Aktivierung
		super.activate(context, config.id(), config.alias(), config.enabled());

		if (!config.enabled()) {
			this.log.info("DeyeGridmeterEmulator [{}] ist per Konfiguration deaktiviert.", config.id());
			return;
		}

		this.log.info(
				"Aktiviere DeyeGridmeterEmulator [{}], meter-id [{}], TCP-Port {}, unitId {}",
				config.id(), config.meter_id(), config.port(), config.unitId());

		this.setupModbusSlave();
		this.startUpdateLoop();
	}

	@Override
	@Deactivate
	protected void deactivate() {
		this.log.info("Deaktiviere DeyeGridmeterEmulator [{}]",
				this.config != null ? this.config.id() : "?");

		this.stopUpdateLoop();
		this.stopModbusSlave();

		super.deactivate();
	}

	// =========================================================================
	// j2mod-Setup
	// =========================================================================

	/**
	 * Erzeugt das SimpleProcessImage und startet den Modbus-TCP-Slave auf dem
	 * konfigurierten Port.
	 */
	private void setupModbusSlave() throws OpenemsException {
		try {
			// Prozessbild anlegen
			this.processImage = new SimpleProcessImage();

			// Benötigte Input-Register anlegen
			int highestRegister = Math.max(REG_EXPORT_KWH + 1, REG_POWER_L3 + 1);
			this.ensureInputRegisterSize(highestRegister);

			// Initial mit 0 befüllen
			writeFloatToInputRegisters(REG_POWER_L1, 0f);
			writeFloatToInputRegisters(REG_POWER_L2, 0f);
			writeFloatToInputRegisters(REG_POWER_L3, 0f);
			writeFloatToInputRegisters(REG_IMPORT_KWH, 0f);
			writeFloatToInputRegisters(REG_EXPORT_KWH, 0f);

			// TCP-Slave erzeugen und Prozessbild registrieren
			this.slave = ModbusSlaveFactory.createTCPSlave(this.config.port(), 3);
			this.slave.addProcessImage(this.config.unitId(), this.processImage);
			this.slave.open();

			this.log.info("Modbus-TCP-Slave für DeyeGridmeterEmulator gestartet: Port {}, UnitId {}",
					this.config.port(), this.config.unitId());

		} catch (ModbusException e) {
			throw new OpenemsException("Konnte Modbus-Slave nicht starten: " + e.getMessage(), e);
		}
	}

	private void stopModbusSlave() {
		if (this.slave != null) {
			try {
				this.log.info("Stoppe Modbus-TCP-Slave für DeyeGridmeterEmulator");
				this.slave.close();
			} catch (Exception e) {
				this.log.warn("Fehler beim Stoppen des Modbus-Slaves: {}", e.getMessage());
			}
			this.slave = null;
		}
	}

	// =========================================================================
	// Update-Loop
	// =========================================================================

	private void startUpdateLoop() {
		if (this.executor != null) {
			return;
		}
		this.executor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "DeyeGridmeterEmulator-Update");
			t.setDaemon(true);
			return t;
		});
		this.running = true;
		this.executor.submit(this::updateLoop);
	}

	private void stopUpdateLoop() {
		this.running = false;
		if (this.executor != null) {
			this.executor.shutdownNow();
			this.executor = null;
		}
	}

	/**
	 * Hintergrundschleife: liest periodisch Werte vom konfigurierten Meter und
	 * schreibt sie in die Modbus-Input-Register.
	 */
	private void updateLoop() {
		while (this.running) {
			try {
				this.updateRegistersFromMeter();
			} catch (Exception e) {
				this.log.warn("Fehler im DeyeGridmeterEmulator-Update-Loop: {}", e.getMessage());
			}

			try {
				Thread.sleep(this.config.writeIntervalMs());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	/**
	 * Liest aktuelle Werte vom konfigurierten ElectricityMeter und schreibt sie als
	 * float32 in die SDM630-Register.
	 *
	 * Mapping:
	 *  - REG_POWER_L1..L3   <- ACTIVE_POWER_L1..L3 [W]
	 *  - REG_IMPORT_KWH     <- ACTIVE_CONSUMPTION_ENERGY [kWh]
	 *  - REG_EXPORT_KWH     <- ACTIVE_PRODUCTION_ENERGY  [kWh]
	 */
	private void updateRegistersFromMeter() {
	    final ElectricityMeter meter;
	    try {
	        meter = this.componentManager.getComponent(this.config.meter_id());
	    } catch (Exception e) { // fängt auch OpenemsNamedException ab
	        this.log.debug("Quell-Meter [{}] noch nicht verfügbar: {}",
	                this.config.meter_id(), e.getMessage());
	        return;
	    }
	    if (meter == null) {
	        this.log.debug("Quell-Meter [{}] liefert null aus ComponentManager.",
	                this.config.meter_id());
	        return;
	    }

	    /*
	     * Channel-Werte lesen.
	     * value().asOptional() liefert Optional<?>, daher cast auf Number.
	     */
	    double pL1 = meter.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L1)
	            .value().asOptional()
	            .map(v -> ((Number) v).doubleValue())
	            .orElse(0d);

	    double pL2 = meter.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L2)
	            .value().asOptional()
	            .map(v -> ((Number) v).doubleValue())
	            .orElse(0d);

	    double pL3 = meter.channel(ElectricityMeter.ChannelId.ACTIVE_POWER_L3)
	            .value().asOptional()
	            .map(v -> ((Number) v).doubleValue())
	            .orElse(0d);

	    // Energie aus OpenEMS: EM300 liefert Wh -> für Deye in kWh umrechnen
	    double eImportWh = meter.channel(ElectricityMeter.ChannelId.ACTIVE_CONSUMPTION_ENERGY)
	            .value().asOptional()
	            .map(v -> ((Number) v).doubleValue())
	            .orElse(0d);

	    double eExportWh = meter.channel(ElectricityMeter.ChannelId.ACTIVE_PRODUCTION_ENERGY)
	            .value().asOptional()
	            .map(v -> ((Number) v).doubleValue())
	            .orElse(0d);

	    // Wh -> kWh
	    double eImportKwh = eImportWh / 1000d;
	    double eExportKwh = eExportWh / 1000d;

	    // Nach float32 casten – für SDM630/Deye mehr als ausreichend
	    float fPL1 = (float) pL1;
	    float fPL2 = (float) pL2;
	    float fPL3 = (float) pL3;
	    float fImport = (float) eImportKwh;
	    float fExport = (float) eExportKwh;

	    // Für debugLog() merken
	    this.lastPowerL1 = fPL1;
	    this.lastPowerL2 = fPL2;
	    this.lastPowerL3 = fPL3;
	    this.lastImportKwh = fImport;
	    this.lastExportKwh = fExport;

	    // In Input-Register schreiben
	    writeFloatToInputRegisters(REG_POWER_L1, fPL1);
	    writeFloatToInputRegisters(REG_POWER_L2, fPL2);
	    writeFloatToInputRegisters(REG_POWER_L3, fPL3);
	    writeFloatToInputRegisters(REG_IMPORT_KWH, fImport);
	    writeFloatToInputRegisters(REG_EXPORT_KWH, fExport);
	}


	// =========================================================================
	// Hilfsfunktionen: Register / Float-Konvertierung
	// =========================================================================

	/**
	 * Stellt sicher, dass mindestens {@code size} Input-Register existieren.
	 * (Index ist 0-basiert.)
	 */
	private void ensureInputRegisterSize(int size) {
		while (this.processImage.getInputRegisterCount() <= size) {
			this.processImage.addInputRegister(new SimpleInputRegister((short) 0));
		}
	}

	/**
	 * Schreibt einen float32-Wert in zwei aufeinanderfolgende Input-Register:
	 * High-Word nach {@code startRef}, Low-Word nach {@code startRef + 1}.
	 *
	 * Wortreihenfolge: Big-Endian, High-Word zuerst – typisches SDM630-Verhalten.
	 */
	private void writeFloatToInputRegisters(int startRef, float value) {
		byte[] bytes = ByteBuffer.allocate(4)
				.order(ByteOrder.BIG_ENDIAN)
				.putFloat(value)
				.array();

		int hiWord = ((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF);
		int loWord = ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);

		ensureInputRegisterSize(startRef + 1);

		this.processImage.setInputRegister(startRef, new SimpleInputRegister((short) hiWord));
		this.processImage.setInputRegister(startRef + 1, new SimpleInputRegister((short) loWord));
	}

	// =========================================================================
	// OpenEMS-Standard
	// =========================================================================

	@Override
	public String debugLog() {
		return "P_L1=" + this.lastPowerL1 + " W; " //
				+ "P_L2=" + this.lastPowerL2 + " W; " //
				+ "P_L3=" + this.lastPowerL3 + " W; " //
				+ "E_imp=" + this.lastImportKwh + " kWh; " //
				+ "E_exp=" + this.lastExportKwh + " kWh";
	}
}
