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
        { label: "PV1", utilizationChannel: "SelMiPv1UtilizationPercent", powerChannel: "SelMiPv1PowerW" },
        { label: "PV2", utilizationChannel: "SelMiPv2UtilizationPercent", powerChannel: "SelMiPv2PowerW" },
        { label: "PV3", utilizationChannel: "SelMiPv3UtilizationPercent", powerChannel: "SelMiPv3PowerW" },
        { label: "PV4", utilizationChannel: "SelMiPv4UtilizationPercent", powerChannel: "SelMiPv4PowerW" },
    ];

    private readonly serialChannel: string = "SelMiSerial";
    private readonly acPowerChannel: string = "SelMiActivePowerW";

    private readonly alarmSummaryChannel: string = "SelMiAlarmSummary";
    private readonly alarmSummaryInfoChannel: string = "SelMiAlarmSummaryInfo";
    private readonly alarmSummaryIgnoredChannel: string = "SelMiAlarmSummaryIgnored";

    //mrdomek Cache for live DC power values (W), aligned with this.inputs index.
    private powerValuesW: Array<number | null> = [];

    //mrdomek Explicit subscriptions for PV power channels used in sums.
    private subscribedPowerAddresses: ChannelAddress[] = [];

    private injector: Injector = inject(Injector);
    private subscription: EffectRef | null = null;

    constructor(
        private dataService: DataService,
        private service: Service,
    ) { }

    public ngOnInit(): void {
        //mrdomek Initialize caches deterministically.
        this.powerValuesW = this.inputs.map(() => null);

        this.service.getCurrentEdge().then(edge => {
            this.subscribedPowerAddresses = this.inputs.map(input =>
                new ChannelAddress(this.componentId, input.powerChannel),
            );

            this.dataService.getValues(this.subscribedPowerAddresses, edge, this.componentId);

            this.subscription = effect(() => {
                const currentData = this.dataService.currentValue();
                this.onCurrentData(currentData);
            }, { injector: this.injector });
        });
    }

    public ngOnDestroy(): void {
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

    public getPairPowerSumW(evenIndex: number): number | null {
        const a = this.powerValuesW[evenIndex] ?? null;
        const b = this.powerValuesW[evenIndex + 1] ?? null;

        if (a == null && b == null) {
            return null;
        }
        return (a ?? 0) + (b ?? 0);
    }

    //mrdomek Sum of all DC inputs currently shown in this.inputs.
    public getDcTotalPowerW(): number | null {
        let any = false;
        let sum = 0;

        for (const v of this.powerValuesW) {
            if (v != null) {
                any = true;
                sum += v;
            }
        }

        return any ? sum : null;
    }

    public getMpptSumLabel(evenIndex: number): string {
        const pairIndex = Math.floor(evenIndex / 2);
        const letter = String.fromCharCode(65 + pairIndex);
        return `MPPT ${letter} Sum`;
    }

    //mrdomek Converter used by oe-flat-widget-line; it must return a display string including the unit.
    public toWattString(value: any): string {
        const n = (typeof value === "number")
            ? value
            : (typeof value === "string" ? Number(value) : Number.NaN);

        if (Number.isFinite(n)) {
            return `${Math.round(n)} W`;
        }
        return "-";
    }

    public presentModal(): void {
        alert(`Hoymiles-Details (${this.componentId}): hier kommt später ein Detail-Modal.`);
    }

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
