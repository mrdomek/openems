<!--
Deye Battery Inverter Helper Index – v0.1
Stand: 2025-12-04

Zweck:
- Hilfsfunktionen (Helper) und typische Muster aus bestehenden ESS-Implementierungen sammeln
- Referenz für die Implementierung eines Deye-Batteriewechselrichters in OpenEMS
-->

# Deye Battery Inverter – Helper & Pattern Index

## 0. Ziel und Scope

Dieses Dokument sammelt wiederverwendbare Bausteine aus bestehenden OpenEMS-Bundles, die für einen
Deye-Batteriewechselrichter relevant sind, insbesondere:

- `io.openems.edge.ess.generic`
- `io.openems.edge.ess.fenecon.commercial40`
- `io.openems.edge.fenecon.dess`

Schwerpunkt:

- Work-/System-States (z. B. `SET_WORK_STATE`, `SYSTEM_STATE`, `GRID_MODE`)
- Leistungs-Setpoints (`SET_ACTIVE_POWER`, `SET_REACTIVE_POWER`)
- Leistungsgrenzen (`AllowedChargePower`, `AllowedDischargePower`, `MaxApparentPower`)
- Modbus-Mapping und Event-Handling

---

## 1. Gesamtbild: Deye als Batteriewechselrichter in OpenEMS

Zielarchitektur:

- Deye wird als Batteriewechselrichter integriert, der:
  - Natures wie `SymmetricEss` / `ManagedSymmetricEss` (bzw. `SymmetricBatteryInverter`) implementiert.
  - über Modbus Register für:
    - aktuelle AC-Leistung, SoC, Spannungen/Ströme
    - Work-/Systemzustand (Run/Stop/Remote, Fehler)
    - Setpoints für aktive/reaktive Leistung
    - Leistungsgrenzen/Derating
  - von OpenEMS über `applyPower(int activePower, int reactivePower)` gesteuert wird.

Dieses Dokument zeigt, wie so etwas in vorhandenen ESS (v. a. Fenecon Commercial40) bereits gelöst ist
und welches Muster für Deye übernommen werden soll.

---

## 2. `io.openems.edge.ess.generic` – generische ESS-Logik

### 2.1 Zweck des Bundles

- Stellt generische ESS-Logik bereit:
  - ESS-State-Machine
  - Zusammenspiel von Batterie + (Battery-)Inverter
  - Behandlung von Start/Stop, Fehlerzuständen, Leistungsgrenzen
- Wird benutzt, um konkrete Geräte in ein standardisiertes ESS-Verhalten einzubetten.

### 2.2 Wichtige Muster

State-Machine-Dokumentation:

- In `io.openems.edge.ess.generic/doc/statemachine.*`
- Beschreibt:
  - Zustände (z. B. STARTING, RUNNING, STOPPED, ERROR)
  - Übergänge
  - Definition, wann ESS als „bereit“, „gestoppt“ etc. gilt

Konfiguration (`Config`):

- Typische Punkte:
  - `id()` – ESS-ID (`ess0`, `ess1`, …)
  - Referenzen auf:
    - Batterie-Komponente (`battery_id`)
    - (Battery-)Inverter-Komponente (`batteryInverter_id` / `ess_id`)

Relevanz für Deye:

- Langfristig: Deye-Batteriewechselrichter kann unter einem Generic-ESS „eingehängt“ werden.
- Deye muss daher die üblichen ESS-Interfaces sauber bedienen:
  - SoC, ActivePower
  - AllowedCharge/DischargePower, MaxApparentPower
  - Start/Stop-Mechanik, GridMode

---

## 3. `io.openems.edge.ess.fenecon.commercial40` – EssFeneconCommercial40Impl

Dies ist die wichtigste Blaupause für Deye, insbesondere was WorkState/SystemState und Setpoints angeht.

### 3.1 Rolle der `EssFeneconCommercial40Impl`

- Implementiert ein ESS für FENECON Commercial40.
- Implementierte Natures:
  - `SymmetricEss`
  - `ManagedSymmetricEss`
