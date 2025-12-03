package io.openems.edge.deye.ess;

/**
 * Betriebsmodi des Deye-Wechselrichters aus OpenEMS-Sicht.
 *
 * Standby: WR im Standby (Register 80 = 0).
 * RemoteControlled: WR läuft, wird von OpenEMS gesteuert.
 * ZeroGridStandalone: WR läuft mit eigener Null-Export-Logik.
 * Island: Inselbetrieb / USV.
 *
 * Aktuell wird nur zwischen STANDBY und "nicht STANDBY" unterschieden.
 */
public enum DeyeOperationMode {

    STANDBY,
    REMOTE_CONTROLLED,
    ZERO_GRID_STANDALONE,
    ISLAND;

}
