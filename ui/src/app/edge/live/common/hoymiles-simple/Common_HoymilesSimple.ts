import { CommonModule } from "@angular/common";
import { Component, Input } from "@angular/core";
import { IonicModule } from "@ionic/angular";
import { SharedModule } from "../../../../shared/shared.module";

interface DcInputConfig {
    label: string;
    utilizationChannel: string;
    powerChannel: string;
}

/**
 * Einfaches Flat-Widget für einen Hoymiles-Mikrowechselrichter.
 *
 * - componentId: OpenEMS-Component-ID (z.B. "pvInverter0")
 * - nutzt SelMi*-Channels:
 *   - SelMiPv1UtilizationPercent, SelMiPv1PowerW, ...
 *   - SelMiSerial
 *   - SelMiActivePowerW
 *   - SelMiAlarmSummary, SelMiAlarmSummaryInfo, SelMiAlarmSummaryIgnored
 */
@Component({
    standalone: true,
    selector: "Common_HoymilesSimple",
    imports: [
        CommonModule,
        IonicModule,
        SharedModule,
    ],
    templateUrl: "./Common_HoymilesSimple.html",
    styleUrls: ["./Common_HoymilesSimple.scss"],
})
export class Common_HoymilesSimpleComponent {

    @Input()
    public componentId: string = "pvInverter0";

    @Input()
    public title: string = "Hoymiles DC-Inputs";

    public inputs: DcInputConfig[] = [
        {
            label: "PV1",
            utilizationChannel: "SelMiPv1UtilizationPercent",
            powerChannel: "SelMiPv1PowerW",
        },
        {
            label: "PV2",
            utilizationChannel: "SelMiPv2UtilizationPercent",
            powerChannel: "SelMiPv2PowerW",
        },
        {
            label: "PV3",
            utilizationChannel: "SelMiPv3UtilizationPercent",
            powerChannel: "SelMiPv3PowerW",
        },
        {
            label: "PV4",
            utilizationChannel: "SelMiPv4UtilizationPercent",
            powerChannel: "SelMiPv4PowerW",
        },
        // Bei Bedarf: PV5/PV6 ergänzen
        // {
        //     label: "PV5",
        //     utilizationChannel: "SelMiPv5UtilizationPercent",
        //     powerChannel: "SelMiPv5PowerW",
        // },
        // {
        //     label: "PV6",
        //     utilizationChannel: "SelMiPv6UtilizationPercent",
        //     powerChannel: "SelMiPv6PowerW",
        // },
    ];

    private readonly serialChannel: string = "SelMiSerial";
    private readonly acPowerChannel: string = "SelMiActivePowerW";

    private readonly alarmSummaryChannel: string = "SelMiAlarmSummary";
    private readonly alarmSummaryInfoChannel: string = "SelMiAlarmSummaryInfo";
    private readonly alarmSummaryIgnoredChannel: string = "SelMiAlarmSummaryIgnored";

    public getUtilizationAddress(input: DcInputConfig): string {
        return `${this.componentId}/${input.utilizationChannel}`;
    }

    public getPowerAddress(input: DcInputConfig): string {
        return `${this.componentId}/${input.powerChannel}`;
    }

    public getSerialAddress(): string {
        return `${this.componentId}/${this.serialChannel}`;
    }

    public getAcPowerAddress(): string {
        return `${this.componentId}/${this.acPowerChannel}`;
    }

    public getAlarmSummaryAddress(): string {
        return `${this.componentId}/${this.alarmSummaryChannel}`;
    }

    public getAlarmSummaryInfoAddress(): string {
        return `${this.componentId}/${this.alarmSummaryInfoChannel}`;
    }

    public getAlarmSummaryIgnoredAddress(): string {
        return `${this.componentId}/${this.alarmSummaryIgnoredChannel}`;
    }

    public presentModal(): void {
        alert(`Hoymiles-Details (${this.componentId}): hier kommt später ein Detail-Modal.`);
    }
}
