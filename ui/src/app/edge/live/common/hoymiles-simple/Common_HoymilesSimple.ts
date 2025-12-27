import { CommonModule } from "@angular/common";
import { Component } from "@angular/core";
import { IonicModule } from "@ionic/angular";
import { SharedModule } from "../../../../shared/shared.module";

interface DcInputConfig {
    label: string;
    utilizationChannel: string;
}

/**
 * Einfaches Common-Widget für genau einen Hoymiles-Wechselrichter.
 *
 * Darstellung:
 * - Kachel mit Titel
 * - Für jeden DC-Eingang:
 *   - eine Zeile mit Name + Prozentwert
 *   - ein Prozent-Balken (oe-flat-widget-percentagebar)
 *
 * Datenquelle:
 * - channelAddress = <baseComponentId>/<ChannelName>
 *   z.B. "pvinverter0/MI1_PV1_UTILIZATION_PERCENT"
 */
@Component({
    standalone: true,
    selector: "Common_HoymilesSimple",
    imports: [
        CommonModule,
        IonicModule,
        SharedModule, // bringt oe-flat-widget & Co. mit
    ],
    templateUrl: "./Common_HoymilesSimple.html",
    styleUrls: ["./Common_HoymilesSimple.scss"],
})
export class Common_HoymilesSimpleComponent {

    /**
     * Component-ID deines Hoymiles-Wechselrichters in der EdgeConfig.
     * HIER deine reale ID eintragen, z.B. "pvinverter0".
     */
    public baseComponentId: string = "pvInverter0";

    /**
     * DC-Eingänge, die angezeigt werden sollen.
     * Channel-Namen ggf. an deine Edge-Implementierung anpassen.
     */
    public inputs: DcInputConfig[] = [
        { label: "PV1", utilizationChannel: "SelMiPv1UtilizationPercent" },
        { label: "PV2", utilizationChannel: "SelMiPv2UtilizationPercent" },
        { label: "PV3", utilizationChannel: "SelMiPv3UtilizationPercent" },
        { label: "PV4", utilizationChannel: "SelMiPv4UtilizationPercent" },
        // bei Bedarf:
        // { label: "PV5", utilizationChannel: "MI1_PV5_UTILIZATION_PERCENT" },
        // { label: "PV6", utilizationChannel: "MI1_PV6_UTILIZATION_PERCENT" },
    ];

    /**
     * Hilfsfunktion für das Template:
     * baut aus baseComponentId und Channel-Namen einen gültigen channelAddress-String.
     */
    public getUtilizationAddress(input: DcInputConfig): string {
        return `${this.baseComponentId}/${input.utilizationChannel}`;
    }

    /**
     * Platzhalter für Detail-Ansicht.
     * Nächster Schritt: richtiges Modal-Widget nach OpenEMS-Pattern.
     */
    public presentModal(): void {
        alert("Hoymiles-Details: hier bauen wir im nächsten Schritt ein Modal.");
    }
}
