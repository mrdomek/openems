import { CommonModule } from "@angular/common";
import { Component, effect, EffectRef, inject, Injector, Input, OnDestroy, OnInit } from "@angular/core";
import { IonicModule } from "@ionic/angular";
import { DataService } from "src/app/shared/components/shared/dataservice";
import { ChannelAddress, CurrentData, Service } from "src/app/shared/shared";
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
 *
 * //mrdomek Pair sums (PV1+PV2, PV3+PV4, ...) are computed UI-side from CurrentData
 * //mrdomek to avoid introducing new Edge channels.
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
export class Common_HoymilesSimpleComponent implements OnInit, OnDestroy {

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
    ];

    private readonly serialChannel: string = "SelMiSerial";
    private readonly acPowerChannel: string = "SelMiActivePowerW";

    private readonly alarmSummaryChannel: string = "SelMiAlarmSummary";
    private readonly alarmSummaryInfoChannel: string = "SelMiAlarmSummaryInfo";
    private readonly alarmSummaryIgnoredChannel: string = "SelMiAlarmSummaryIgnored";

    //mrdomek Cache for live DC power values (W), aligned with this.inputs index.
    private powerValuesW: Array<number | null> = [];

    //mrdomek Explicit subscriptions for PV power channels used in pair sums.
    private subscribedPowerAddresses: ChannelAddress[] = [];

    private injector: Injector = inject(Injector);
    private subscription: EffectRef | null = null;

    constructor(
        private dataService: DataService,
        private service: Service,
    ) { }

    public ngOnInit(): void {
        //mrdomek Initialize caches deterministically to avoid undefined states in templates.
        this.powerValuesW = this.inputs.map(() => null);

        //mrdomek Subscribe to the required channels via the same DataService mechanism as AbstractFlatWidget.
        this.service.getCurrentEdge().then(edge => {
            //mrdomek Build and subscribe only to the PV power channels required for pair sums.
            this.subscribedPowerAddresses = this.inputs.map(input =>
                new ChannelAddress(this.componentId, input.powerChannel),
            );

            this.dataService.getValues(this.subscribedPowerAddresses, edge, this.componentId);

            //mrdomek React to every CurrentData update and refresh our local power cache.
            this.subscription = effect(() => {
                const currentData = this.dataService.currentValue();
                this.onCurrentData(currentData);
            }, { injector: this.injector });
        });
    }

    public ngOnDestroy(): void {
        //mrdomek Ensure we unsubscribe the channels we explicitly subscribed for pair sums.
        if (this.subscribedPowerAddresses.length > 0) {
            this.dataService.unsubscribeFromChannels(this.subscribedPowerAddresses);
        }
        this.subscription?.destroy();
    }

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

    //mrdomek Returns the live sum of PV(i)+PV(i+1) based on cached CurrentData values.
    public getPairPowerSumW(evenIndex: number): number | null {
        const a = this.powerValuesW[evenIndex] ?? null;
        const b = this.powerValuesW[evenIndex + 1] ?? null;

        if (a == null && b == null) {
            return null;
        }
        return (a ?? 0) + (b ?? 0);
    }

    //mrdomek Converter for oe-flat-widget-line [converter]. Must not depend on "this",
    //mrdomek because the line component calls converter(value) without binding a context.
    public toWattString(value: any): string {
        if (typeof value === "number" && Number.isFinite(value)) {
            return Math.round(value).toString();
        }
        if (typeof value === "string") {
            const n = Number(value);
            return Number.isFinite(n) ? Math.round(n).toString() : "-";
        }
        return "-";
    }

    public presentModal(): void {
        alert(`Hoymiles-Details (${this.componentId}): hier kommt später ein Detail-Modal.`);
    }

    //mrdomek Update cached PV power values from CurrentData snapshots.
    private onCurrentData(currentData: CurrentData): void {
        if (!currentData?.allComponents) {
            return;
        }

        for (let i = 0; i < this.inputs.length; i++) {
            const address = new ChannelAddress(this.componentId, this.inputs[i].powerChannel);
            const raw = currentData.allComponents[address.toString()];
            this.powerValuesW[i] = this.toNumberOrNull(raw);
        }
    }

    //mrdomek Internal numeric conversion for caching and arithmetic.
    private toNumberOrNull(value: any): number | null {
        if (typeof value === "number" && Number.isFinite(value)) {
            return value;
        }
        if (typeof value === "string") {
            const n = Number(value);
            return Number.isFinite(n) ? n : null;
        }
        return null;
    }
}
