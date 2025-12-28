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

interface PvDetailRowValues {
    voltageMv: number | null;
    currentMa: number | null;
    powerW: number | null;
}

/**
 * Simple Flat-Widget for a Hoymiles micro-inverter.
 *
 * - componentId: OpenEMS Component-ID (e.g. "pvInverter0")
 * - uses SelMi*-Channels:
 *   - SelMiPv1UtilizationPercent, SelMiPv1PowerW, ...
 *   - SelMiActivePowerW
 *   - SelMiLimitActivePowerPercent
 *   - SelMiOperationMode
 *   - SelMiTemperatureC
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

    //mrdomek Configuration for the PV-Details table (Voltage/Current/Power per input).
    public pvDetails: PvDetailRowConfig[] = [
        { label: "PV1", voltageMvChannel: "SelMiPv1VoltageMv", currentMaChannel: "SelMiPv1CurrentMa", powerWChannel: "SelMiPv1PowerW" },
        { label: "PV2", voltageMvChannel: "SelMiPv2VoltageMv", currentMaChannel: "SelMiPv2CurrentMa", powerWChannel: "SelMiPv2PowerW" },
        { label: "PV3", voltageMvChannel: "SelMiPv3VoltageMv", currentMaChannel: "SelMiPv3CurrentMa", powerWChannel: "SelMiPv3PowerW" },
        { label: "PV4", voltageMvChannel: "SelMiPv4VoltageMv", currentMaChannel: "SelMiPv4CurrentMa", powerWChannel: "SelMiPv4PowerW" },
    ];

    //mrdomek Modal state + content
    public isDetailsModalOpen: boolean = false;
    public alarmSummaryText: string | null = null;
    public alarmInfoText: string | null = null;
    public alarmIgnoredText: string | null = null;

    //mrdomek Cache for the PV-Details table values (aligned with this.pvDetails index).
    public pvDetailValues: PvDetailRowValues[] = [];

    private readonly serialChannel: string = "SelMiSerial";
    private readonly acPowerChannel: string = "SelMiActivePowerW";
    private readonly temperatureChannel: string = "SelMiTemperatureC";
    private readonly activePowerLimitChannel: string = "ActivePowerLimit";

    //mrdomek Additional details
    private readonly limitActivePowerPercentChannel: string = "SelMiLimitActivePowerPercent";
    private readonly operationModeChannel: string = "SelMiOperationMode";

    private readonly alarmSummaryChannel: string = "SelMiAlarmSummary";
    private readonly alarmSummaryInfoChannel: string = "SelMiAlarmSummaryInfo";
    private readonly alarmSummaryIgnoredChannel: string = "SelMiAlarmSummaryIgnored";

    //mrdomek Cache for live DC power values (W), aligned with this.inputs index.
    private powerValuesW: Array<number | null> = [];

    //mrdomek Explicit subscriptions for values that are used in sums/tables and must be available synchronously.
    private subscribedAddresses: ChannelAddress[] = [];

    private injector: Injector = inject(Injector);
    private subscription: EffectRef | null = null;

    constructor(
        private dataService: DataService,
        private service: Service,
    ) { }

    public ngOnInit(): void {
        //mrdomek Initialize caches deterministically (prevents undefined/template flicker).
        this.powerValuesW = this.inputs.map(() => null);
        this.pvDetailValues = this.pvDetails.map(() => ({ voltageMv: null, currentMa: null, powerW: null }));

        this.service.getCurrentEdge().then(edge => {
            //mrdomek Build one subscription list for all values that we aggregate locally.
            const addresses: ChannelAddress[] = [];

            //mrdomek Power channels used for MPPT sums and DC total.
            for (const input of this.inputs) {
                addresses.push(new ChannelAddress(this.componentId, input.powerChannel));
            }

            //mrdomek Alarm channels shown in the details modal.
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryChannel));
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryInfoChannel));
            addresses.push(new ChannelAddress(this.componentId, this.alarmSummaryIgnoredChannel));

            //mrdomek PV-Details table channels.
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

    public getAcPowerAddress(): string {
        return `${this.componentId}/${this.acPowerChannel}`;
    }

    public getTemperatureAddress(): string {
        return `${this.componentId}/${this.temperatureChannel}`;
    }

    public getActivePowerLimitAddress(): string {
        return `${this.componentId}/${this.activePowerLimitChannel}`;
    }

    public getLimitActivePowerPercentAddress(): string {
        return `${this.componentId}/${this.limitActivePowerPercentChannel}`;
    }

    public getOperationModeAddress(): string {
        return `${this.componentId}/${this.operationModeChannel}`;
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

    //mrdomek Converter used by oe-flat-widget-line; must return a display string including the unit.
    public toWattString(value: any): string {
        const n = (typeof value === "number")
            ? value
            : (typeof value === "string" ? Number(value) : Number.NaN);

        if (Number.isFinite(n)) {
            return `${Math.round(n)} W`;
        }
        return "-";
    }

    //mrdomek Converter for percentage values (expects either number or numeric string).
    public toPercentString(value: any): string {
        const n = (typeof value === "number")
            ? value
            : (typeof value === "string" ? Number(value) : Number.NaN);

        if (Number.isFinite(n)) {
            return `${Math.round(n)} %`;
        }
        return "-";
    }

    //mrdomek Converter for temperature in °C.
    public toCelsiusString(value: any): string {
        const n = (typeof value === "number")
            ? value
            : (typeof value === "string" ? Number(value) : Number.NaN);

        if (Number.isFinite(n)) {
            //mrdomek Keep it compact: integer if possible, else one decimal.
            const isInt = Math.abs(n - Math.round(n)) < 1e-9;
            return `${isInt ? Math.round(n) : n.toFixed(1)} °C`;
        }
        return "-";
    }

    //mrdomek Converter for enum-like values that might come as number or string.
    public toTextString(value: any): string {
        if (value === null || value === undefined) {
            return "-";
        }
        const s = String(value).trim();
        return s.length > 0 ? s : "-";
    }

    //mrdomek PV-Details formatting helpers (channels are *_mV and *_mA; we show V and A).
    public toVoltStringFromMv(value: any): string {
        const n = this.toNumberOrNull(value);
        if (n == null) {
            return "-";
        }
        return `${(n / 1000).toFixed(1)} V`;
    }

    public toAmpStringFromMa(value: any): string {
        const n = this.toNumberOrNull(value);
        if (n == null) {
            return "-";
        }
        return `${(n / 1000).toFixed(2)} A`;
    }

    public toWattStringFromW(value: any): string {
        const n = this.toNumberOrNull(value);
        if (n == null) {
            return "-";
        }
        return `${Math.round(n)} W`;
    }

    private onCurrentData(currentData: CurrentData): void {
        if (!currentData?.allComponents) {
            return;
        }

        //mrdomek Cache PV power (for sums).
        for (let i = 0; i < this.inputs.length; i++) {
            const addr = new ChannelAddress(this.componentId, this.inputs[i].powerChannel).toString();
            this.powerValuesW[i] = this.toNumberOrNull(currentData.allComponents[addr]);
        }

        //mrdomek Cache alarm strings (details modal).
        this.alarmSummaryText = this.toTextStringOrNull(
            currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryChannel).toString()],
        );
        this.alarmInfoText = this.toTextStringOrNull(
            currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryInfoChannel).toString()],
        );
        this.alarmIgnoredText = this.toTextStringOrNull(
            currentData.allComponents[new ChannelAddress(this.componentId, this.alarmSummaryIgnoredChannel).toString()],
        );

        //mrdomek Cache PV-Details table values.
        for (let i = 0; i < this.pvDetails.length; i++) {
            const row = this.pvDetails[i];

            const voltageAddr = new ChannelAddress(this.componentId, row.voltageMvChannel).toString();
            const currentAddr = new ChannelAddress(this.componentId, row.currentMaChannel).toString();
            const powerAddr = new ChannelAddress(this.componentId, row.powerWChannel).toString();

            this.pvDetailValues[i] = {
                voltageMv: this.toNumberOrNull(currentData.allComponents[voltageAddr]),
                currentMa: this.toNumberOrNull(currentData.allComponents[currentAddr]),
                powerW: this.toNumberOrNull(currentData.allComponents[powerAddr]),
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

    private toTextStringOrNull(value: any): string | null {
        if (value === null || value === undefined) {
            return null;
        }
        const s = String(value).trim();
        return s.length > 0 ? s : null;
    }
}