- Aufgaben:
  - Modbus-Lesen von Messwerten (SoC, Leistungen, Zustände)
  - Modbus-Schreiben von Setpoints (aktive/reaktive Leistung, WorkState)
  - Pflege der ESS-Leistungsgrenzen
  - Event-basierte Zusatzlogik (Derating, Energie-Berechnung, WorkState-Setzen)

---

### 3.2 Kern-Channels für Steuerung

#### 3.2.1 Setpoints

Im Interface `EssFeneconCommercial40` (vereinfacht dargestellt):

- `SET_ACTIVE_POWER`
  - Typ: `IntegerWriteChannel`
  - Zugriff: `WRITE_ONLY`
  - Einheit: Watt
  - Zweck: Sollwert für aktive Leistung (±W)

- `SET_REACTIVE_POWER`
  - Typ: `IntegerWriteChannel`
  - Zugriff: `WRITE_ONLY`
  - Einheit: Var
  - Zweck: Sollwert für Blindleistung (±Var)

Diese Channels werden in `EssFeneconCommercial40Impl.applyPower()` gesetzt und dann per Modbus rausgeschrieben.

#### 3.2.2 WorkState

- `SET_WORK_STATE`
  - Typ: `EnumWriteChannel`
  - Zugriff: `WRITE_ONLY`
  - Enum: `SetWorkState` (z. B. `START`, ggf. weitere States)
  - Zweck: Wechselrichter/ESS in einen definierten Betriebsmodus bringen (z. B. RUN).

#### 3.2.3 System-/Inverterzustände

Typische Channels in `EssFeneconCommercial40` / `EssFeneconCommercial40Impl`:

- `SYSTEM_STATE` (Rohzustand des Systems)
- `CONTROL_MODE`
- `BATTERY_MAINTENANCE_STATE`
- `INVERTER_STATE`
- `SymmetricEss.ChannelId.GRID_MODE` (auf `GridMode`-Enum gemappt)

Diese geben an:

- In welchem Modus das Gerät arbeitet
- Ob on-grid/off-grid
- interne Wartungs-/Fehlerzustände

---

### 3.3 `applyPower(int activePower, int reactivePower)`

Typischer Aufbau (vereinfacht):

- `config.readOnlyMode()` prüfen.
- `SET_ACTIVE_POWER`-Channel holen, `setNextWriteValue(activePower)`.
- `SET_REACTIVE_POWER`-Channel holen, `setNextWriteValue(reactivePower)`.

Muster:

- `config.readOnlyMode()` als globaler Schalter für „nur messen, nicht steuern“.
- Zugriff auf Write-Channels über `this.channel(...)`.
- Nur `setNextWriteValue(...)` – das tatsächliche Modbus-Schreiben macht das Modbus-Framework.

Für Deye:

- In `DeyeSunHybridImpl.applyPower()` exakt dieses Muster übernehmen:
  - `readOnlyMode` prüfen.
  - `SET_ACTIVE_POWER` und `SET_REACTIVE_POWER` Channels setzen.
  - keine direkten Modbus-Schreibaufrufe an dieser Stelle.

---

### 3.4 Modbus-Mapping: SystemState, GridMode, WorkState, Setpoints

In `defineModbusProtocol()` werden:

- Status-Register per FC3 gelesen.
- Setpoint-Register per FC16 geschrieben.

#### 3.4.1 SystemState, ControlMode, InverterState und GridMode

Typischer Ausschnitt (vereinfacht):

- `SYSTEM_STATE`, `CONTROL_MODE`, `BATTERY_MAINTENANCE_STATE`, `INVERTER_STATE`
  - je als `UnsignedWordElement` auf eigene Channels gemappt.
- `SymmetricEss.ChannelId.GRID_MODE`
  - über `UnsignedWordElement` + `ElementToChannelConverter` auf `GridMode` (`OFF_GRID`, `ON_GRID`, `UNDEFINED`) gemappt.

Muster:

- Rohzustände als Plain-Integer-Channel lassen (für Debug/Fehleranalyse).
- Wichtigere Zustände (z. B. `GRID_MODE`) auf OpenEMS-Enums mappen.

Für Deye:

