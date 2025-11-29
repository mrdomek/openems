package io.openems.edge.batteryinverter.deye.sg05lp3;

// Imports für Standard-Java und OSGi
import java.util.Optional;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Imports für OpenEMS Common
import io.openems.common.exceptions.OpenemsException;

// Imports für OpenEMS Edge Bridge Modbus
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
// FC16WriteRegistersTask entfernt, da nicht mehr geschrieben wird
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;

// Imports für OpenEMS Edge Common
// Channel und WriteChannel entfernt, da nicht mehr direkt geschrieben wird
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;

// Imports für OpenEMS Edge ESS
// ManagedSymmetricEss entfernt
import io.openems.edge.ess.api.SymmetricEss;
// EssPower und Power API Imports entfernt


@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "deye.sg05lp3.reader", // Name geändert zur Unterscheidung
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
// ManagedSymmetricEss aus implements entfernt
public class DeyeSG05LP3Impl extends AbstractOpenemsModbusComponent implements DeyeSG05LP3, SymmetricEss, ModbusComponent, OpenemsComponent {

	// Logger für diese Klasse definieren
	private final Logger log = LoggerFactory.getLogger(DeyeSG05LP3Impl.class);

	@Reference
	private ConfigurationAdmin cm;

	// EssPower Referenz entfernt

	@Reference(policy = ReferencePolicy.STATIC, policyOption = ReferencePolicyOption.GREEDY, cardinality = ReferenceCardinality.MANDATORY)
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	// config Feld entfernt, da es nicht verwendet wurde.

	public DeyeSG05LP3Impl() {
		super(//
				OpenemsComponent.ChannelId.values(), //Base OpenEMS Component channels
				ModbusComponent.ChannelId.values(), //Modbus Component channels
				DeyeSG05LP3.ChannelId.values(), //Specific DeyeSunHybrid channels
				SymmetricEss.ChannelId.values() //Symmetric ESS channels (enthält ACTIVE_POWER, REACTIVE_POWER, SOC etc.)
				// ManagedSymmetricEss.ChannelId.values() entfernt
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		// config Feld entfernt, super.activate verwendet config direkt
		if(super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId(), this.cm, "Modbus",
				config.modbus_id())) {
			return;
		}
		// Keine Power-Instanziierung hier
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		try {
			return new ModbusProtocol(this, // Reference to this component

				// FC3: Read Holding Registers tasks

				// Read active Power (Register 636)
				// TODO: Verify unit (W?) and scaling for register 636
				new FC3ReadRegistersTask(636, Priority.HIGH,
						m(SymmetricEss.ChannelId.ACTIVE_POWER, new SignedWordElement(636))),

				// Read State of Charge (Register 588)
				// Unit: %
				new FC3ReadRegistersTask(588, Priority.HIGH,
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(588)))

				// --- Hier weitere Lese-Tasks hinzufügen, wenn benötigt ---
				/* Beispiel: Spannung lesen (Adresse und Skalierung prüfen!)
				,new FC3ReadRegistersTask(215, Priority.LOW,
						m(SymmetricEss.ChannelId.VOLTAGE, new SignedWordElement(215), ElementToChannelConverter.SCALE_FACTOR_MINUS_1))
				*/
				/* Beispiel: Blindleistung lesen (Adresse prüfen!)
				,new FC3ReadRegistersTask(XXX, Priority.HIGH,
						m(SymmetricEss.ChannelId.REACTIVE_POWER, new SignedWordElement(XXX)))
				*/

				// FC16 Write Tasks entfernt

			);
		} catch (/* OpenemsException e */ Exception e) {
            // Log error using the class-specific logger
            this.logError(this.log, "Fehler beim Erstellen des Modbus Protokolls für Deye SG05LP3: " + e.getMessage());
            // Throw RuntimeException to indicate initialization failure
            throw new RuntimeException("Initialisierung des Modbus Protokolls fehlgeschlagen!", e);
        }
	}

	@Override
	public String debugLog() {
		// Get values using standard interface methods and handle potential nulls safely
		Integer soc = this.getSoc().orElse(null); // From SymmetricEss interface, use orElse
		Integer activePower = this.getActivePower().orElse(null); // From SymmetricEss interface, use orElse
		// Optional: Lese weitere Werte, wenn die Tasks in defineModbusProtocol hinzugefügt wurden
		// Integer reactivePower = this.getReactivePower().orElse(null);

		String socStr = (soc != null) ? soc + "%" : "N/A";
		String pStr = (activePower != null) ? activePower + "W" : "N/A";
		// String qStr = (reactivePower != null) ? reactivePower + "var" : "N/A";

		// Logge die gelesenen Werte
		String logMessage = "SoC:" + socStr + " P:" + pStr; // + " Q:" + qStr;
		this.logInfo(this.log, logMessage); // Logge die Nachricht

		return logMessage; // Gib die gleiche Nachricht für das Debug-Log zurück
	}

	// getPower() entfernt
	// applyPower() entfernt
	// getPowerPrecision() entfernt

}
