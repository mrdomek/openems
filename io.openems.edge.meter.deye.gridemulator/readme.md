# OpenEMS – Deye Gridmeter Emulator

A Modbus‑TCP gridmeter emulator for Deye SUN‑xxK‑SG hybrid inverters.

This module allows a Deye inverter to read grid values from OpenEMS as if a physical SDM630‑compatible meter were connected.

Typical use cases:

* Zero‑export / export limiting
* AC‑coupled battery storage
* Use an existing OpenEMS meter (e.g. EM24 / EM300) as shared grid reference

---

## Deye communication requirements

Deye uses **RS485 / Modbus RTU** for gridmeter communication. It does **not** support Modbus TCP.

Therefore, a **Modbus RTU ↔ Modbus TCP gateway** is required between Deye and OpenEMS.

Communication chain:

```
Deye (Modbus RTU master)
        ↓ via RS485
RS485 ↔ TCP Gateway (TCP Client mode)
        ↓ via TCP
OpenEMS (Modbus TCP server – this emulator)
```

The gateway must:

* accept RTU polls from Deye
* convert them into TCP Modbus requests
* forward them to OpenEMS as **TCP client**
* return responses back to Deye via RTU

Compatible hardware: any RS485↔TCP converter that provides:

* Modbus RTU
* **TCP Client mode** (mandatory)
* transparent frame forwarding

Tested device: *Waveshare "RS485 TO ETH (B)"*. Other devices with similar feature set should work.

---

## Features

* Emulates an SDM630 compatible Modbus input register map
* Works with any OpenEMS `ElectricityMeter`
* Uses standard OpenEMS channels (active power L1/L2/L3, total import/export energy)
* Float32 (ABCD) encoding matching Deye expectations
* Uses `io.openems.j2mod` – no legacy `ModbusCoupler`
* No dependency on the physical meter model

---

## Timing behaviour

Two time domains exist:

### OpenEMS system cycle

OpenEMS updates all component channels in a global cycle (typically about 1 second). The emulator can only expose values as fresh as the cycle provides.

`minUpdateIntervalMs` may be configured lower but cannot bypass the OpenEMS cycle limit.

### Deye RTU polling

Wireshark traces show that Deye polls its gridmeter at roughly **150 ms intervals**. The gateway forwards these polls immediately to OpenEMS.

The emulator typically responds within a few milliseconds, so the **OpenEMS cycle** is the effective limit for data freshness.

Result:

* Deye receives grid values that are at most one OpenEMS cycle old.
* This is equivalent to other EMS‑driven gridmeter integrations.

---

## Module placement

Suggested folder:

```
io.openems.edge.meter.deye.gridemulator
```


Build‑path entries should include:

* io.openems.common
* io.openems.edge.common
* io.openems.edge.meter.api
* io.openems.j2mod

---

## OpenEMS configuration example

```
id: gridemu0
enabled: true
alias: Deye Gridmeter Emulator
port: 502
unitId: 1
meter_id: meter0
minUpdateIntervalMs: 1000
```

Meaning:

* `port`: TCP port the emulator listens on
* `unitId`: Modbus unit ID expected by Deye (often 1)
* `meter_id`: OpenEMS meter whose readings will be forwarded
* `minUpdateIntervalMs`: local throttling (cannot beat system cycle)

---

## Gateway configuration (generic)

The RS485↔TCP device must be configured as follows:

### TCP

* **Mode:** TCP Client (required)
* **Destination IP:** OpenEMS host
* **Destination Port:** emulator TCP port (e.g. 502)

OpenEMS acts as the TCP server.

### RS485

Match Deye’s serial settings:

* Baudrate: 9600
* Data bits: 8
* Parity: None
* Stop bits: 1
* Flow control: None

### RTU frame detection

Working values:

* Max frame length: ~256 bytes
* Max interval: ~5 ms

Exact names differ per gateway vendor.

---

## Waveshare example (VirCom)

For "RS485 TO ETH (B)":

* Workmode: TCP Client
* Dest. IP: OpenEMS
* Dest. Port: emulator TCP port
* Serial: 9600 / 8N1 / no flow control
* Packet rules:

  * Max length ~256
  * Max interval ~5 ms

These values ensure correct translation between Deye RTU polls and OpenEMS TCP responses.

---

## Data source in OpenEMS

The emulator reads the following channels from the configured `ElectricityMeter`:

* `ACTIVE_POWER_L1`
* `ACTIVE_POWER_L2`
* `ACTIVE_POWER_L3`
* `ACTIVE_POWER`
* `ACTIVE_CONSUMPTION_ENERGY`
* `ACTIVE_PRODUCTION_ENERGY`

Values are mapped into SDM630‑compatible registers and encoded as Float32.

Works with any meter supported by OpenEMS (e.g. EM24, EM300, SDM, etc.).

---

## Author

mrdomek