- Entsprechende Deye-Register für:
  - `SYSTEM_STATE`, `INVERTER_STATE`, `CONTROL_MODE` etc.
- Einen Deye-Registerwert auf `SymmetricEss.ChannelId.GRID_MODE` mappen (on-grid/off-grid).

#### 3.4.2 WorkState-Setpoint

Typische Definition:

- `FC16WriteRegistersTask(startAddress=0x0500, m(ChannelId.SET_WORK_STATE, new UnsignedWordElement(0x0500)))`

Muster:

- `SET_WORK_STATE` als `UnsignedWordElement`.
- FC16-Task für das Register, das den WorkMode steuert.

Für Deye:

- Deye-WorkMode-/Start-Stopp-Register hier ansetzen.
- `ChannelId.SET_WORK_STATE` + Enum `SetWorkState` definieren.

#### 3.4.3 Leistungs-Setpoints

Typische Definition:

- `FC16WriteRegistersTask(startAddress=0x0501, m(SET_ACTIVE_POWER, SignedWordElement(0x0501), SCALE_FACTOR_2), m(SET_REACTIVE_POWER, SignedWordElement(0x0502), SCALE_FACTOR_2))`

Muster:

- `SignedWordElement` für Werte mit Vorzeichen (Laden/Entladen, kapazitiv/induktiv).
- Scale-Faktor-Konverter (`SCALE_FACTOR_2` oder vergleichbar) für Register-Skalierung.

Für Deye:

- Entsprechende Deye-Register für Leistungs-Sollwerte hier eintragen.
- Vorzeichenkonvention beachten (z. B. positiv = Entladung, negativ = Ladung).

---

### 3.5 WorkState-Logik (`defineWorkState()` + EventHandler)

Die Klasse implementiert einen Event-Handler, um zyklisch den WorkState zu setzen:

- `handleEvent(Event event)` reagiert auf:
  - `TOPIC_CYCLE_AFTER_PROCESS_IMAGE`:
    - Derating (`applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError()`).
    - Energie-Berechnung (`calculateEnergy()`).
  - `TOPIC_CYCLE_BEFORE_CONTROLLERS`:
    - `defineWorkState()`.

`defineWorkState()` (Logik):

- Einmal pro Minute (über Zeitstempel `lastDefineWorkState`) wird:
  - der Enum-Write-Channel `SET_WORK_STATE` geholt.
  - `SetWorkState.START` geschrieben (`setNextWriteValue(SetWorkState.START)`).
  - Fehler bei Schreibproblemen geloggt.

Muster:

- `EdgeEventConstants.TOPIC_CYCLE_BEFORE_CONTROLLERS` als Trigger für WorkMode-„Keep-Alive“.
- Vermeidung von Dauerschreiben über Zeit-Guard.
- `EnumWriteChannel` und `setNextWriteValue(Enum)` als saubere API.

Für Deye:

- Gleiches Pattern anwenden, wenn Deye einen WorkMode/RemoteMode per Register braucht:
  - `defineWorkState()` analog implementieren.
  - EventHandling exakt wie hier.

---

### 3.6 Leistungsgrenzen und Derating

In `handleEvent()`:

- `applyPowerLimitOnPowerDecreaseCausedByOvertemperatureError()`:
  - Liest Overtemperature-/Fehler-States.
  - Setzt dynamisch strengere Leistungsgrenzen (z. B. AllowedCharge/DischargePower) bei Übertemperatur.
- `calculateEnergy()`:
  - Integriert Leistung über die Zeit mit `CalculateEnergyFromPower`.

Muster:

- Derating über zusätzliche Constraints auf AllowedCharge/DischargePower.
- Energie-Berechnung über `CalculateEnergyFromPower`.

Für Deye:

- Deye-spezifische Overtemperature-/Fehler-States auf Channels mappen.
- Optional ähnlich Commercial40: Derating-Funktion, die bei bestimmten Fehlern/Temperaturen Limits reduziert.

---

### 3.7 Debug-Logik

Die Methode `debugLog()` (schematisch) liefert z. B.:

- SoC
- aktuelle aktive Leistung
- AllowedCharge/DischargePower
- GridMode
- interne StateMachine-States (z. B. Surplus-Feed-In-State)

