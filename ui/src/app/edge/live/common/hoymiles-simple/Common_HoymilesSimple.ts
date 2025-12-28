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

interface PvDetailRowConfig {
    label: string;
    voltageMvChannel: string;
    currentMaChannel: string;
    powerWChannel: string;
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

    //mrdomek Modal state is kept inside the widget to avoid external dependencies.
    public isDetailsModalOpen: boolean = false;

    //mrdomek Alarm texts are cached as display strings (with optional line breaks).
    public alarmSummaryText: string | null = null;
    public alarmInfoText: string | null = null;
    public alarmIgnoredText: string | null = null;

    //mrdomek PV detail table rows (PV1..PV6). We show "-" if channels are not available.
    public pvDetails: PvDetailRowConfig[] = [
        { label: "PV1", voltageMvChannel: "SelMiPv1VoltageMv", currentMaChannel: "SelMiPv1CurrentMa", powerWChannel: "SelMiPv1PowerW" },
        { label: "PV2", voltageMvChannel: "SelMiPv2VoltageMv", currentMaChannel: "SelMiPv2CurrentMa", powerWChannel: "SelMiPv2PowerW" },
        { label: "PV3", voltageMvChannel: "SelMiPv3VoltageMv", currentMaChannel: "SelMiPv3CurrentMa", powerWChannel: "SelMiPv3PowerW" },
        { label: "PV4", voltageMvChannel: "SelMiPv4VoltageMv", currentMaChannel: "SelMiPv4CurrentMa", powerWChannel: "SelMiPv4PowerW" },
        { label: "PV5", voltageMvChannel: "SelMiPv5VoltageMv", currentMaChannel: "SelMiPv5CurrentMa", powerWChannel: "SelMiPv5PowerW" },
        { label: "PV6", voltageMvChannel: "SelMiPv6VoltageMv", currentMaChannel: "SelMiPv6CurrentMa", powerWChannel: "SelMiPv6PowerW" },
    ];

    //mrdomek Live cache for PV detail values, aligned with this.pvDetails index.
    public pvDetailValues: Array<{ voltageMv: number | null; currentMa: number | null; powerW: number | null }> = [];

    //mrdomek Live cache for DC power values (W), aligned with this.inputs index.
    private powerValuesW: Array<number | null> = [];

    private readonly serialChannel: string = "SelMiSerial";
    private readonly temperatureChannel: string = "SelMiTemperatureC";
    private readonly activePowerLimitChannel: string = "ActivePowerLimit";
    private readonly acPowerChannel: string = "SelMiActivePowerW";

    private readonly alarmSummaryChannel: string = "SelMiAlarmSummary";
    private readonly alarmSummaryInfoChannel: string = "SelMiAlarmSummaryInfo";
    private readonly alarmSummaryIgnoredChannel: string = "SelMiAlarmSummaryIgnored";

    //mrdomek One combined subscription list keeps unsubscribe deterministic.
    private subscribedAddresses: ChannelAddress[] = [];

    private injector: Injector = inject(Injector);
    private subscription: EffectRef | null = null;

    constructor(
        private dataService: DataService,
        private service: Service,
    ) { }

    public ngOnInit(): void {
        //mrdomek Initialize caches deterministically.
        this.powerValuesW = this.inputs.map(() => null);
        this.pvDetailValues = this.pvDetails.map(() => ({ voltageMv: null, currentMa: null, powerW: null }));

        this.service.getCurrentEdge().then(edge => {
            //mrdomek Build subscription list: all channels needed for sums + modal tables.
            const addresses: ChannelAddress[] = [];

            // Power channels (for MPPT sums + DC total)
            for (const input of this.inputs) {
                addresses.push(new ChannelAddress(this.componentId, input.powerChannel));
            }

            // Modal header channels
            addresses.push(new ChannelAddress(this.componentId, this.serialChannel));
            addresses.push(new ChannelAddress(this.componentId, this.temperatureChannel));
            addresses.push(new ChannelAddress(this.componentId, this.activePowerLimitChannel));

            // Modal alarm channels
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryChannel));
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryInfoChannel));
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryIgnoredChannel));

            // PV detail table channels
            for (const row of this.pvDetails) {
                addresses.push(new ChannelAddress(this.componentId, row.voltageMvChannel));
                addresses.push(new ChannelAddress(this.componentId, row.currentMaChannel));
                addresses.push(new ChannelAddress(this.componentId, row.powerWChannel));
            }

            this.subscribedAddresses = addresses;

            this.dataService.getValues(this.subscribedAddresses, edge, this.componentId);

            this.subscription = effect(() => {
                const currentData = this.dataService.currentValue();
                this.onCurrentData(currentData);
            }, { injector: this.injector });
        });
    }

    public ngOnDestroy(): void {
        if (this.subscribedAddresses.length > 0) {
            this.dataService.unsubscribeFromChannels(this.subscribedAddresses);
        }
        this.subscription?.destroy();
    }

    public presentModal(): void {
        this.isDetailsModalOpen = true;
    }

    public closeDetails(): void {
        this.isDetailsModalOpen = false;
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

    public getTemperatureAddress(): string {
        return `${this.componentId}/${this.temperatureChannel}`;
    }

    public getActivePowerLimitAddress(): string {
        return `${this.componentId}/${this.activePowerLimitChannel}`;
    }

    public getAcPowerAddress(): string {
        return `${this.componentId}/${this.acPowerChannel}`;
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

    //mrdomek Converter for °C channels.
    public toCelsiusString(value: any): string {
        const n = (typeof value === "number")
            ? value
            : (typeof value === "string" ? Number(value) : Number.NaN);

        if (Number.isFinite(n)) {
            return `${Math.round(n)} °C`;
        }
        return "-";
    }

    //mrdomek Format alarm strings to show one entry per line if ';' is used as separator.
    public formatAlarmText(value: any): string | null {
        if (value == null) {
            return null;
        }
        const s = String(value).trim();
        if (s.length === 0) {
            return null;
        }
        return s.replace(/;\s*/g, ";\n");
    }

    private onCurrentData(currentData: CurrentData): void {
        if (!currentData?.allComponents) {
            return;
        }

        //mrdomek Cache PV power values used for sums.
        for (let i = 0; i < this.inputs.length; i++) {
            const address = new ChannelAddress(this.componentId, this.inputs[i].powerChannel);
            const raw = currentData.allComponents[address.toString()];
            this.powerValuesW[i] = this.toNumberOrNull(raw);
        }

        //mrdomek Cache alarm texts for modal table display.
        this.alarmSummaryText = this.formatAlarmText(currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryChannel).toString()]);
        this.alarmInfoText = this.formatAlarmText(currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryInfoChannel).toString()]);
        this.alarmIgnoredText = this.formatAlarmText(currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryIgnoredChannel).toString()]);

        //mrdomek Cache PV details (mV, mA, W).
        for (let i = 0; i < this.pvDetails.length; i++) {
            const row = this.pvDetails[i];

            const vRaw = currentData.allComponents[new ChannelAddress(this.componentId, row.voltageMvChannel).toString()];
            const cRaw = currentData.allComponents[new ChannelAddress(this.componentId, row.currentMaChannel).toString()];
            const pRaw = currentData.allComponents[new ChannelAddress(this.componentId, row.powerWChannel).toString()];

            this.pvDetailValues[i] = {
                voltageMv: this.toNumberOrNull(vRaw),
                currentMa: this.toNumberOrNull(cRaw),
                powerW: this.toNumberOrNull(pRaw),
            };
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
