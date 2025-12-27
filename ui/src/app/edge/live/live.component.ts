import { Component, effect, ElementRef, OnDestroy, ViewChild } from "@angular/core";
import { ActivatedRoute, Router } from "@angular/router";
import { RefresherCustomEvent } from "@ionic/angular";
import { Subject } from "rxjs";
import { NavigationService } from "src/app/shared/components/navigation/service/navigation.service";
import { DataService } from "src/app/shared/components/shared/dataservice";
import { UserService } from "src/app/shared/service/user.service";
import { Edge, EdgeConfig, EdgePermission, Service, Utils, Websocket } from "src/app/shared/shared";
import { Widgets } from "src/app/shared/type/widgets";
import { DateTimeUtils } from "src/app/shared/utils/datetime/datetime-utils";

@Component({
    selector: "live",
    templateUrl: "./live.component.html",
    standalone: false,
})
export class LiveComponent implements OnDestroy {

    @ViewChild("modal", { read: ElementRef }) public modal!: ElementRef;

    protected edge: Edge | null = null;
    protected config: EdgeConfig | null = null;
    protected widgets: Widgets | null = null;
    protected isModbusTcpWidgetAllowed: boolean = false;
    protected showRefreshDragDown: boolean = false;
    protected showNewFooter: boolean = false;

    //mrdomek Hard fact from UI config: Hoymiles HMS/HMT components arrive with this exact factoryId.
    //mrdomek We match EXACTLY against this string to ensure the widget only appears for this driver.
    protected readonly HOYMILES_FACTORY_ID: string = "PV-Inverter.Hoymiles.HMS-HMT";

    private stopOnDestroy: Subject<void> = new Subject<void>();
    private interval: ReturnType<typeof setInterval> | undefined;

    constructor(
        private route: ActivatedRoute,
        public service: Service,
        protected utils: Utils,
        protected websocket: Websocket,
        private dataService: DataService,
        private router: Router,
        protected navigationService: NavigationService,
        private userService: UserService,
    ) {

        effect(() => {
            const edge = this.service.currentEdge();
            this.edge = edge;
            this.isModbusTcpWidgetAllowed = EdgePermission.isModbusTcpApiWidgetAllowed(edge);

            this.service.getConfig().then(config => {
                this.config = config;
                this.widgets = navigationService.getWidgets(config.widgets, userService.currentUser(), edge);

                //mrdomek Optional debug helper: enable by adding "?debugHoymiles=1" to the URL.
                //mrdomek This prints the factoryId list so future mismatches are obvious immediately.
                if (this.isHoymilesDebugEnabled()) {
                    this.debugPrintFactoryIds(config);
                    // eslint-disable-next-line no-console
                    console.log("Hoymiles widget componentIds:", this.hoymilesComponents);
                }
            });

            this.checkIfRefreshNeeded();
        });
    }

    //mrdomek Returns all componentIds whose factoryId EXACTLY matches the Hoymiles driver factoryId.
    //mrdomek No fallback by componentId prefix is used (by request) to avoid false positives.
    public get hoymilesComponents(): string[] {
        const cfg: any = this.config;
        if (!cfg || !cfg.components) {
            return [];
        }

        const components = cfg.components as Record<string, { factoryId?: string }>;
        const target = this.HOYMILES_FACTORY_ID;

        return Object.keys(components).filter((componentId) => {
            const factoryId = components[componentId]?.factoryId;
            return typeof factoryId === "string" && factoryId === target;
        });
    }

    public ionViewWillEnter() {
        if (this.widgets?.list) {
            this.showNewFooter = this.widgets?.list
                .filter(item => item.name == "Evse.Controller.Single" || item.name == "Controller.IO.Heating.Room")
                ?.length > 0;
        }
    }

    ionViewWillLeave() {
        this.ngOnDestroy();
    }

    public ngOnDestroy() {
        clearInterval(this.interval);
        this.stopOnDestroy.next();
        this.stopOnDestroy.complete();
    }

    protected handleRefresh: (ev: RefresherCustomEvent) => void =
        (ev: RefresherCustomEvent) => this.dataService.refresh(ev);

    protected checkIfRefreshNeeded() {
        this.interval = setInterval(async () => {

            if (this.edge?.isOnline === false) {
                this.showRefreshDragDown = false;
                return;
            }

            const lastUpdate: Date | null = this.dataService.lastUpdated();
            if (lastUpdate == null) {
                this.showRefreshDragDown = true;
                return;
            }
            this.showRefreshDragDown = DateTimeUtils.isDifferenceInSecondsGreaterThan(20, new Date(), lastUpdate);
        }, 5000);
    }

    private isHoymilesDebugEnabled(): boolean {
        //mrdomek We intentionally read the query param from the current route snapshot to keep this minimal.
        return this.route.snapshot.queryParamMap.get("debugHoymiles") === "1";
    }

    private debugPrintFactoryIds(config: EdgeConfig): void {
        const cfg: any = config as any;
        const components: Record<string, { factoryId?: string }> = cfg?.components ?? {};

        const rows = Object.keys(components).map(id => ({
            componentId: id,
            factoryId: components[id]?.factoryId ?? "(undefined)",
        }));

        // eslint-disable-next-line no-console
        console.table(rows);
    }
}