Muster:

- Kompaktes Debug-Log zur schnellen Beurteilung des ESS-Zustands.
- Gut geeignet, um Deye-Verhalten gegen Commercial40 vergleichen zu können.

---

## 4. `io.openems.edge.fenecon.dess` – zusätzliche Patterns

Kurzüberblick:

- Nutzt eine Hersteller-Excel (BYD DESS Modbus Protocol) im `doc/`-Ordner.
- Umfangreiches Modbus-Mapping mit:
  - DC- und AC-Energiezählern
  - vielen Zuständen, Fehlern, Betriebsmodi

Muster für Deye:

- Offizielle Deye-Modbus-Doku (Excel/PDF) ebenfalls unter:
  - `io.openems.edge.batteryinverter.deye/doc/...`
  ablegen.
- Registerblöcke im Code logisch gruppieren:
  - allgemeine Infos (Seriennummer, Firmware, …)
  - AC-Leistungen / Energien
  - Batterie-Infos (SoC, Spannung, Strom, Temperatur)
  - Work-/Systemstate, Fehler, Derating
  - Setpoints

---

## 5. Checkliste: Deye-Implementierung konform zu OpenEMS

### 5.1 Channels / Natures

- Implementierte Natures:
  - `SymmetricEss` / `ManagedSymmetricEss` (oder `SymmetricBatteryInverter`)
- Channels vorhanden:
  - `SymmetricEss.ChannelId.ACTIVE_POWER`
  - `SymmetricEss.ChannelId.SOC`
  - `ManagedSymmetricEss.ChannelId.ALLOWED_CHARGE_POWER` (negativ)
  - `ManagedSymmetricEss.ChannelId.ALLOWED_DISCHARGE_POWER` (positiv)
  - `ManagedSymmetricEss.ChannelId.MAX_APPARENT_POWER`
  - `SymmetricEss.ChannelId.GRID_MODE`

### 5.2 System-/WorkState

- Rohzustände:
  - `SYSTEM_STATE`, `INVERTER_STATE`, `CONTROL_MODE`, ggf. weitere Deye-States
- OpenEMS-States:
  - `GRID_MODE` (on-grid/off-grid, sauber gemappt)
- WorkState:
  - `SET_WORK_STATE` (`SetWorkState`-Enum)
  - `defineWorkState()` analog `EssFeneconCommercial40Impl`:
    - zyklisch aufgerufen (`TOPIC_CYCLE_BEFORE_CONTROLLERS`)
    - schreibt `SetWorkState.START` oder passenden Deye-State

### 5.3 Leistungs-Setpoints

- Write-Only-Channels:
  - `SET_ACTIVE_POWER` (W)
  - `SET_REACTIVE_POWER` (Var)
- `applyPower(int activePower, int reactivePower)`:
  - `readOnlyMode`-Guard
  - Setzen der Write-Channels
- Modbus-Mapping via `FC16WriteRegistersTask`:
  - `SignedWordElement` + passende Scale-Faktoren
  - korrekte Vorzeichenkonvention für Laden/Entladen

### 5.4 Leistungsgrenzen und Derating

- Statische Limits:
  - aus Konfiguration oder aus Deye-Registern
- Dynamische Limits:
  - Derating bei Overtemperature/Fehlern, optional nach Commercial40-Vorbild

### 5.5 Energie-Berechnung

- `CalculateEnergyFromPower` verwenden, wenn Deye keine geeigneten Energiewerte liefert.
- Separate Energy-Channels für Charge/Discharge.

---

## 6. Pflege dieses Dokuments

- Datei liegt in einem Feature-Branch, z. B.:
  - `doc/deye-batteryinverter-helper-index.md`
- Version im Kommentar-Header (`v0.1`, `v0.2`, …) anpassen.
- Beim Einbau neuer Muster (z. B. aus `DeyeSunHybridImpl`) dieses Dokument erweitern:
  - neue Abschnitte für Deye-spezifische Register-Maps
  - besondere Fehler-/Derating-Mechanismen
  - tatsächliche Codes von `SetWorkState` / `SystemState` dokumentieren.
