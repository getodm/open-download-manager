package org.odm.gtk4;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gtk.Application;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Adjustment;
import org.gnome.gtk.Button;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.LinkButton;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.PopoverMenuBar;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreeModel;
import org.javagi.base.Out;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSelection;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.TreeView;
import org.manager.url.DownloadUrlPolicy;

/**
 * Main window — 1:1 GTK4 port of main-window.glade. Same widget ids and
 * layout; behaviors wired for the core surface, with the rest (Tor toggle,
 * queue reorder, live search, DHT/BT tabs) deferred to Step 5 but their
 * widgets present in the .ui so the structure matches the original.
 *
 * All core events arrive through UiThread.marshal — the only safe seam.
 */
public class MainWindow {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainWindow.class);
    private static org.gnome.gtk.CssProvider statusBarCssProvider;
    /** Internal fetch batch; every record remains reachable through scrolling. */
    static final int HISTORY_PAGE_SIZE = 500;
    private static final List<String> DOWNLOAD_COLUMN_LABELS = List.of(
            "#", I18n.tr("Status"), I18n.tr("Name"), I18n.tr("Completed"), I18n.tr("Size"), I18n.tr("Progress"), I18n.tr("Elapsed"),
            I18n.tr("Left"), I18n.tr("Down Speed"), I18n.tr("Up Speed"), I18n.tr("Ratio"), I18n.tr("Retry"), I18n.tr("Start Date"), I18n.tr("End Date"), I18n.tr("Result"));
    private static final List<String> COMPLETION_ACTION_KEYS = List.of(
            "notify", "desktop-notify", "antivirus", "subtitles", "suspend", "shutdown", "custom");
    /** Long enough for GTK to paint and animate an immediately acknowledged NEWNYM. */
    private static final long NEW_IDENTITY_MIN_ACTIVITY_MILLIS = 1_000;
    private static final Download.Status[] ALWAYS_VISIBLE_STATUSES = {
        Download.Status.CREATED, Download.Status.QUEUED, Download.Status.PAUSED,
        Download.Status.STARTING, Download.Status.CONNECTING, Download.Status.DOWNLOADING,
        Download.Status.SEEDING
    };

    private record RefreshSnapshot(List<Download> downloads, int totalCount,
            int loadedHistoryCount,
            java.util.Map<Download.Status, Integer> statusCounts,
            String searchText,
            java.util.Map<DownloadListPresenter.FilterBucket, Integer> filterCounts) {
    }

    /** Applicability of selection-scoped context and Download-menu actions. */
    record DownloadSelectionCapabilities(boolean any, boolean single,
            boolean openFile, boolean openFolder, boolean pause, boolean resume,
            boolean start, boolean copyLinks, boolean changeDestination,
            boolean recheckData, boolean downloadSubtitles, boolean updateMirror,
            boolean openHttrackLog, boolean openHttrackErrorLog, boolean delete,
            boolean deleteWithFiles, boolean properties) {
    }

    enum DownloadActivation {
        OPEN_FILE,
        REVEAL_IN_FOLDER
    }

    enum QueueMove {
        UP,
        TOP,
        DOWN,
        BOTTOM
    }

    record QueueMovementCapabilities(boolean up, boolean top,
            boolean down, boolean bottom) {

        static final QueueMovementCapabilities NONE =
                new QueueMovementCapabilities(false, false, false, false);

        boolean allows(QueueMove move) {
            return switch (move) {
                case UP -> up;
                case TOP -> top;
                case DOWN -> down;
                case BOTTOM -> bottom;
            };
        }
    }
    private final ApplicationWindow window;
    private final ListStore statusStore;
    private final ListStore categoryStore;
    private final ListStore downloadsStore;
    private final TreeView statusTreeview;
    private final TreeView categoryTreeview;
    private final TreeView downloadsTreeview;
    private final ScrolledWindow downloadScrolledWindow;
    private final Adjustment downloadScrollAdjustment;
    private final TreeView filesTreeview;
    private final TreeView completionDetailsTreeview;
    private final GestureClick downloadContextClick;
    private final Label infoLabel;
    private final Label downSpeedLabel;
    private final Label upSpeedLabel;
    private final Label dhtStatusLabel;
    private final Spinner activitySpinner;
    private final SpinnerActivity activity;
    private final DownloadProgressGraph infoProgressGraph;
    private final Label addedOnValue;
    private final Label infoHashValue;
    private final Label errorValue;
    private final Button errorDetailsButton;
    private final LinkButton folderOpenButton;
    private final Label folderValue;
    private final Image engineIcon;
    private final Image torIcon;
    private final Label torIpLabel;
    private final Label engineValue;
    private final Label etaValue;
    private final Label downloadedValue;
    private final Label connectionsValue;
    private final Label seedsPeersValue;
    private final Button moveUpButton;
    private final Button moveTopButton;
    private final Button moveDownButton;
    private final Button moveBottomButton;
    private final Button torCheckButton;
    private final org.gnome.gtk.SearchEntry searchEntry;
    private final PopoverMenuBar menuBar;
    private final org.gnome.gtk.Widget leftPanelWidget;
    private final org.gnome.gtk.Widget infoPanelWidget;
    private final DownloadManager downloadManager;
    private final org.manager.download.OfflineModeController offlineModeController;

    private final DownloadListPresenter listPresenter;
    final DetailTabsPresenter detailTabsPresenter;
    final SourcesPresenter sourcesPresenter;
    private final java.util.concurrent.ExecutorService backgroundExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean refreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean refreshAgain =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Remembers that a coalesced refresh was requested by search/filter UI. */
    private final java.util.concurrent.atomic.AtomicBoolean refreshActivityRequested =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean torIdentityRequestInFlight =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** Number of newest history records requested from the repository. */
    private int historyFetchLimit = HISTORY_PAGE_SIZE;
    /** Number of primary history rows returned by the last applied snapshot. */
    private int loadedHistoryCount;
    private int knownDownloadCount;

    // Listeners registered with core services; kept as fields so the window
    // can detach them on final close instead of leaking refresh work forever
    private DownloadListener windowDownloadListener;
    private org.manager.download.action.AfterCompletionActionListener windowCompletionListener;
    private org.manager.clipboard.ClipboardServiceListener windowClipboardListener;
    /** Optional app-provided exit sequence (graceful shutdown UI). */
    private Runnable finalCloseDelegate;
    /** At most one File -> Exit confirmation may be open at a time. */
    private org.gnome.gtk.MessageDialog exitConfirmation;
    /** Guards the final teardown delegate against duplicate exit gestures. */
    private boolean finalExitStarted;
    private boolean backgroundWorkShutdown;
    /** App-owned StatusNotifier lifecycle; null in isolated window tests. */
    private java.util.function.Consumer<Boolean> trayPreferenceHandler;
    private Download selectedDownload;
    private List<Download> selectedDownloads = List.of();
    /**
     * Stable snapshot taken on the captured secondary-button press. GtkTreeView
     * may still collapse its selection later in the same pointer sequence, so
     * the deferred popup must restore this snapshot rather than re-hit-testing
     * the row after release.
     */
    private List<String> pendingContextSelectionIds = List.of();
    private boolean pendingContextPopup;
    /** Guard so menu actions register on the window only once. */
    private boolean menuActionsRegistered;
    private final java.util.Map<String, org.gnome.gio.SimpleAction> menuActions =
            new java.util.HashMap<>();
    private volatile boolean trayAvailable;
    private java.util.function.Consumer<java.util.Map<String, TrayMenu.ActionState>> trayStateListener;
    private boolean hasPausedDownloads;
    private final ListStore trackersStore;
    private final ListStore peersStore;
    private final TreeStore filesStore;
    private final ListStore completionDetailsStore;
    private final ListStore globalProgressStore;
    private final org.tor.TorService torService;
    private final TorServiceController torServiceController;
    private final org.manager.schedule.ScheduleManager scheduleManager;
    private final java.util.concurrent.atomic.AtomicLong torToggleEpoch =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean torDesiredRunning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final org.tor.TorCircuitMonitor torCircuitMonitor;
    private final org.tor.TorService.TorServiceListener windowTorListener;
    /** Check currently reporting progress in the status label; GTK-thread confined. */
    private CompletableFuture<org.tor.TorCircuitMonitor.Result> torStatusCheck;
    /** Current Tor start/stop message, protected from routine list refreshes. */
    private String torTransitionStatus;
    /** Download ids with at least one running completion action; GTK-thread confined. */
    private final java.util.Set<String> runningCompletionDownloads = new java.util.HashSet<>();
    private int completionPulseSourceId;
    /** Builder reference kept for window-state persistence from menu actions. */
    private final GtkBuilder uiBuilder;

    private final org.jackett.JackettService jackettService;

    public MainWindow(Application app, DownloadManager downloadManager, org.tor.TorService torService,
            org.manager.schedule.ScheduleManager scheduleManager) {
        this(app, downloadManager, torService, scheduleManager, null);
    }

    public MainWindow(Application app, DownloadManager downloadManager, org.tor.TorService torService,
            org.manager.schedule.ScheduleManager scheduleManager, org.jackett.JackettService jackettService) {
        this.jackettService = jackettService;
        this.downloadManager = downloadManager;
        this.torService = torService;
        this.torServiceController = new TorServiceController(downloadManager, torService);
        this.scheduleManager = scheduleManager;

        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        this.uiBuilder = builder;

        this.window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        this.statusStore = Widgets.require(builder, "status_store", ListStore.class);
        this.categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        this.downloadsStore = Widgets.require(builder, "download_store", ListStore.class);
        this.statusTreeview = Widgets.require(builder, "status_treeview", TreeView.class);
        this.categoryTreeview = Widgets.require(builder, "category_treeview", TreeView.class);
        this.downloadsTreeview = Widgets.require(builder, "download_treeview", TreeView.class);
        this.downloadScrolledWindow = Widgets.require(builder,
                "download_scrolled_window", ScrolledWindow.class);
        this.downloadScrollAdjustment = downloadScrolledWindow.getVadjustment();
        this.filesTreeview = Widgets.require(builder, "files_view", TreeView.class);
        this.completionDetailsTreeview = Widgets.require(builder,
                "completion_details_view", TreeView.class);
        this.infoLabel = Widgets.require(builder, "info_label", Label.class);
        this.downSpeedLabel = Widgets.require(builder, "down_speed_label", Label.class);
        this.upSpeedLabel = Widgets.require(builder, "up_speed_label", Label.class);
        this.dhtStatusLabel = Widgets.require(builder, "dht_status_label", Label.class);
        this.activitySpinner = Widgets.require(builder, "activity_spinner", Spinner.class);
        this.activity = new SpinnerActivity(activitySpinner);
        this.infoProgressGraph = new DownloadProgressGraph(builder);
        this.addedOnValue = Widgets.require(builder, "added_on_value", Label.class);
        this.trackersStore = Widgets.require(builder, "trackers_store", ListStore.class);
        this.peersStore = Widgets.require(builder, "peers_store", ListStore.class);
        this.filesStore = Widgets.require(builder, "files_store", TreeStore.class);
        this.completionDetailsStore = Widgets.require(builder, "completion_details_store", ListStore.class);
        this.globalProgressStore = Widgets.require(builder, "global_progress_store", ListStore.class);
        this.infoHashValue = Widgets.require(builder, "info_hash_v1_value", Label.class);
        this.errorValue = Widgets.require(builder, "info_error_value", Label.class);
        this.errorDetailsButton = Widgets.require(builder, "info_error_button", Button.class);
        this.folderOpenButton = Widgets.require(builder, "folder_open_button", LinkButton.class);
        this.folderValue = Widgets.require(builder, "folder_value", Label.class);
        this.engineIcon = Widgets.require(builder, "engine_icon", Image.class);
        this.torIcon = Widgets.require(builder, "tor_icon", Image.class);
        this.torIcon.setFromGicon(DownloadEnginePresentation.statusTorIcon());
        this.torIpLabel = Widgets.require(builder, "tor_ip_label", Label.class);
        this.engineValue = Widgets.require(builder, "engine_value", Label.class);
        this.etaValue = Widgets.require(builder, "eta_value", Label.class);
        this.downloadedValue = Widgets.require(builder, "downloaded_value", Label.class);
        this.connectionsValue = Widgets.require(builder, "connections_value", Label.class);
        this.seedsPeersValue = Widgets.require(builder, "seeds_peers_value", Label.class);

        this.listPresenter = new DownloadListPresenter(statusStore, categoryStore,
                downloadsStore, globalProgressStore, statusTreeview, categoryTreeview,
                this::refresh);
        this.detailTabsPresenter = new DetailTabsPresenter(downloadManager, trackersStore,
                peersStore, filesStore, filesTreeview, completionDetailsStore,
                () -> selectedDownload);
        this.sourcesPresenter = new SourcesPresenter(downloadManager, () -> selectedDownload);
        ScrolledWindow sourcesScrolled = new ScrolledWindow();
        sourcesScrolled.setChild(sourcesPresenter.root);
        sourcesScrolled.setVexpand(true);
        sourcesScrolled.setPropagateNaturalHeight(false);
        sourcesScrolled.setMinContentHeight(32);
        Widgets.require(builder, "info_notebook", org.gnome.gtk.Notebook.class)
                .appendPage(sourcesScrolled, new Label(I18n.tr("Sources")));
        this.backgroundExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "odm-window-fetch");
            t.setDaemon(true);
            return t;
        });
        this.offlineModeController = new org.manager.download.OfflineModeController(
                downloadManager, backgroundExecutor);
        if (app != null) {
            window.setApplication(app);
        }

        restoreWindowState(builder);
        window.onCloseRequest(() -> {
            boolean toTray = downloadManager.getGlobalSettings().getBooleanProperty("ui.systemTray", false);
            if (toTray && trayAvailable) {
                saveWindowState(builder); // persist geometry; destroy() skips this handler
                dismissExitConfirmation();
                window.setVisible(false);
                return true; // suppress the close; tray keeps the app running
            }
            if (!beginFinalExit()) {
                return true;
            }
            if (finalCloseDelegate != null) {
                // Hand the exit sequence to the app (graceful shutdown with
                // a progress dialog); it disposes the window when done
                window.setVisible(false);
                finalCloseDelegate.run();
                return true; // suppress the default close
            }
            return false; // allow close
        });

        statusTreeview.getSelection().onChanged(this::onStatusSelectionChanged);
        categoryTreeview.getSelection().onChanged(this::onCategorySelectionChanged);
        downloadsTreeview.getSelection().setMode(SelectionMode.MULTIPLE);
        downloadsTreeview.getSelection().onChanged(this::onDownloadSelectionChanged);
        downloadScrollAdjustment.onValueChanged(this::loadNextHistoryPageIfNeeded);
        downloadsTreeview.onRowActivated((path, column) ->
                activateDownload(downloadAt(path)));
        filesTreeview.onRowActivated((path, column) -> revealDetailFile(path));
        completionDetailsTreeview.onRowActivated((path, column) ->
                showCompletionActionOutput(path));
        filesTreeview.setExpanderColumn(Widgets.require(builder,
                "files_name_column", org.gnome.gtk.TreeViewColumn.class));

        // Torrent per-file selection: toggle a row -> apply aria2 select-file
        Widgets.require(builder, "files_selected_renderer", org.gnome.gtk.CellRendererToggle.class)
                .onToggled(this::onFileSelectionToggled);
        ListStore filePriorityStore = Widgets.require(builder,
                "files_priority_store", ListStore.class);
        for (String priority : new String[]{FileTreeSupport.PRIORITY_HIGH,
                FileTreeSupport.PRIORITY_NORMAL, FileTreeSupport.PRIORITY_LOW}) {
            TreeIter iter = new TreeIter();
            filePriorityStore.append(iter);
            ListStoreCells.setString(filePriorityStore, iter, 0, FileTreeSupport.displayPriority(priority));
        }
        org.gnome.gtk.CellRendererCombo filePriorityRenderer = Widgets.require(builder,
                "files_priority_renderer", org.gnome.gtk.CellRendererCombo.class);
        filePriorityRenderer.onEditingStarted((editable, path) -> {
            detailTabsPresenter.setFilePriorityEditing(true);
            editable.onEditingDone(() -> detailTabsPresenter.setFilePriorityEditing(false));
            editable.onRemoveWidget(() -> detailTabsPresenter.setFilePriorityEditing(false));
        });
        filePriorityRenderer.onEditingCanceled(() ->
                detailTabsPresenter.setFilePriorityEditing(false));
        filePriorityRenderer.onChanged((path, priorityIter) -> onFilePriorityChanged(path,
                ListStoreCells.getString(filePriorityStore, priorityIter, 0)));

        this.downloadContextClick = new GestureClick();
        downloadContextClick.setButton(3);
        // TreeView installs its own click gestures. Capture the secondary
        // press before its built-in handler can collapse a multi-selection.
        // Claiming a valid row press leaves an existing selected group intact;
        // an unselected row is made the sole context target here instead.
        downloadContextClick.setPropagationPhase(PropagationPhase.CAPTURE);
        downloadContextClick.onPressed((nPress, x, y) -> {
            if (selectContextTargetAt(x, y)) {
                onDownloadSelectionChanged();
                pendingContextSelectionIds = selectedDownloads.stream()
                        .map(Download::getId)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                pendingContextPopup = !pendingContextSelectionIds.isEmpty();
                downloadContextClick.setState(
                        org.gnome.gtk.EventSequenceState.CLAIMED);
            } else {
                pendingContextSelectionIds = List.of();
                pendingContextPopup = false;
            }
        });
        // A released signal still runs inside GTK's claimed pointer sequence.
        // Defer presentation by one main-loop turn so the grab is completely
        // released before the popover starts receiving hover/prelight events.
        downloadContextClick.onReleased((nPress, x, y) -> {
            if (!pendingContextPopup) {
                return;
            }
            List<String> selectionIds = pendingContextSelectionIds;
            pendingContextSelectionIds = List.of();
            pendingContextPopup = false;
            deferContextMenuPopup(() -> showContextMenu(x, y, selectionIds));
        });
        downloadsTreeview.addController(downloadContextClick);
        var contextKey = new EventControllerKey();
        contextKey.onKeyPressed((keyval, keycode, state) -> {
            boolean keyboardMenu = keyval == org.gnome.gdk.Gdk.KEY_Menu
                    || (keyval == org.gnome.gdk.Gdk.KEY_F10
                    && state.contains(org.gnome.gdk.ModifierType.SHIFT_MASK));
            if (keyboardMenu) {
                showContextMenuForSelection();
            }
            return keyboardMenu;
        });
        downloadsTreeview.addController(contextKey);

        Widgets.require(builder, "new_download_button", Button.class).onClicked(this::onAddClicked);
        Widgets.require(builder, "pause_button", Button.class).onClicked(this::onPauseClicked);
        Widgets.require(builder, "resume_button", Button.class).onClicked(this::onResumeClicked);
        Widgets.require(builder, "delete_button", Button.class).onClicked(this::onDeleteClicked);
        this.moveUpButton = Widgets.require(builder, "move_up_button", Button.class);
        this.moveTopButton = Widgets.require(builder, "move_top_button", Button.class);
        this.moveDownButton = Widgets.require(builder, "move_down_button", Button.class);
        this.moveBottomButton = Widgets.require(builder, "move_bottom_button", Button.class);
        moveUpButton.onClicked(() -> moveSelectedDownloads(QueueMove.UP));
        moveTopButton.onClicked(() -> moveSelectedDownloads(QueueMove.TOP));
        moveDownButton.onClicked(() -> moveSelectedDownloads(QueueMove.DOWN));
        moveBottomButton.onClicked(() -> moveSelectedDownloads(QueueMove.BOTTOM));
        Widgets.require(builder, "settings_button", Button.class).onClicked(this::onSettingsClicked);
        folderOpenButton.onActivateLink(() -> {
            openDisplayedSaveFolder();
            return true;
        });
        errorDetailsButton.onClicked(() -> {
            if (selectedDownload != null && selectedDownload.getErrorMessage() != null) {
                ActionOutputDialog.presentError(window, selectedDownload.getName(),
                        selectedDownload.getErrorMessage());
            }
        });
        this.searchEntry = Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        // Use the visible search field instead of TreeView's internal popup,
        // which emits GTK CSS criticals on keyboard input with GTK 4.14.
        downloadsTreeview.setSearchEntry(searchEntry);
        searchEntry.onSearchChanged(this::onSearchChanged);
        this.torCheckButton = Widgets.require(builder, "tor_check_button", Button.class);
        installStatusBarCss();
        torDesiredRunning.set(torService.isRunning() || torService.isStarting());
        this.torCircuitMonitor = new org.tor.TorCircuitMonitor(torService,
                downloadManager.getGlobalSettings(), offlineModeController, this::onTorCheckStarted);
        torCheckButton.onClicked(() -> torCircuitMonitor.checkNow());
        this.windowTorListener = event -> UiThread.marshal(() -> {
            if (!finalExitStarted) {
                onTorServiceEvent(event);
            }
        });
        torService.addListener(windowTorListener);
        this.menuBar = Widgets.require(builder, "menu_bar", PopoverMenuBar.class);
        this.leftPanelWidget = Widgets.require(builder, "left_panel", org.gnome.gtk.Widget.class);
        this.infoPanelWidget = Widgets.require(builder, "info_panel_box", org.gnome.gtk.Widget.class);
        menuBar.setMenuModel(buildMainMenu());
        syncTorPresentation();
        if (app != null) {
            app.setAccelsForAction("win.select-all", new String[]{"<Primary>a"});
        }
        AccessibilitySupport.label(statusTreeview, I18n.tr("Download status filters"));
        AccessibilitySupport.label(categoryTreeview, I18n.tr("Download category filters"));
        AccessibilitySupport.label(downloadsTreeview, I18n.tr("Downloads"));
        installDownloadTooltips(builder);
        AccessibilitySupport.label(searchEntry, I18n.tr("Search downloads"));
        AccessibilitySupport.label(torCheckButton, I18n.tr("Verify Tor connection"));
        AccessibilitySupport.label(menuBar, I18n.tr("Application menu"));
        AccessibilitySupport.label(folderOpenButton, I18n.tr("Open displayed save folder"));
        Widgets.require(builder, "status_label", Label.class).setMnemonicWidget(statusTreeview);
        Widgets.require(builder, "category_label", Label.class).setMnemonicWidget(categoryTreeview);

        windowDownloadListener = new DownloadListener() {
            @Override public void onDownloadStart(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadStatusChanged(Download d,
                    Download.Status previousStatus, Download.Status currentStatus) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadPause(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadResume(Download d) { listPresenter.scheduleRefresh(); }
            @Override public void onDownloadComplete(Download d) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadError(Download d, String errorMessage) {
                listPresenter.scheduleRefresh();
            }
            @Override public void onDownloadCanceled(Download d) { listPresenter.scheduleRefresh(); }
        };
        downloadManager.addDownloadListener(windowDownloadListener);

        // Surface after-completion action results (e.g. antivirus threats)
        // in the info bar; callbacks may arrive from worker threads.
        windowCompletionListener = new org.manager.download.action.AfterCompletionActionListener() {
            @Override
            public void onActionStart(Download d, org.manager.download.action.AfterCompletionAction a) {
                UiThread.marshal(() -> {
                    if (a.contributesToFinalizingProgress()) {
                        runningCompletionDownloads.add(d.getId());
                        ensureCompletionPulseTimer();
                    }
                    refreshDetailHistoryPresentation(d);
                });
            }

            @Override
            public void onActionComplete(Download d, org.manager.download.action.AfterCompletionAction a) {
                if (a instanceof org.manager.download.action.AntivirusCheckAction av) {
                    UiThread.marshal(() -> {
                        if (av.isThreatDetected()) {
                            AccessibilitySupport.status(infoLabel,
                                    I18n.format("THREAT DETECTED in %s — scan result: %s", d.getName(), av.getScanResult()),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            LOGGER.warn("Antivirus threat detected in " + d.getName()
                                    + ": " + av.getScanResult());
                        } else {
                            AccessibilitySupport.status(infoLabel,
                                    I18n.format("Antivirus scan completed for %s", d.getName()));
                        }
                        refreshDetailHistoryPresentation(d);
                    });
                } else {
                    UiThread.marshal(() -> refreshDetailHistoryPresentation(d));
                }
            }

            @Override
            public void onActionError(Download d, org.manager.download.action.AfterCompletionAction a,
                    String errorMessage, org.manager.download.action.AfterCompletionAction.Severity severity) {
                LOGGER.warn(
                        "Completion action failed for " + d.getName() + ": " + errorMessage);
                UiThread.marshal(() -> refreshDetailHistoryPresentation(d));
            }

            @Override
            public void onAllActionsComplete(Download d,
                    java.util.List<org.manager.download.action.AfterCompletionAction> successful,
                    java.util.List<org.manager.download.action.AfterCompletionAction> failed) {
                UiThread.marshal(() -> {
                    runningCompletionDownloads.remove(d.getId());
                    if (runningCompletionDownloads.isEmpty()) {
                        stopCompletionPulseTimer();
                    }
                    refreshDetailHistoryPresentation(d);
                });
            }
        };
        downloadManager.addAfterCompletionActionListener(windowCompletionListener);

        // Clipboard detection flow: in silent mode core creates QUEUED
        // downloads on its own; otherwise open the matching download dialog with
        // the detected URL prefilled. Callbacks arrive on monitor threads,
        // so everything widget-touching goes through UiThread.
        try {
            windowClipboardListener = new org.manager.clipboard.ClipboardServiceListener() {
                @Override
                public void onUrlsDetected(java.util.List<java.net.URI> urls, String content) {
                    // handled by the silent/auto paths in ClipboardService
                }

                @Override
                public void onConfirmationRequired(java.util.List<java.net.URI> urls, String content) {
                    if (urls == null || urls.isEmpty()) {
                        return;
                    }
                    UiThread.marshal(() -> {
                        presentDownloadConfirmation(urls.getFirst());
                        if (urls.size() > 1) {
                            LOGGER.info(urls.size() + " URLs detected; offering the first");
                        }
                    });
                }
            };
            downloadManager.getClipboardService().addServiceListener(windowClipboardListener);
            // Restore the persisted silent-mode flag into the core service
            applyClipboardSilentToCore(downloadManager.getGlobalSettings()
                    .getBooleanProperty("ui.clipboardSilent", false));
        } catch (Exception e) {
            LOGGER.warn(
                    "Clipboard service listener registration failed", e);
        }

        refresh();
    }

    public void present() {
        window.present();
        downloadsTreeview.grabFocus();
    }

    /**
     * Installs an override for the app-exit sequence. When set, the final
     * close (window close or File &gt; Exit) hides the window and runs this
     * delegate instead of ending immediately — OdmApplication uses it to
     * show the shutdown progress dialog while the core shuts down
     * gracefully. The delegate must eventually call {@link #dispose()}.
     *
     * @param delegate the exit sequence, or null to restore default behavior
     */
    public void setFinalCloseDelegate(Runnable delegate) {
        this.finalCloseDelegate = delegate;
    }

    /** Shows the explicit confirmation used only by File -> Exit. */
    void requestExitFromMenu() {
        if (finalExitStarted) {
            return;
        }
        if (exitConfirmation != null) {
            exitConfirmation.present();
            return;
        }

        org.gnome.gtk.MessageDialog confirmation = new org.gnome.gtk.MessageDialog();
        DialogSupport.configureIndependent(confirmation, window);
        confirmation.setTitle(I18n.tr("Confirm Exit"));
        confirmation.setMarkup(I18n.tr("<b>Exit Open Download Manager?</b>"));
        confirmation.formatSecondaryText(
                I18n.tr("The application will stop its download services and close."));
        int cancelResponse = org.gnome.gtk.ResponseType.CANCEL.getValue();
        int exitResponse = org.gnome.gtk.ResponseType.ACCEPT.getValue();
        confirmation.addButton(I18n.tr("Cancel"), cancelResponse);
        org.gnome.gtk.Widget exitButton = confirmation.addButton(I18n.tr("Exit"), exitResponse);
        exitButton.addCssClass("destructive-action");
        confirmation.setDefaultResponse(cancelResponse);
        confirmation.onResponse(response -> {
            if (exitConfirmation == confirmation) {
                exitConfirmation = null;
            }
            confirmation.close();
            if (response == exitResponse) {
                performFinalExit();
            }
        });
        exitConfirmation = confirmation;
        confirmation.present();
    }

    private void performFinalExit() {
        if (!beginFinalExit()) {
            return;
        }
        if (finalCloseDelegate != null) {
            window.setVisible(false);
            finalCloseDelegate.run();
        } else {
            window.destroy();
        }
    }

    /** Common one-shot preparation for menu exits and title-bar closes. */
    private boolean beginFinalExit() {
        if (finalExitStarted) {
            return false;
        }
        finalExitStarted = true;
        publishTrayState();
        dismissExitConfirmation();
        saveWindowState(uiBuilder);
        removeWindowListeners();
        return true;
    }

    private void dismissExitConfirmation() {
        org.gnome.gtk.MessageDialog confirmation = exitConfirmation;
        exitConfirmation = null;
        if (confirmation != null) {
            confirmation.close();
        }
    }

    org.gnome.gtk.MessageDialog exitConfirmationDialog() {
        return exitConfirmation;
    }

    boolean hasStartedFinalExit() {
        return finalExitStarted;
    }

    /** Controls whether closing to the notification area can keep the app reachable. */
    public void setTrayAvailable(boolean available) {
        this.trayAvailable = available;
        if (!available) { trayStateListener = null; }
    }

    /** Snapshots and activations use the same actions as the main window menus. */
    void setTrayStateListener(java.util.function.Consumer<java.util.Map<String, TrayMenu.ActionState>> listener) {
        trayStateListener = listener;
        publishTrayState();
    }

    java.util.Map<String, TrayMenu.ActionState> trayActionStates() {
        java.util.Map<String, TrayMenu.ActionState> states = new java.util.LinkedHashMap<>();
        for (TrayMenu.Entry entry : TrayMenu.ENTRIES) {
            if (entry.action() == null) { continue; }
            var action = menuActions.get(entry.action());
            states.put(entry.action(), new TrayMenu.ActionState(
                    !finalExitStarted && action != null && action.getEnabled(),
                    action != null && entry.checkable() && action.getState().getBoolean()));
        }
        return java.util.Map.copyOf(states);
    }

    private void publishTrayState() {
        if (trayStateListener != null) { trayStateListener.accept(trayActionStates()); }
    }

    void activateTrayAction(String name) {
        if (finalExitStarted || !trayActionStates().containsKey(name)) { return; }
        var action = menuActions.get(name);
        if (action != null && action.getEnabled()) {
            if ("new-download".equals(name)) { present(); }
            action.activate(null);
        }
    }

    /** Lets the application create or retire its tray export after Settings is saved. */
    public void setTrayPreferenceHandler(java.util.function.Consumer<Boolean> handler) {
        this.trayPreferenceHandler = handler;
    }

    /** Really destroys the window (bypasses the close-request handler). */
    public void dispose() {
        shutdownBackgroundWork();
        window.destroy();
    }

    /**
     * Stops the window-owned executors (detail-tab fetches and background
     * I/O). Idempotent; part of every real teardown path.
     */
    private void shutdownBackgroundWork() {
        if (backgroundWorkShutdown) {
            return;
        }
        backgroundWorkShutdown = true;
        finalExitStarted = true;
        publishTrayState();
        trayStateListener = null;
        torServiceController.cancelPending();
        torDesiredRunning.set(false);
        torToggleEpoch.incrementAndGet();
        torCircuitMonitor.close();
        torService.removeListener(windowTorListener);
        runningCompletionDownloads.clear();
        stopCompletionPulseTimer();
        if (contextMenu != null) {
            contextMenu.dispose();
            contextMenu = null;
        }
        detailTabsPresenter.shutdown();
        sourcesPresenter.shutdown();
        infoProgressGraph.dispose();
        activity.dispose();
        backgroundExecutor.shutdown();
    }

    private void ensureCompletionPulseTimer() {
        if (completionPulseSourceId != 0) {
            return;
        }
        completionPulseSourceId = org.gnome.glib.GLib.timeoutAdd(
                org.gnome.glib.GLib.PRIORITY_DEFAULT, 100,
                () -> {
                    if (runningCompletionDownloads.isEmpty()) {
                        completionPulseSourceId = 0;
                        return false;
                    }
                    listPresenter.pulseCompletionRows();
                    return true;
                });
    }

    private void stopCompletionPulseTimer() {
        if (completionPulseSourceId != 0) {
            org.gnome.glib.Source.remove(completionPulseSourceId);
            completionPulseSourceId = 0;
        }
    }

    private void refreshDetailHistoryPresentation(Download download) {
        listPresenter.scheduleRefresh();
        if (selectedDownload != null
                && selectedDownload.getId().equals(download.getId())) {
            detailTabsPresenter.load();
        }
    }

    /**
     * Detaches every listener this window registered with core services.
     * Called from the final close path; without it the manager keeps
     * dispatching refresh work to a dead window forever.
     */
    private void removeWindowListeners() {
        shutdownBackgroundWork();
        if (windowDownloadListener != null) {
            downloadManager.removeDownloadListener(windowDownloadListener);
            windowDownloadListener = null;
        }
        if (windowCompletionListener != null) {
            downloadManager.removeAfterCompletionActionListener(windowCompletionListener);
            windowCompletionListener = null;
        }
        if (windowClipboardListener != null) {
            try {
                downloadManager.getClipboardService().removeServiceListener(windowClipboardListener);
            } catch (Exception e) {
                LOGGER.warn(
                        "Failed to remove clipboard service listener", e);
            }
            windowClipboardListener = null;
        }
    }

    private void onAddClicked() {
        new NewDownloadDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh), torService).present();
    }

    /** Desktop links share clipboard silent mode and the common URL admission policy. */
    void openDownloadUrls(List<String> urls) {
        boolean silent = downloadManager.getGlobalSettings().getBooleanProperty("ui.clipboardSilent", false);
        for (String url : urls) {
            var source = DownloadUrlPolicy.parse(url);
            if (source.isEmpty()) {
                AccessibilitySupport.status(infoLabel, I18n.tr("Cannot open an invalid or unsupported download link."));
                continue;
            }
            try {
                if (silent) {
                    submitDesktopDownload(source.orElseThrow().uri());
                } else {
                    presentDownloadConfirmation(source.orElseThrow().uri());
                }
            } catch (Exception error) {
                LOGGER.warn("Failed to handle download link", error);
                AccessibilitySupport.status(infoLabel, I18n.format("Could not add download: %s", UiErrors.message(error)));
            }
        }
    }

    private void submitDesktopDownload(java.net.URI source) {
        Path destination = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
        CompletableFuture.supplyAsync(() -> downloadManager.createDownload(source, destination), backgroundExecutor)
                // Match silent clipboard admission, including the global automatic-start policy.
                .thenCompose(downloadManager::queueDownloadFromBackgroundSource)
                .whenComplete((ignored, error) -> {
                    if (error != null) { LOGGER.warn("Failed to submit download link", error); }
                    UiThread.marshal(() -> {
                        if (finalExitStarted) { return; }
                        if (error != null) {
                            AccessibilitySupport.status(infoLabel, I18n.format("Could not add download: %s", UiErrors.message(error)));
                        } else {
                            refresh();
                        }
                    });
                });
    }

    private void presentDownloadConfirmation(java.net.URI source) {
        if (org.manager.download.MediaUrlDetector.isMediaUrl(source)) {
            NewMediaDialog dialog = new NewMediaDialog(window, downloadManager,
                    () -> UiThread.marshal(this::refresh), torService);
            dialog.prefillUrl(source.toString());
            dialog.present();
        } else {
            NewDownloadDialog dialog = new NewDownloadDialog(window, downloadManager,
                    () -> UiThread.marshal(this::refresh), torService);
            dialog.prefillUrl(source.toString());
            dialog.present();
        }
    }

    private void onNewMediaClicked() {
        new NewMediaDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh), torService).present();
    }

    /**
     * Pushes the silent-mode flag into the core clipboard service so
     * detected URLs are placed in QUEUED status instead of popping dialogs.
     */
    private void applyClipboardSilentToCore(boolean silent) {
        try {
            org.manager.clipboard.ClipboardSettings current =
                    downloadManager.getClipboardService().getSettings();
            downloadManager.updateClipboardSettings(current.copy().setSilentMode(silent));
        } catch (Exception e) {
            LOGGER.warn(
                    "Failed to apply clipboard silent mode", e);
        }
    }

    private void onSettingsClicked() {
        createSettingsDialog().present();
    }

    private SettingsDialog createSettingsDialog() {
        return new SettingsDialog(window, downloadManager, scheduleManager, active -> {
            if (trayPreferenceHandler != null) {
                trayPreferenceHandler.accept(downloadManager.getGlobalSettings()
                        .getBooleanProperty("ui.systemTray", false));
            }
            syncScheduleActionState();
            syncModeActions();
            torCircuitMonitor.refresh();
            // Rebuild settings-backed actions (subtitles, antivirus, custom)
            // so changes apply without requiring a restart or re-selection.
            installCompletionActions();
        }, torService, jackettService);
    }

    private void onSearchTorrents() {
        new SearchTorrentsDialog(window, downloadManager, jackettService, torService,
                () -> UiThread.marshal(this::refresh)).present();
    }

    private void onSearchChanged() {
        listPresenter.setSearchText(searchEntry.getText().strip().toLowerCase());
        refreshWithActivity();
    }

    private void onPauseClicked() {
        runSelectedDownloads(MainWindow::canPause, downloadManager::pauseDownload, I18n.mark("Could not pause all selected downloads: %s"));
    }

    private void onResumeClicked() {
        onDownloadSelectionChanged();
        List<Download> targets = selectedDownloads.stream()
                .filter(MainWindow::canStartOrResume)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        trackActivity(allOf(targets.stream()
                .map(download -> download.getStatus() == Download.Status.PAUSED
                        ? downloadManager.resumeDownload(download)
                        : downloadManager.startDownload(download))
                .toList())).whenComplete((ignored, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        AccessibilitySupport.status(infoLabel,
                                I18n.format("Could not start or resume all selected downloads: %s", UiErrors.message(error)),
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                    }
                    refresh();
                }));
    }

    private void onDeleteClicked() {
        onDownloadSelectionChanged();
        removeDownloads(List.copyOf(selectedDownloads), false,
                I18n.mark("Could not delete all selected downloads: %s"));
    }

    private void removeDownloads(List<Download> targets, boolean deleteFiles, String failureMessage) {
        if (targets.isEmpty()) { return; }
        trackActivity(downloadManager.cancelDownloads(targets, deleteFiles))
                .whenComplete((ignored, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        AccessibilitySupport.status(infoLabel, I18n.format(failureMessage, UiErrors.message(error)),
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                    }
                    refresh();
                }));
    }

    private void startSelectedDownloads() {
        runSelectedDownloads(MainWindow::canStart, downloadManager::startDownload, I18n.mark("Could not start all selected downloads: %s"));
    }

    /** Reorders the selected waiting downloads while preserving their relative order. */
    private void moveSelectedDownloads(QueueMove move) {
        onDownloadSelectionChanged();
        List<Download> queued = queuedDownloadsForMovement();
        QueueMovementCapabilities capabilities =
                queueMovementCapabilities(selectedDownloads, queued);
        if (!capabilities.allows(move)) {
            updateQueueButtonSensitivity(capabilities);
            return;
        }

        // A user-requested queue move and an explicit column sort conflict.
        // Return to the manager's queue order so the move is immediately visible.
        listPresenter.useQueueOrder();
        for (Download target : queueMoveTargets(selectedDownloads, queued, move)) {
            switch (move) {
                case UP -> downloadManager.moveDownloadUp(target);
                case TOP -> downloadManager.moveDownloadToTop(target);
                case DOWN -> downloadManager.moveDownloadDown(target);
                case BOTTOM -> downloadManager.moveDownloadToBottom(target);
            }
        }
        updateQueueButtonSensitivity(queueMovementCapabilities(
                selectedDownloads, queuedDownloadsForMovement()));
        refresh();
    }

    private List<Download> queuedDownloadsForMovement() {
        List<Download> queued = downloadManager.getDownloadsByStatus(Download.Status.QUEUED);
        return queued == null ? List.of() : queued;
    }

    /** Queue controls require an all-queued selection and remain boundary-aware. */
    static QueueMovementCapabilities queueMovementCapabilities(List<Download> selection,
            List<Download> queuedDownloads) {
        if (selection == null || selection.isEmpty() || queuedDownloads == null) {
            return QueueMovementCapabilities.NONE;
        }
        java.util.Set<String> selectedIds = selection.stream()
                .filter(java.util.Objects::nonNull)
                .filter(download -> download.getStatus() == Download.Status.QUEUED)
                .map(Download::getId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
        if (selectedIds.size() != selection.size()) {
            return QueueMovementCapabilities.NONE;
        }
        List<Download> ordered = orderedQueuedDownloads(queuedDownloads);
        long matched = ordered.stream()
                .map(Download::getId)
                .filter(selectedIds::contains)
                .distinct()
                .count();
        if (matched != selectedIds.size()) {
            return QueueMovementCapabilities.NONE;
        }

        boolean canMoveUp = false;
        boolean canMoveDown = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (!selectedIds.contains(ordered.get(i).getId())) {
                continue;
            }
            canMoveUp |= i > 0 && !selectedIds.contains(ordered.get(i - 1).getId());
            canMoveDown |= i + 1 < ordered.size()
                    && !selectedIds.contains(ordered.get(i + 1).getId());
        }
        return new QueueMovementCapabilities(
                canMoveUp, canMoveUp, canMoveDown, canMoveDown);
    }

    /**
     * Invocation order for the existing single-record manager operations.
     * Up/bottom run in ascending order; top/down run in reverse order so a
     * multi-selection never reverses itself while individual moves settle.
     */
    static List<Download> queueMoveTargets(List<Download> selection,
            List<Download> queuedDownloads, QueueMove move) {
        if (!queueMovementCapabilities(selection, queuedDownloads).allows(move)) {
            return List.of();
        }
        java.util.Set<String> selectedIds = selection.stream()
                .map(Download::getId)
                .collect(java.util.stream.Collectors.toSet());
        java.util.ArrayList<Download> targets = orderedQueuedDownloads(queuedDownloads).stream()
                .filter(download -> selectedIds.contains(download.getId()))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        if (move == QueueMove.TOP || move == QueueMove.DOWN) {
            java.util.Collections.reverse(targets);
        }
        return List.copyOf(targets);
    }

    private static List<Download> orderedQueuedDownloads(List<Download> queuedDownloads) {
        return queuedDownloads.stream()
                .filter(java.util.Objects::nonNull)
                .filter(download -> download.getStatus() == Download.Status.QUEUED)
                .sorted(java.util.Comparator.comparingInt(Download::getQueuePosition)
                        .thenComparing(Download::getCreatedAt,
                                java.util.Comparator.nullsLast(
                                        java.util.Comparator.naturalOrder())))
                .toList();
    }

    private void runSelectedDownloads(java.util.function.Predicate<Download> applicable,
            java.util.function.Function<Download, CompletableFuture<Void>> operation,
            String failureMessage) {
        onDownloadSelectionChanged();
        List<Download> targets = selectedDownloads.stream().filter(applicable).toList();
        if (targets.isEmpty()) {
            return;
        }
        trackActivity(allOf(targets.stream().map(operation).toList())).whenComplete((ignored, error) ->
                UiThread.marshal(() -> {
                    if (error != null) {
                        AccessibilitySupport.status(infoLabel,
                                I18n.format(failureMessage, UiErrors.message(error)),
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                    }
                    refresh();
                }));
    }

    private static CompletableFuture<Void> allOf(
            List<? extends CompletableFuture<?>> futures) {
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private <T> CompletableFuture<T> trackActivity(CompletableFuture<T> future) {
        return activity.track(future);
    }

    /** Requires an explicit destructive confirmation and captures the target
     * before showing the asynchronous dialog so a later selection change
     * cannot delete a different download's files. */
    private void confirmDeleteWithFiles(List<Download> targets) {
        List<Download> capturedTargets = targets == null ? List.of() : List.copyOf(targets);
        if (capturedTargets.isEmpty()) {
            return;
        }
        boolean multiple = capturedTargets.size() > 1;
        org.gnome.gtk.MessageDialog confirmation = new org.gnome.gtk.MessageDialog();
        DialogSupport.configureIndependent(confirmation, window);
        confirmation.setMarkup(multiple
                ? I18n.tr("<b>Delete these downloads and their files?</b>")
                : I18n.tr("<b>Delete this download and its files?</b>"));
        confirmation.formatSecondaryText(multiple
                ? I18n.tr("This permanently removes files for %d selected downloads.")
                        .formatted(capturedTargets.size())
                : I18n.tr("This permanently removes files for \"%s\".")
                        .formatted(capturedTargets.getFirst().getName()));
        int cancelResponse = org.gnome.gtk.ResponseType.CANCEL.getValue();
        int acceptResponse = org.gnome.gtk.ResponseType.ACCEPT.getValue();
        confirmation.addButton(I18n.tr("Cancel"), cancelResponse);
        org.gnome.gtk.Widget deleteButton =
                confirmation.addButton(I18n.tr("Delete Files"), acceptResponse);
        deleteButton.addCssClass("destructive-action");
        confirmation.setDefaultResponse(cancelResponse);
        confirmation.onResponse(response -> {
            confirmation.close();
            if (response != acceptResponse) {
                return;
            }
            removeDownloads(capturedTargets, true,
                    I18n.mark("Could not delete all selected download files: %s"));
        });
        confirmation.present();
    }

    private void onPropertiesClicked() {
        onDownloadSelectionChanged();
        if (!selectedDownloads.isEmpty()) {
            new PropertyDialog(window, downloadManager, selectedDownloads, torService).present();
        }
    }

    /** Context menu rebuilt on popup so sensitivity reflects the current multi-selection. */
    private PopupMenu contextMenu;

    private void showContextMenu(double x, double y, List<String> selectionIds) {
        if (!restoreContextSelection(listPresenter,
                downloadsTreeview.getSelection(), selectionIds)) {
            return;
        }
        onDownloadSelectionChanged();
        if (selectedDownload == null) return;
        showContextMenuAt((int) x, (int) y);
    }

    /** Runs after the current GTK input dispatch has released its pointer grab. */
    static void deferContextMenuPopup(Runnable popup) {
        UiThread.marshal(popup);
    }

    /** Restores the press-time selection if GTK changed it before popup time. */
    static boolean restoreContextSelection(DownloadListPresenter presenter,
            TreeSelection selection, List<String> downloadIds) {
        if (presenter == null || selection == null
                || downloadIds == null || downloadIds.isEmpty()) {
            return false;
        }
        selection.unselectAll();
        return presenter.restoreSelection(selection, downloadIds) > 0;
    }

    /** Selects the row under a secondary click without collapsing a selected group. */
    private boolean selectContextTargetAt(double x, double y) {
        Out<org.gnome.gtk.TreeViewColumn> column = new Out<>();
        TreePath path = pathAtWidgetPosition(downloadsTreeview, (int) x, (int) y, column);
        if (path == null) {
            return false;
        }
        selectContextTarget(downloadsTreeview, path, column.get());
        return true;
    }

    /**
     * Resolves a gesture position to a row. GestureClick reports widget
     * coordinates, while TreeView row lookup expects bin-window coordinates;
     * the difference is the header offset and is most visible on row zero.
     */
    static TreePath pathAtWidgetPosition(TreeView tree, int widgetX, int widgetY,
            Out<org.gnome.gtk.TreeViewColumn> column) {
        Out<Integer> binX = new Out<>();
        Out<Integer> binY = new Out<>();
        tree.convertWidgetToBinWindowCoords(widgetX, widgetY, binX, binY);
        Out<TreePath> path = new Out<>();
        if (!tree.getPathAtPos(binX.get(), binY.get(), path, column,
                new Out<>(), new Out<>())) {
            return null;
        }
        return path.get();
    }

    /** Shows row-specific text for the ellipsized Name and icon-only Type cells. */
    private void installDownloadTooltips(GtkBuilder builder) {
        org.gnome.gtk.TreeViewColumn nameColumn = Widgets.require(
                builder, "name_column", org.gnome.gtk.TreeViewColumn.class);
        org.gnome.gtk.CellRenderer nameRenderer = Widgets.require(
                builder, "name_renderer", org.gnome.gtk.CellRenderer.class);
        org.gnome.gtk.TreeViewColumn resultColumn = Widgets.require(
                builder, "tor_icon_column", org.gnome.gtk.TreeViewColumn.class);
        org.gnome.gtk.CellRenderer resultRenderer = Widgets.require(
                builder, "engine_icon_renderer", org.gnome.gtk.CellRenderer.class);
        downloadsTreeview.setHasTooltip(true);
        downloadsTreeview.onQueryTooltip((x, y, keyboardMode, tooltip) -> {
            if (tooltip == null) {
                return false;
            }
            Out<TreePath> cursorPath = new Out<>();
            Out<org.gnome.gtk.TreeViewColumn> hoveredColumn = new Out<>();
            TreePath path;
            if (keyboardMode) {
                downloadsTreeview.getCursor(cursorPath, hoveredColumn);
                path = cursorPath.get();
            } else {
                path = pathAtWidgetPosition(downloadsTreeview, x, y, hoveredColumn);
            }
            if (path == null) {
                return false;
            }
            try {
                // A keyboard tooltip describes the selected record's name even
                // when its current cursor column is not the Name column.
                org.gnome.gtk.TreeViewColumn tooltipColumn = keyboardMode
                        ? nameColumn : hoveredColumn.get();
                boolean resultCell = !keyboardMode
                        && tooltipColumn != null
                        && tooltipColumn.handle().equals(resultColumn.handle());
                Download download = resultCell ? downloadAt(path) : null;
                String text = resultCell
                        ? downloadResultTooltip(download, tooltipColumn, resultColumn)
                        : downloadNameTooltip(
                                downloadsStore, path, tooltipColumn, nameColumn);
                if (text == null || text.isBlank()) {
                    return false;
                }
                tooltip.setText(text);
                downloadsTreeview.setTooltipCell(
                        tooltip, path,
                        resultCell ? resultColumn : nameColumn,
                        resultCell ? resultRenderer : nameRenderer);
                return true;
            } finally {
                org.javagi.interop.MemoryCleaner.free(path.handle());
            }
        });
    }

    static String downloadNameTooltip(ListStore store, TreePath path,
            org.gnome.gtk.TreeViewColumn hoveredColumn,
            org.gnome.gtk.TreeViewColumn nameColumn) {
        if (store == null || path == null || hoveredColumn == null || nameColumn == null
                || !hoveredColumn.handle().equals(nameColumn.handle())) {
            return null;
        }
        TreeIter iter = new TreeIter();
        return store.getIter(iter, path)
                ? ListStoreCells.getString(store, iter, 1)
                : null;
    }

    static String downloadResultTooltip(Download download,
            org.gnome.gtk.TreeViewColumn hoveredColumn,
            org.gnome.gtk.TreeViewColumn resultColumn) {
        if (download == null || hoveredColumn == null || resultColumn == null
                || !hoveredColumn.handle().equals(resultColumn.handle())) {
            return null;
        }
        return CompletionActionPresentation.tooltip(download);
    }

    private void showContextMenuForSelection() {
        onDownloadSelectionChanged();
        if (selectedDownload == null) {
            return;
        }
        Out<TreePath> path = new Out<>();
        Out<org.gnome.gtk.TreeViewColumn> column = new Out<>();
        downloadsTreeview.getCursor(path, column);
        var area = new org.gnome.gdk.Rectangle();
        if (path.get() != null) {
            downloadsTreeview.getCellArea(path.get(), column.get(), area);
        }
        showContextMenuAt(area.readX() + Math.max(1, area.readWidth() / 2),
                area.readY() + Math.max(1, area.readHeight() / 2));
    }

    /**
     * Makes an unselected row the sole context target while preserving an
     * already-selected multi-row group when the click lands inside it.
     */
    static void selectContextTarget(TreeView tree, TreePath path,
            org.gnome.gtk.TreeViewColumn column) {
        TreeSelection selection = tree.getSelection();
        if (!selection.pathIsSelected(path)) {
            selection.unselectAll();
            tree.setCursor(path, column, false);
        }
        tree.grabFocus();
    }

    private void showContextMenuAt(int x, int y) {
        // 1:1 action set from download_context_menu, now selection-aware.
        if (contextMenu != null) {
            contextMenu.dispose();
        }
        DownloadSelectionCapabilities capabilities = selectionCapabilities(selectedDownloads);
        DownloadLinkCopy linkCopy = DownloadLinkCopy.from(selectedDownloads);
        contextMenu = new PopupMenu()
                .add(I18n.tr("Open"), capabilities.openFile(), () -> openSelected("file"))
                .add(I18n.tr("Open Folder"), capabilities.openFolder(), () -> openSelected("folder"))
                .separator()
                .add(I18n.tr("Pause"), capabilities.pause(), this::onPauseClicked)
                .add(I18n.tr("Resume"), capabilities.resume(), this::onResumeClicked)
                .add(I18n.tr("Start"), capabilities.start(), this::startSelectedDownloads)
                .separator()
                .add(linkCopy.label(), capabilities.copyLinks(), this::copyDownloadLinks)
                .add(I18n.tr("Change Destination…"), capabilities.changeDestination(), this::changeDestination)
                .add(I18n.tr("Recheck Data"), capabilities.recheckData(), this::recheckData)
                .add(I18n.tr("Download Subtitles"), capabilities.downloadSubtitles(),
                        this::downloadSubtitles)
                .add(I18n.tr("Update Website Mirror…"), capabilities.updateMirror(),
                        this::updateWebsiteMirror)
                .add(I18n.tr("Open HTTrack Log"), capabilities.openHttrackLog(), () ->
                        openHttrackDiagnostic(
                                org.manager.download.HttrackMirrorSupport.DiagnosticLog.ACTIVITY))
                .add(I18n.tr("Open HTTrack Error Log"), capabilities.openHttrackErrorLog(), () ->
                        openHttrackDiagnostic(
                                org.manager.download.HttrackMirrorSupport.DiagnosticLog.ERRORS))
                .add(I18n.tr("Properties"), capabilities.properties(), this::onPropertiesClicked)
                .separator()
                .add(I18n.tr("Delete"), capabilities.delete(), this::onDeleteClicked)
                .add(I18n.tr("Delete with Files"), capabilities.deleteWithFiles(), () ->
                        confirmDeleteWithFiles(selectedDownloads));
        contextMenu.popupAt(menuBar, downloadsTreeview, x, y);
    }

    /** Copies every selected source or magnet URI, one per line. */
    private void copyDownloadLinks() {
        onDownloadSelectionChanged();
        DownloadLinkCopy linkCopy = DownloadLinkCopy.from(selectedDownloads);
        if (linkCopy.available()) {
            String text = linkCopy.text();
            org.manager.clipboard.ClipboardService clipboardService =
                    downloadManager.getClipboardService();
            if (clipboardService != null) {
                clipboardService.bypassNextMonitoredContent(text);
            }
            downloadsTreeview.getClipboard().setText(text);
            AccessibilitySupport.status(infoLabel, linkCopy.confirmation());
        }
    }

    /** Changes the destination folder of the selected download. */
    private void changeDestination() {
        if (selectedDownload == null) {
            return;
        }
        Download targetDownload = selectedDownload;
        if (!canChangeDestination(targetDownload)) {
            AccessibilitySupport.status(infoLabel,
                    I18n.tr("Destination cannot be changed for this download"));
            return;
        }
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        DialogSupport.configureIndependent(dialog);
        dialog.setTitle(I18n.tr("Select new destination"));
        dialog.selectFolder(window, null, result -> {
            try {
                org.gnome.gio.File folder = dialog.selectFolderFinish(result);
                if (folder != null && folder.getPath() != null
                        && canChangeDestination(targetDownload)) {
                    java.nio.file.Path destination = java.nio.file.Path.of(
                            folder.getPath().toString());
                    AccessibilitySupport.status(infoLabel,
                            I18n.format("Moving “%s”…", targetDownload.getName()));
                    trackActivity(downloadManager.relocateDownload(targetDownload, destination))
                            .whenComplete((ignored, error) -> UiThread.marshal(() -> {
                                if (error == null) {
                                    AccessibilitySupport.status(infoLabel,
                                            I18n.format("Moved “%s”", targetDownload.getName()));
                                } else {
                                    AccessibilitySupport.status(infoLabel,
                                            I18n.format("Could not move download: %s", UiErrors.message(error)),
                                            org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                                }
                                refresh();
                            }));
                }
            } catch (Exception e) {
                LOGGER.debug("Destination change cancelled or failed", e);
            }
        });
    }

    private static boolean canChangeDestination(Download download) {
        return download != null && download.getDestination() != null
                && download.getStatus() != Download.Status.CANCELED;
    }

    static DownloadSelectionCapabilities selectionCapabilities(List<Download> selection) {
        List<Download> downloads = selection == null
                ? List.of()
                : selection.stream().filter(java.util.Objects::nonNull).toList();
        boolean any = !downloads.isEmpty();
        boolean single = downloads.size() == 1;
        Download only = single ? downloads.getFirst() : null;
        return new DownloadSelectionCapabilities(
                any,
                single,
                single && only.getStatus() == Download.Status.COMPLETED
                        && only.getPrimaryOutputPath() != null,
                single && only.getDestination() != null,
                allSelectedMatch(downloads, MainWindow::canPause),
                allSelectedMatch(downloads,
                        download -> download.getStatus() == Download.Status.PAUSED),
                allSelectedMatch(downloads, MainWindow::canStart),
                allSelectedMatch(downloads, download -> DownloadLinkCopy.linkFor(download) != null),
                single && canChangeDestination(only),
                allSelectedMatch(downloads, MainWindow::canRecheckData),
                allSelectedMatch(downloads, MainWindow::canDownloadSubtitles),
                single && org.manager.download.HttrackMirrorSupport.canUpdate(only),
                single && org.manager.download.HttrackMirrorSupport.hasDiagnostic(only,
                        org.manager.download.HttrackMirrorSupport.DiagnosticLog.ACTIVITY),
                single && org.manager.download.HttrackMirrorSupport.hasDiagnostic(only,
                        org.manager.download.HttrackMirrorSupport.DiagnosticLog.ERRORS),
                any,
                any,
                any);
    }

    private static boolean allSelectedMatch(List<Download> downloads,
            java.util.function.Predicate<Download> predicate) {
        return !downloads.isEmpty() && downloads.stream().allMatch(predicate);
    }

    private static boolean canPause(Download download) {
        return download != null && switch (download.getStatus()) {
            case QUEUED, STARTING, CONNECTING, DOWNLOADING, SEEDING -> true;
            default -> false;
        };
    }

    static boolean canStartOrResume(Download download) {
        return download != null && (download.getStatus() == Download.Status.PAUSED
                || canStart(download));
    }

    private static boolean canStart(Download download) {
        return download != null && switch (download.getStatus()) {
            case CREATED, QUEUED, ERROR -> true;
            default -> false;
        };
    }

    static boolean canRecheckData(Download download) {
        return org.manager.download.handler.Aria2DownloadHandler.canRecheckData(download);
    }

    static boolean canDownloadSubtitles(Download download) {
        return download != null
                && download.getStatus() == Download.Status.COMPLETED
                && download.getType() != Download.Type.WEBSITE_SCRAPING;
    }

    /** Requests an immediate one-shot data recheck from each selected aria2 item. */
    private void recheckData() {
        onDownloadSelectionChanged();
        List<Download> targets = selectedDownloads.stream()
                .filter(MainWindow::canRecheckData)
                .toList();
        if (targets.isEmpty()) {
            return;
        }
        java.util.List<CompletableFuture<Void>> updates = new java.util.ArrayList<>();
        for (Download target : targets) {
            CompletableFuture<Void> update = downloadManager.recheckData(target);
            refreshDetailHistoryPresentation(target);
            updates.add(update.whenComplete((ignored, error) -> UiThread.marshal(() -> {
                refreshDetailHistoryPresentation(target);
            })));
        }
        trackActivity(allOf(updates));
    }

    /** Runs subtitle discovery again for the selected completed, non-HTTrack records. */
    private void downloadSubtitles() {
        onDownloadSelectionChanged();
        List<Download> targets = List.copyOf(selectedDownloads);
        if (!allSelectedMatch(targets, MainWindow::canDownloadSubtitles)) {
            return;
        }

        List<CompletableFuture<Boolean>> actions = targets.stream()
                .map(target -> downloadManager.executeAfterCompletionAction(target,
                        CompletionActionPolicy.buildSubtitleAction(
                                downloadManager.getGlobalSettings())))
                .toList();
        AccessibilitySupport.status(infoLabel,
                targets.size() == 1
                        ? I18n.format("Downloading subtitles for “%s”…", targets.getFirst().getName())
                        : I18n.plural("Downloading subtitles for %d download…", "Downloading subtitles for %d downloads…", targets.size()));
        trackActivity(allOf(actions))
                .whenComplete((ignored, error) -> UiThread.marshal(() -> {
                    if (error != null) {
                        AccessibilitySupport.status(infoLabel,
                                I18n.format("Could not run all subtitle downloads: %s", UiErrors.message(error)),
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                    } else {
                        long succeeded = actions.stream()
                                .filter(action -> Boolean.TRUE.equals(action.join()))
                                .count();
                        long failed = targets.size() - succeeded;
                        if (failed > 0) {
                            AccessibilitySupport.status(infoLabel,
                                    targets.size() == 1
                                            ? I18n.format("Subtitle download failed for “%s” — see Actions output", targets.getFirst().getName())
                                            : I18n.plural("Subtitle download failed for %2$d of %1$d download — see Actions output",
                                                    "Subtitle download failed for %2$d of %1$d downloads — see Actions output", targets.size(), failed),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                        } else {
                            AccessibilitySupport.status(infoLabel,
                                    targets.size() == 1
                                            ? I18n.format("Subtitle action completed for “%s”", targets.getFirst().getName())
                                            : I18n.plural("Subtitle action completed for %d download",
                                                    "Subtitle actions completed for %d downloads", targets.size()));
                        }
                    }
                    refresh();
                }));
    }

    /** Updates one completed mirror, preserving local-only files by default. */
    private void updateWebsiteMirror() {
        onDownloadSelectionChanged();
        Download target = selectedDownloads.size() == 1
                ? selectedDownloads.getFirst() : null;
        if (!org.manager.download.HttrackMirrorSupport.canUpdate(target)) {
            return;
        }

        org.gnome.gtk.MessageDialog confirmation = new org.gnome.gtk.MessageDialog();
        DialogSupport.configureIndependent(confirmation, window);
        confirmation.setMarkup(I18n.tr("<b>Update this website mirror?</b>"));
        confirmation.formatSecondaryText(
                I18n.tr("HTTrack will revisit the remote site using the existing mirror cache. "
                + "Keeping old files is safer and recommended."));
        int cancelResponse = org.gnome.gtk.ResponseType.CANCEL.getValue();
        int keepResponse = org.gnome.gtk.ResponseType.ACCEPT.getValue();
        int purgeResponse = org.gnome.gtk.ResponseType.APPLY.getValue();
        confirmation.addButton(I18n.tr("Cancel"), cancelResponse);
        org.gnome.gtk.Widget keepButton = confirmation.addButton(
                I18n.tr("Update and Keep Old Files"), keepResponse);
        keepButton.addCssClass("suggested-action");
        org.gnome.gtk.Widget purgeButton = confirmation.addButton(
                I18n.tr("Update and Remove Missing Files"), purgeResponse);
        purgeButton.addCssClass("destructive-action");
        confirmation.setDefaultResponse(keepResponse);
        confirmation.onResponse(response -> {
            confirmation.close();
            if (response != keepResponse && response != purgeResponse) {
                return;
            }
            AccessibilitySupport.status(infoLabel,
                    I18n.format("Starting update for “%s”…", target.getName()));
            trackActivity(downloadManager.updateWebsiteMirror(
                    target, response == purgeResponse)).whenComplete((ignored, error) ->
                            UiThread.marshal(() -> {
                                if (error != null) {
                                    AccessibilitySupport.status(infoLabel,
                                            I18n.format("Could not update website mirror: %s", UiErrors.message(error)),
                                            org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                                }
                                refresh();
                            }));
        });
        confirmation.present();
    }

    private void openHttrackDiagnostic(
            org.manager.download.HttrackMirrorSupport.DiagnosticLog log) {
        onDownloadSelectionChanged();
        Download target = selectedDownloads.size() == 1
                ? selectedDownloads.getFirst() : null;
        Path path = org.manager.download.HttrackMirrorSupport.diagnosticPath(target, log);
        if (!FileManagerSupport.open(path)) {
            AccessibilitySupport.status(infoLabel,
                    I18n.format("Could not open %s", log == null ? I18n.tr("HTTrack log") : log.fileName()),
                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
        }
    }

    /**
     * Builds the full main menu as a Gio.Menu — 1:1 port of the original
     * menu bar (File/Edit/View/Download/Help with submenus, toggles, and
     * radio items), shown in GTK4's classic horizontal PopoverMenuBar.
     */
    private org.gnome.gio.Menu buildMainMenu() {
        registerMenuActions();
        org.gnome.gio.Menu menu = new org.gnome.gio.Menu();

        // File
        org.gnome.gio.Menu file = new org.gnome.gio.Menu();
        file.append(I18n.tr("New Download"), "win.new-download");
        file.append(I18n.tr("New Media Download"), "win.new-media");
        file.append(I18n.tr("New Website Scrape"), "win.scrape");
        file.append(I18n.tr("Search Torrents"), "win.search-torrents");
        org.gnome.gio.Menu batch = new org.gnome.gio.Menu();
        batch.append(I18n.tr("Import URL Sequence"), "win.import-sequence");
        batch.append(I18n.tr("Import from Text File"), "win.import-file");
        batch.append(I18n.tr("Import from HTML File"), "win.import-html");
        batch.append(I18n.tr("Import Links from Remote"), "win.import-remote-html");
        batch.append(I18n.tr("Export Download List"), "win.export-file");
        file.appendSubmenu(I18n.tr("Batch Process"), batch);
        file.append(I18n.tr("Offline Mode"), "win.offline");
        file.append(I18n.tr("Exit"), "win.quit");
        menu.appendSubmenu(I18n.tr("_File"), file);

        // Edit
        org.gnome.gio.Menu edit = new org.gnome.gio.Menu();
        edit.append(I18n.tr("Clipboard Monitoring"), "win.clipboard-monitoring");
        edit.append(I18n.tr("Silent Mode"), "win.clipboard-silent");
        org.gnome.gio.Menu completion = new org.gnome.gio.Menu();
        completion.append(I18n.tr("Notify (sound)"), "win.completion-notify");
        completion.append(I18n.tr("Desktop notification"), "win.completion-desktop-notify");
        completion.append(I18n.tr("Antivirus Scan"), "win.completion-antivirus");
        completion.append(I18n.tr("Download Subtitles"), "win.completion-subtitles");
        completion.append(I18n.tr("Suspend"), "win.completion-suspend");
        completion.append(I18n.tr("Shutdown"), "win.completion-shutdown");
        completion.append(I18n.tr("Custom…"), "win.completion-custom");
        edit.appendSubmenu(I18n.tr("Completion Actions"), completion);
        org.gnome.gio.Menu schedule = new org.gnome.gio.Menu();
        schedule.append(I18n.tr("None (custom grid)"), "win.schedule::none");
        schedule.append(I18n.tr("Always"), "win.schedule::always");
        schedule.append(I18n.tr("Business Hours"), "win.schedule::business");
        schedule.append(I18n.tr("Night Hours"), "win.schedule::night");
        schedule.append(I18n.tr("Weekends"), "win.schedule::weekend");
        schedule.append(I18n.tr("Weekdays"), "win.schedule::weekday");
        schedule.append(I18n.tr("Never (paused)"), "win.schedule::never");
        edit.appendSubmenu(I18n.tr("Schedule"), schedule);
        org.gnome.gio.Menu tor = new org.gnome.gio.Menu();
        tor.append(I18n.tr("Enable/Disable Tor"), "win.tor-enabled");
        tor.append(I18n.tr("New Tor Identity"), "win.tor-new-identity");
        edit.appendSubmenu(I18n.tr("Tor"), tor);
        edit.append(I18n.tr("Select All"), "win.select-all");
        edit.append(I18n.tr("Preferences"), "win.preferences");
        menu.appendSubmenu(I18n.tr("_Edit"), edit);

        // View
        org.gnome.gio.Menu view = new org.gnome.gio.Menu();
        view.append(I18n.tr("Left Panel"), "win.left-panel");
        view.append(I18n.tr("Info Panel"), "win.info-panel");
        org.gnome.gio.Menu columns = new org.gnome.gio.Menu();
        for (int i = 0; i < DOWNLOAD_COLUMN_LABELS.size(); i++) {
            columns.append(DOWNLOAD_COLUMN_LABELS.get(i), "win.col-" + i);
        }
        view.appendSubmenu(I18n.tr("Columns"), columns);
        menu.appendSubmenu(I18n.tr("_View"), view);

        // Download
        org.gnome.gio.Menu download = new org.gnome.gio.Menu();
        download.append(I18n.tr("Open"), "win.open-file");
        download.append(I18n.tr("Open Folder"), "win.open-folder");
        download.append(I18n.tr("Download Subtitles"), "win.download-subtitles");
        download.append(I18n.tr("Update Website Mirror…"), "win.update-website-mirror");
        download.append(I18n.tr("Open HTTrack Log"), "win.open-httrack-log");
        download.append(I18n.tr("Open HTTrack Error Log"), "win.open-httrack-error-log");
        download.append(I18n.tr("Force Download"), "win.force-download");
        download.append(I18n.tr("Pause All"), "win.pause-all");
        download.append(I18n.tr("Resume All"), "win.resume-all");
        download.append(I18n.tr("Delete"), "win.delete");
        download.append(I18n.tr("Delete with Files"), "win.delete-with-files");
        download.append(I18n.tr("Remove All Finished"), "win.remove-finished");
        download.append(I18n.tr("Properties"), "win.properties");
        menu.appendSubmenu(I18n.tr("_Download"), download);

        // Help
        org.gnome.gio.Menu help = new org.gnome.gio.Menu();
        help.append(I18n.tr("Statistics"), "win.statistics");
        help.append(I18n.tr("Donation"), "win.donation");
        help.append(I18n.tr("About"), "win.about");
        menu.appendSubmenu(I18n.tr("_Help"), help);

        return menu;
    }

    /** Registers all menu actions on the window (ApplicationWindow is an ActionMap). */
    private void registerMenuActions() {
        if (menuActionsRegistered) {
            return;
        }
        menuActionsRegistered = true;

        // File
        addAction("open", this::present);
        addAction("new-download", this::onAddClicked);
        addAction("new-media", this::onNewMediaClicked);
        addAction("search-torrents", this::onSearchTorrents);
        addAction("scrape", this::onScraperClicked);
        addAction("import-sequence", () -> new ImportSequenceDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh), torService).present());
        addAction("import-file", () -> ImportListDialog.chooseAndPresent(window, downloadManager,
                () -> UiThread.marshal(this::refresh), torService));
        addAction("import-html", this::onImportHtml);
        addAction("import-remote-html", this::onImportRemoteHtml);
        addAction("export-file", this::onExportList);
        addStatefulAction("offline",
                downloadManager.getGlobalSettings().getBooleanProperty("ui.offline", false),
                active -> trackActivity(offlineModeController.setOffline(active))
                        .whenComplete((ignored, failure) -> UiThread.marshal(this::refresh)));
        addAction("quit", this::requestExitFromMenu);

        // Edit
        addAction("select-all", this::selectAllDownloads);
        addStatefulAction("clipboard-monitoring", downloadManager.isClipboardMonitoringEnabled(),
                downloadManager::setClipboardMonitoringEnabled);
        addStatefulAction("clipboard-silent",
                downloadManager.getGlobalSettings().getBooleanProperty("ui.clipboardSilent", false),
                active -> {
                    downloadManager.getGlobalSettings().setProperty("ui.clipboardSilent",
                            String.valueOf(active));
                    downloadManager.getGlobalSettings().save();
                    applyClipboardSilentToCore(active);
                });
        java.util.Set<String> selectedCompletionActions = completionActionKeys();
        for (String key : COMPLETION_ACTION_KEYS) {
            addStatefulAction("completion-" + key, selectedCompletionActions.contains(key),
                    active -> onCompletionActionToggled(key, active));
        }
        installCompletionActions();
        addRadioAction("schedule",
                downloadManager.getGlobalSettings().getProperty("scheduler.preset", "always"),
                this::applySchedulePreset);
        addAction("preferences", this::onSettingsClicked);
        addStatefulAction("tor-enabled", torDesiredRunning.get(), this::onTorToggled);
        addAction("tor-new-identity", this::onTorNewIdentity);

        // View
        addStatefulAction("left-panel", true, leftPanelWidget::setVisible);
        addStatefulAction("info-panel", true, infoPanelWidget::setVisible);
        var columns = downloadsTreeview.getColumns();
        for (int i = 0; i < DOWNLOAD_COLUMN_LABELS.size(); i++) {
            // Bind the action to the column itself, independently of its dragged position.
            var column = columns.get(i);
            addStatefulAction("col-" + i, column.getVisible(), column::setVisible);
        }

        // Download
        addAction("open-file", () -> openSelected("file"));
        addAction("open-folder", () -> openSelected("folder"));
        addAction("download-subtitles", this::downloadSubtitles);
        addAction("update-website-mirror", this::updateWebsiteMirror);
        addAction("open-httrack-log", () -> openHttrackDiagnostic(
                org.manager.download.HttrackMirrorSupport.DiagnosticLog.ACTIVITY));
        addAction("open-httrack-error-log", () -> openHttrackDiagnostic(
                org.manager.download.HttrackMirrorSupport.DiagnosticLog.ERRORS));
        addAction("force-download", this::startSelectedDownloads);
        addAction("pause-all", () -> runGlobalDownloadAction(downloadManager.pauseAllDownloads(), I18n.mark("Could not pause downloads: %s")));
        addAction("resume-all", () -> runGlobalDownloadAction(downloadManager.resumeAllDownloads(), I18n.mark("Could not resume downloads: %s")));
        setMenuActionEnabled("pause-all", false);
        setMenuActionEnabled("resume-all", false);
        addAction("delete", this::onDeleteClicked);
        addAction("delete-with-files", () -> {
            onDownloadSelectionChanged();
            if (!selectedDownloads.isEmpty()) {
                confirmDeleteWithFiles(selectedDownloads);
            }
        });
        addAction("remove-finished", () -> trackActivity(
                downloadManager.pruneCompletedDownloads(java.time.Duration.ZERO))
                .thenRun(() -> UiThread.marshal(this::refresh)));
        addAction("properties", this::onPropertiesClicked);

        // Help
        addAction("statistics", this::showStatistics);
        addAction("donation", () -> org.gnome.gtk.Gtk.showUri(window,
                "https://github.com/albilu/odm", 0));
        addAction("about", () -> AboutDialogPresenter.present(window));
        syncModeActions();
        updateSelectionActionSensitivity();
    }

    private void runGlobalDownloadAction(CompletableFuture<Void> operation, String description) {
        trackActivity(operation).whenComplete((ignored, failure) -> UiThread.marshal(() -> {
            refresh();
            if (failure != null) {
                LOGGER.warn("Global download action failed: {}", description, failure);
                AccessibilitySupport.status(infoLabel, I18n.format(description, UiErrors.message(failure)));
            }
        }));
    }

    private void syncModeActions() {
        boolean clipboard = downloadManager.isClipboardMonitoringEnabled();
        setBooleanActionState("clipboard-monitoring", clipboard);
        setBooleanActionState("clipboard-silent", downloadManager.getGlobalSettings()
                .getBooleanProperty("ui.clipboardSilent", false));
        boolean offline = downloadManager.getGlobalSettings().getBooleanProperty("ui.offline", false);
        setBooleanActionState("offline", offline);
        setMenuActionEnabled("clipboard-silent", clipboard);
        setMenuActionEnabled("resume-all", hasPausedDownloads && !offline);
    }

    private void setBooleanActionState(String name, boolean enabled) {
        var action = menuActions.get(name);
        if (action != null && action.getState().getBoolean() != enabled) {
            action.setState(org.gnome.glib.Variant.boolean_(enabled));
        }
    }

    private void observeTrayAction(org.gnome.gio.SimpleAction action) {
        action.onNotify("enabled", ignored -> publishTrayState());
        action.onNotify("state", ignored -> publishTrayState());
    }

    private void addAction(String name, Runnable handler) {
        org.gnome.gio.SimpleAction action = new org.gnome.gio.SimpleAction(name, null);
        action.onActivate(parameter -> handler.run());
        window.addAction(action);
        menuActions.put(name, action);
        observeTrayAction(action);
    }

    private void addStatefulAction(String name, boolean initial,
            java.util.function.Consumer<Boolean> onToggle) {
        org.gnome.gio.SimpleAction action = org.gnome.gio.SimpleAction.stateful(name, null,
                org.gnome.glib.Variant.boolean_(initial));
        action.onActivate(parameter -> {
            boolean newState = !action.getState().getBoolean();
            action.setState(org.gnome.glib.Variant.boolean_(newState));
            onToggle.accept(newState);
            syncModeActions();
        });
        window.addAction(action);
        menuActions.put(name, action);
        observeTrayAction(action);
    }

    private void addRadioAction(String name, String initial,
            java.util.function.Consumer<String> onChoice) {
        org.gnome.gio.SimpleAction action = org.gnome.gio.SimpleAction.stateful(name,
                new org.gnome.glib.VariantType("s"),
                org.gnome.glib.Variant.string(initial));
        action.onActivate(parameter -> {
            String choice = parameter != null ? parameter.dupString(new org.javagi.base.Out<>()) : initial;
            action.setState(org.gnome.glib.Variant.string(choice));
            onChoice.accept(choice);
        });
        window.addAction(action);
        menuActions.put(name, action);
    }

    private void updateSelectionActionSensitivity() {
        DownloadSelectionCapabilities capabilities = selectionCapabilities(selectedDownloads);
        setMenuActionEnabled("open-file", capabilities.openFile());
        setMenuActionEnabled("open-folder", capabilities.openFolder());
        setMenuActionEnabled("download-subtitles", capabilities.downloadSubtitles());
        setMenuActionEnabled("update-website-mirror", capabilities.updateMirror());
        setMenuActionEnabled("open-httrack-log", capabilities.openHttrackLog());
        setMenuActionEnabled("open-httrack-error-log", capabilities.openHttrackErrorLog());
        setMenuActionEnabled("force-download", capabilities.start());
        setMenuActionEnabled("delete", capabilities.delete());
        setMenuActionEnabled("delete-with-files", capabilities.deleteWithFiles());
        setMenuActionEnabled("properties", capabilities.properties());
        updateQueueButtonSensitivity(queueMovementCapabilities(
                selectedDownloads, queuedDownloadsForMovement()));
    }

    private void updateQueueButtonSensitivity(QueueMovementCapabilities capabilities) {
        moveUpButton.setSensitive(capabilities.up());
        moveTopButton.setSensitive(capabilities.top());
        moveDownButton.setSensitive(capabilities.down());
        moveBottomButton.setSensitive(capabilities.bottom());
    }

    private void setMenuActionEnabled(String name, boolean enabled) {
        org.gnome.gio.SimpleAction action = menuActions.get(name);
        if (action != null) {
            action.setEnabled(enabled);
        }
    }

    boolean menuActionEnabled(String name) {
        org.gnome.gio.SimpleAction action = menuActions.get(name);
        return action != null && action.getEnabled();
    }

    String menuActionParameterType(String name) {
        org.gnome.gio.SimpleAction action = menuActions.get(name);
        org.gnome.glib.VariantType type = action == null ? null : action.getParameterType();
        return type == null ? null : type.dupString();
    }

    /** Selected keys, with transparent migration from the old radio setting. */
    private java.util.Set<String> completionActionKeys() {
        String persisted = downloadManager.getGlobalSettings()
                .getProperty("ui.completionActions", null);
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        if (persisted == null) {
            String legacy = downloadManager.getGlobalSettings()
                    .getProperty("ui.completionAction", "none");
            if (COMPLETION_ACTION_KEYS.contains(legacy)) {
                selected.add(legacy);
            }
            return selected;
        }
        for (String key : persisted.split(",")) {
            String normalized = key.strip().toLowerCase(java.util.Locale.ROOT);
            if (COMPLETION_ACTION_KEYS.contains(normalized)) {
                selected.add(normalized);
            }
        }
        return selected;
    }

    private java.util.Set<String> completionActionKeysFromMenu() {
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        for (String key : COMPLETION_ACTION_KEYS) {
            org.gnome.gio.SimpleAction action = menuActions.get("completion-" + key);
            if (action != null && action.getState() != null
                    && action.getState().getBoolean()) {
                selected.add(key);
            }
        }
        return selected;
    }

    private void onCompletionActionToggled(String key, boolean active) {
        if ("custom".equals(key) && active
                && downloadManager.getGlobalSettings()
                        .getProperty("ui.completionCommand", "").isBlank()) {
            promptForCustomCommand();
            return;
        }
        persistAndInstallCompletionActions();
    }

    private void persistAndInstallCompletionActions() {
        java.util.Set<String> selected = completionActionKeysFromMenu();
        downloadManager.getGlobalSettings().setProperty(
                "ui.completionActions", String.join(",", selected));
        downloadManager.getGlobalSettings().save();
        setCompletionActions(CompletionActionPolicy.forChoices(
                selected, downloadManager.getGlobalSettings()));
    }

    private void installCompletionActions() {
        java.util.Set<String> selected = menuActionsRegistered
                ? completionActionKeysFromMenu() : completionActionKeys();
        setCompletionActions(CompletionActionPolicy.forChoices(
                selected, downloadManager.getGlobalSettings()));
    }

    private void setCompletionToggleState(String key, boolean active) {
        org.gnome.gio.SimpleAction action = menuActions.get("completion-" + key);
        if (action != null) {
            action.setState(org.gnome.glib.Variant.boolean_(active));
        }
    }

    /**
     * Asks for (or reuses) the custom completion command and installs the
     * {@link org.manager.download.action.ExecuteCommandAction}. The command
     * persists as {@code ui.completionCommand} so it survives restarts; the
     * startup rebuild (no user gesture) silently reuses the stored value.
     */
    private void promptForCustomCommand() {
        String saved = downloadManager.getGlobalSettings().getProperty("ui.completionCommand", "");
        if (saved.isBlank()) {
            org.gnome.gtk.GtkBuilder builder = UiLoader.load("/ui/completion-command.ui");
            org.gnome.gtk.Window prompt = Widgets.require(builder,
                    "completion_command_dialog", org.gnome.gtk.Window.class);
            DialogSupport.configureIndependent(prompt, window);
            org.gnome.gtk.Entry entry = Widgets.require(builder,
                    "completion_command_entry", org.gnome.gtk.Entry.class);
            org.gnome.gtk.Button cancel = Widgets.require(builder,
                    "completion_command_cancel_button", org.gnome.gtk.Button.class);
            org.gnome.gtk.Button ok = Widgets.require(builder,
                    "completion_command_save_button", org.gnome.gtk.Button.class);
            AccessibilitySupport.label(entry, I18n.tr("Custom completion command"));
            java.util.concurrent.atomic.AtomicBoolean committed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            Runnable apply = () -> {
                String command = entry.getText().strip();
                if (!command.isBlank()) {
                    committed.set(true);
                    downloadManager.getGlobalSettings().setProperty("ui.completionCommand", command);
                    persistAndInstallCompletionActions();
                }
                prompt.close();
            };
            prompt.onCloseRequest(() -> {
                if (!committed.get()) {
                    setCompletionToggleState("custom", false);
                    persistAndInstallCompletionActions();
                }
                return false;
            });
            cancel.onClicked(() -> prompt.close());
            ok.onClicked(apply::run);
            entry.onActivate(apply::run);
            prompt.present();
        } else {
            persistAndInstallCompletionActions();
        }
    }

    /** Opens the selected file, or reveals it in its containing folder. */
    private void openSelected(String what) {
        onDownloadSelectionChanged();
        if (selectedDownload == null || selectedDownload.getDestination() == null) {
            return;
        }
        Path target = selectedDownload.getPrimaryOutputPath();
        Path destination = selectedDownload.getDestination();
        if ("folder".equals(what)) {
            performFileManagerAction(() -> FileManagerSupport.reveal(target, destination),
                    I18n.tr("Could not open containing folder"));
        } else if (target != null) {
            performFileManagerAction(() -> FileManagerSupport.open(target),
                    I18n.tr("Could not open downloaded file"));
        }
    }

    /** Opens exactly the base destination displayed in the Information panel. */
    private void openDisplayedSaveFolder() {
        onDownloadSelectionChanged();
        Path destination = displayedSaveFolder(selectedDownload);
        if (destination != null) {
            performFileManagerAction(() -> FileManagerSupport.open(destination),
                    I18n.tr("Could not open save folder"));
        }
    }

    static Path displayedSaveFolder(Download download) {
        return download == null ? null : download.getDestination();
    }

    private Download downloadAt(TreePath path) {
        int[] indices = path == null ? null : path.getIndices();
        return indices == null || indices.length == 0
                ? null : listPresenter.rowAt(indices[0]);
    }

    private void activateDownload(Download download) {
        if (download == null || download.getDestination() == null) {
            return;
        }
        Path target = download.getPrimaryOutputPath();
        if (activationFor(download) == DownloadActivation.OPEN_FILE && target != null) {
            performFileManagerAction(() -> FileManagerSupport.open(target),
                    I18n.tr("Could not open downloaded file"));
        } else {
            performFileManagerAction(() -> FileManagerSupport.reveal(target,
                    download.getDestination()), I18n.tr("Could not open containing folder"));
        }
    }

    private void performFileManagerAction(java.util.function.Supplier<Boolean> operation,
            String failureMessage) {
        trackActivity(CompletableFuture.supplyAsync(operation, backgroundExecutor))
                .whenComplete((succeeded, error) -> {
                    if (error != null) {
                        LOGGER.error(failureMessage, error);
                    }
                    if (error != null || !Boolean.TRUE.equals(succeeded)) {
                        UiThread.marshal(() -> AccessibilitySupport.status(infoLabel,
                                failureMessage,
                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH));
                    }
                });
    }

    static DownloadActivation activationFor(Download download) {
        return download != null && download.getStatus() == Download.Status.COMPLETED
                ? DownloadActivation.OPEN_FILE
                : DownloadActivation.REVEAL_IN_FOLDER;
    }

    private void revealDetailFile(TreePath path) {
        if (path == null || selectedDownload == null) {
            return;
        }
        TreeIter iter = new TreeIter();
        if (!filesStore.getIter(iter, path)) {
            return;
        }
        if (TreeStoreCells.getBoolean(filesStore, iter, FileTreeSupport.FOLDER_COLUMN)) {
            if (filesTreeview.rowExpanded(path)) {
                filesTreeview.collapseRow(path);
            } else {
                filesTreeview.expandRow(path, false);
            }
            return;
        }
        Path file = FileManagerSupport.resolveDetailPath(
                selectedDownload.getDestination(),
                TreeStoreCells.getString(filesStore, iter,
                        DetailTabsPresenter.FILE_PATH_COLUMN));
        if (file != null) {
            performFileManagerAction(() -> FileManagerSupport.reveal(file,
                    file.getParent()), I18n.tr("Could not reveal downloaded file"));
        }
    }

    /** Opens persisted process/action output only for finalizing action rows. */
    private void showCompletionActionOutput(TreePath path) {
        if (path == null) {
            return;
        }
        TreeIter iter = new TreeIter();
        if (!completionDetailsStore.getIter(iter, path)
                || !ListStoreCells.getBoolean(completionDetailsStore, iter,
                        DetailTabsPresenter.ACTION_EXPOSES_OUTPUT_COLUMN)) {
            return;
        }
        ActionOutputDialog.present(window,
                ListStoreCells.getString(completionDetailsStore, iter, 0),
                ListStoreCells.getString(completionDetailsStore, iter, 1),
                ListStoreCells.getString(completionDetailsStore, iter, 2),
                ListStoreCells.getString(completionDetailsStore, iter,
                        DetailTabsPresenter.ACTION_OUTPUT_COLUMN));
    }

    /** Imports links found in a local HTML file. */
    private void onImportHtml() {
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        DialogSupport.configureIndependent(dialog);
        dialog.setTitle(I18n.tr("Select HTML file"));
        dialog.open(window, null, result -> {
            try {
                org.gnome.gio.File file = dialog.openFinish(result);
                if (file == null || file.getPath() == null) {
                    return;
                }
                java.nio.file.Path path = java.nio.file.Path.of(file.getPath().toString());
                ImportLimits importLimits = ImportLimits.from(
                        downloadManager.getGlobalSettings());
                // Bounded file I/O and HTML parsing run off the GTK main loop;
                // only the result goes back to the UI.
                trackActivity(CompletableFuture.supplyAsync(
                        () -> HtmlImportExport.readHtmlLinks(path, importLimits),
                        backgroundExecutor)).whenComplete((links, error) ->
                                UiThread.marshal(() -> {
                                    if (error != null) {
                                        AccessibilitySupport.status(infoLabel,
                                                I18n.format("Could not read links from this HTML file: %s", UiErrors.message(error)),
                                                org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                                        return;
                                    }
                                    ImportListDialog.presentHtmlUrls(window, downloadManager,
                                            () -> UiThread.marshal(this::refresh), links,
                                            importLimits, torService);
                                }));
            } catch (Exception e) {
                LOGGER.debug("HTML import cancelled or failed", e);
            }
        });
    }

    /** Fetches a remote HTML page and imports the links found in it. */
    private void onImportRemoteHtml() {
        org.gnome.gtk.GtkBuilder builder = UiLoader.load("/ui/import-remote.ui");
        org.gnome.gtk.Window prompt = Widgets.require(builder,
                "remote_import_dialog", org.gnome.gtk.Window.class);
        DialogSupport.configureIndependent(prompt, window);

        ImportLimits displayedLimits = ImportLimits.from(downloadManager.getGlobalSettings());
        org.gnome.gtk.Label help = Widgets.require(builder,
                "remote_import_help_label", org.gnome.gtk.Label.class);
        help.setLabel(I18n.format("Enter an HTTP(S) page. Up to %d links are imported from a page up to %d MiB; relative links use the page's final address after redirects.",
                displayedLimits.maxUrls(), displayedLimits.maxSourceSizeMiB()));
        org.gnome.gtk.Entry sourceEntry = Widgets.require(builder,
                "remote_import_url_entry", org.gnome.gtk.Entry.class);
        AccessibilitySupport.label(sourceEntry, I18n.tr("Remote HTML page URL"));
        org.gnome.gtk.Label status = Widgets.require(builder,
                "remote_import_status_label", org.gnome.gtk.Label.class);
        org.gnome.gtk.Button cancel = Widgets.require(builder,
                "remote_import_cancel_button", org.gnome.gtk.Button.class);
        org.gnome.gtk.Button importButton = Widgets.require(builder,
                "remote_import_start_button", org.gnome.gtk.Button.class);
        Runnable startImport = () -> {
            org.manager.GlobalSettings settings = downloadManager.getGlobalSettings();
            if (settings.getBooleanProperty("ui.offline", false)) {
                AccessibilitySupport.status(status,
                        I18n.tr("Remote import is unavailable while Offline Mode is enabled"),
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                return;
            }
            final java.net.URI source;
            try {
                source = DownloadUrlPolicy.require(
                        sourceEntry.getText().strip()).requireWeb().uri();
            } catch (Exception invalid) {
                AccessibilitySupport.status(status, I18n.tr("Enter a valid HTTP(S) page URL"),
                        org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                return;
            }
            String proxy = settings.isGlobalProxyEnabled()
                    ? settings.getGlobalProxyAddress() : null;
            ImportLimits importLimits = ImportLimits.from(settings);
            boolean verifyHttpsCertificates = settings.isVerifyHttpsCertificates();
            importButton.setSensitive(false);
            sourceEntry.setSensitive(false);
            AccessibilitySupport.status(status, I18n.tr("Fetching page and importing links…"));
            trackActivity(CompletableFuture.supplyAsync(() -> HtmlImportExport
                    .fetchRemoteHtmlLinks(source, proxy, importLimits, verifyHttpsCertificates), backgroundExecutor))
                    .whenComplete((links, error) -> UiThread.marshal(() -> {
                        if (error != null) {
                            LOGGER.warn("Remote HTML import failed: {}",
                                    org.manager.tools.ProcessDiagnostics.sanitize(UiErrors.message(error)));
                            importButton.setSensitive(true);
                            sourceEntry.setSensitive(true);
                            AccessibilitySupport.status(status,
                                    I18n.format("Could not import this remote HTML page: %s", UiErrors.message(error)),
                                    org.gnome.gtk.AccessibleAnnouncementPriority.HIGH);
                            return;
                        }
                        prompt.close();
                        ImportListDialog.presentHtmlUrls(window, downloadManager,
                                () -> UiThread.marshal(this::refresh), links,
                                importLimits, torService);
                    }));
        };
        cancel.onClicked(prompt::close);
        importButton.onClicked(startImport::run);
        sourceEntry.onActivate(startImport::run);

        prompt.present();
        ClipboardUrlPrefill.populate(prompt, sourceEntry,
                ClipboardUrlPrefill::isWebPage);
        sourceEntry.grabFocus();
    }

    /** Exports all download URLs to a text file. */
    private void onExportList() {
        org.gnome.gtk.FileDialog dialog = new org.gnome.gtk.FileDialog();
        DialogSupport.configureIndependent(dialog);
        dialog.setTitle(I18n.tr("Export download list"));
        dialog.setInitialName("odm-downloads.txt");
        dialog.save(window, null, result -> {
            try {
                org.gnome.gio.File file = dialog.saveFinish(result);
                if (file == null || file.getPath() == null) {
                    return;
                }
                java.nio.file.Path path = java.nio.file.Path.of(file.getPath().toString());
                // Snapshot the URL list on the GTK thread (model access),
                // write the file off it
                String contents = HtmlImportExport.exportText(downloadManager.getAllDownloads());
                trackActivity(CompletableFuture.runAsync(() -> {
                    try {
                        HtmlImportExport.writeText(path, contents);
                        UiThread.marshal(() -> AccessibilitySupport.status(
                                infoLabel, I18n.tr("Exported download list")));
                    } catch (Exception e) {
                        LOGGER.debug("Export failed", e);
                    }
                }, backgroundExecutor));
            } catch (Exception e) {
                LOGGER.debug("Export cancelled or failed", e);
            }
        });
    }

    /** Shows a small statistics dialog (counts by status, total sizes). */
    private void showStatistics() {
        StatisticsPresenter.Stats st = StatisticsPresenter.aggregate(downloadManager.getAllDownloads());
        org.gnome.gtk.MessageDialog stats = new org.gnome.gtk.MessageDialog();
        DialogSupport.configureIndependent(stats, window);
        stats.setMarkup(I18n.tr("<b>Download Statistics</b>"));
        stats.formatSecondaryText(I18n.format("Total: %d\nActive: %d\nQueued/paused: %d\nFinished: %d\nErrors: %d\n\nDownloaded: %s / %s",
                st.total(), st.active(), st.queued(), st.finished(), st.errors(),
                DownloadFormats.size(st.doneSize()), DownloadFormats.size(st.totalSize())));
        stats.present();
    }

    private void applySchedulePreset(String preset) {
        if ("none".equalsIgnoreCase(preset)) {
            downloadManager.getGlobalSettings().setProperty("scheduler.preset", "none");
            downloadManager.getGlobalSettings().save();
            LOGGER.info("Schedule preset cleared; the custom hour grid remains active");
            return;
        }
        boolean[][] hourGrid = org.manager.schedule.ScheduleManager.hourGridForPreset(preset);
        scheduleManager.setGlobalHourGrid(hourGrid);
        downloadManager.getGlobalSettings().setProperty("scheduler.preset", preset);
        // Presets are shortcuts for the same grid shown in Advanced settings,
        // so the persisted and running policy always match that visual state.
        downloadManager.getGlobalSettings().setProperty("scheduler.grid",
                org.manager.schedule.WeeklySchedule.hourGridToString(hourGrid));
        downloadManager.getGlobalSettings().setProperty("scheduler.enabled", "true");
        downloadManager.getGlobalSettings().save();
        trackActivity(scheduleManager.start()).whenComplete((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Failed to start scheduler for preset " + preset, error);
                UiThread.marshal(() -> AccessibilitySupport.status(
                        infoLabel, I18n.tr("Could not start download scheduler")));
            } else {
                LOGGER.info("Schedule preset applied: " + preset);
            }
        });
    }

    private void syncScheduleActionState() {
        org.gnome.gio.SimpleAction action = menuActions.get("schedule");
        if (action != null) {
            String preset = downloadManager.getGlobalSettings()
                    .getProperty("scheduler.preset", "none");
            action.setState(org.gnome.glib.Variant.string(preset));
        }
    }

    private void setCompletionActions(
            java.util.List<org.manager.download.action.AfterCompletionAction> actions) {
        downloadManager.setGlobalAfterCompletionActions(actions);
        LOGGER.info("After-completion actions set to: "
                + actions.stream().map(action -> action.getType().name()).toList());
    }

    private void onTorToggled(boolean active) {
        clearTorVerificationDisplay();
        long epoch = torToggleEpoch.incrementAndGet();
        torDesiredRunning.set(active);
        if (active) {
            torCircuitMonitor.resume();
        } else {
            torCircuitMonitor.suspend();
        }
        syncTorPresentation();
        setTorTransitionStatus(active
                ? torBootstrapStatus(torService.getBootstrapProgress())
                : I18n.tr("Stopping Tor service…"));
        trackActivity(torServiceController.setEnabled(active)).whenComplete((running, error) -> {
            if (epoch != torToggleEpoch.get()) {
                return;
            }
            if (active && Boolean.TRUE.equals(running)) {
                LOGGER.info("Tor service started; saved download routes retained");
                UiThread.marshal(() -> {
                    if (epoch == torToggleEpoch.get()) {
                        torDesiredRunning.set(true);
                        syncTorPresentation();
                        finishTorTransition(I18n.tr("Tor service started"));
                        torCircuitMonitor.checkNow();
                    }
                });
            } else if (active) {
                LOGGER.warn("Tor failed to start" + (error != null ? ": " + error.getMessage() : ""));
                torDesiredRunning.set(false);
                UiThread.marshal(() -> {
                    if (epoch == torToggleEpoch.get()) {
                        syncTorPresentation();
                        finishTorTransition(I18n.tr("Tor service failed to start"));
                    }
                });
            } else if (error != null) {
                LOGGER.warn("Tor service shutdown failed", error);
                torDesiredRunning.set(torService.isRunning());
                UiThread.marshal(() -> {
                    if (epoch == torToggleEpoch.get()) {
                        syncTorPresentation();
                        finishTorTransition(I18n.tr("Tor service could not be stopped"));
                    }
                });
            } else {
                LOGGER.info("Tor service stopped; Tor downloads are paused");
                UiThread.marshal(() -> {
                    if (epoch == torToggleEpoch.get()) {
                        syncTorPresentation();
                        finishTorTransition(I18n.tr("Tor service stopped"));
                    }
                });
            }
        });
    }

    /** Applies service events even while the window is hidden in the tray. */
    private void onTorServiceEvent(org.tor.TorService.TorServiceEvent event) {
        if (event == org.tor.TorService.TorServiceEvent.STARTED && torService.isRunning()) {
            torDesiredRunning.set(true);
        } else if (event == org.tor.TorService.TorServiceEvent.STOPPED
                && !torService.isRunning() && !torService.isStarting()) {
            torDesiredRunning.set(false);
        }
        syncTorPresentation();

        String message = switch (event) {
            case BOOTSTRAP_PROGRESS, BOOTSTRAP_COMPLETE ->
                    torDesiredRunning.get()
                            && (torService.isStarting() || torService.isRunning())
                            ? torBootstrapStatus(torService.getBootstrapProgress()) : null;
            case STARTED -> I18n.tr("Tor service started");
            case STOPPED -> I18n.tr("Tor service stopped");
            case ERROR -> I18n.tr("Tor service reported an error");
        };
        if (message != null) {
            if (event == org.tor.TorService.TorServiceEvent.BOOTSTRAP_PROGRESS
                    || event == org.tor.TorService.TorServiceEvent.BOOTSTRAP_COMPLETE) {
                setTorTransitionStatus(message);
            } else {
                finishTorTransition(message);
            }
        }
    }

    private void setTorTransitionStatus(String message) {
        torTransitionStatus = message;
        AccessibilitySupport.status(infoLabel, message);
    }

    private void finishTorTransition(String message) {
        torTransitionStatus = null;
        AccessibilitySupport.status(infoLabel, message);
    }

    private static synchronized void installStatusBarCss() {
        var display = org.gnome.gdk.Display.getDefault();
        if (statusBarCssProvider != null || display == null) {
            return;
        }
        var provider = new org.gnome.gtk.CssProvider();
        provider.loadFromString("""
                button.odm-status-button {
                    min-width: 0;
                    min-height: 0;
                    padding: 0;
                }
                """);
        org.gnome.gtk.Gtk.styleContextAddProviderForDisplay(display, provider,
                org.gnome.gtk.Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        statusBarCssProvider = provider;
    }

    private void syncTorPresentation() {
        var toggle = menuActions.get("tor-enabled");
        if (toggle != null) {
            toggle.setState(org.gnome.glib.Variant.boolean_(torDesiredRunning.get()));
        }
        boolean running = torService.isRunning();
        if (!running) {
            clearTorVerificationDisplay();
        }
        torCheckButton.setVisible(running);
        torCheckButton.setSensitive(running && torDesiredRunning.get() && !torIdentityRequestInFlight.get());
        setMenuActionEnabled("tor-new-identity", running && !torIdentityRequestInFlight.get());
    }

    static String torBootstrapStatus(int bootstrapProgress) {
        return I18n.format("Tor bootstrap: %d%%", normalizedTorProgress(bootstrapProgress));
    }

    private static int normalizedTorProgress(int progress) {
        return Math.max(0, Math.min(100, progress));
    }

    private void onTorCheckStarted(java.util.concurrent.CompletableFuture<org.tor.TorCircuitMonitor.Result> check) {
        UiThread.marshal(() -> {
            if (finalExitStarted || !torService.isRunning() || !torDesiredRunning.get()
                    || (check.isDone() && check.getNow(null) == null)) {
                return;
            }
            torStatusCheck = check;
            AccessibilitySupport.status(infoLabel, I18n.tr("Verifying Tor connection…"));
            trackActivity(check).whenComplete((result, failure) -> UiThread.marshal(() -> {
                if (finalExitStarted || torStatusCheck != check) {
                    return;
                }
                torStatusCheck = null;
                updateDownloadListStatus();
                if (!torCircuitMonitor.isCurrent(result)) {
                    return;
                }
                String message = torCheckStatus(result);
                if (result.secure()) {
                    torIpLabel.setVisible(true);
                    AccessibilitySupport.status(torIpLabel, torExitAddress(result));
                } else {
                    clearTorVerificationDisplay();
                    if (result.offlineEnabled()) {
                        var offlineAction = menuActions.get("offline");
                        offlineAction.setState(org.gnome.glib.Variant.boolean_(
                                downloadManager.getGlobalSettings().getBooleanProperty("ui.offline", false)));
                        notifyTorCheckFailure(message);
                        refresh();
                    }
                    AccessibilitySupport.status(infoLabel, message);
                }
                torCheckButton.setTooltipText(message);
            }));
        });
    }

    private void clearTorVerificationDisplay() {
        torStatusCheck = null;
        torIpLabel.setLabel("");
        torIpLabel.setVisible(false);
        torCheckButton.setTooltipText(I18n.tr("Verify Tor connection"));
    }

    static String torCheckStatus(org.tor.TorCircuitMonitor.Result result) {
        if (!result.secure()) {
            return result.offlineEnabled()
                    ? I18n.format("Tor check failed — Offline Mode enabled. %s", UiErrors.message(result.message()))
                    : I18n.format("Tor check failed — %s", UiErrors.message(result.message()));
        }
        return I18n.format("Tor verified — %s", torExitAddress(result));
    }

    private static String torExitAddress(org.tor.TorCircuitMonitor.Result result) {
        String country = result.countryCode();
        String flag = "";
        if (country != null && country.matches("[A-Za-z]{2}")) {
            country = country.toUpperCase(java.util.Locale.ROOT);
            flag = new String(Character.toChars(0x1F1E6 + country.charAt(0) - 'A'))
                    + new String(Character.toChars(0x1F1E6 + country.charAt(1) - 'A')) + " ";
        }
        return flag + result.ip();
    }

    private void notifyTorCheckFailure(String message) {
        LOGGER.warn(message);
        var application = window.getApplication();
        if (application != null) {
            TorFailureNotification.send(application, message)
                    .exceptionally(failure -> {
                        LOGGER.warn("Could not deliver Tor failure desktop notification", failure);
                        return null;
                    });
        }
    }

    /**
     * Requests a new Tor circuit (NEWNYM) through the control port. Wired to
     * the "New Tor Identity" menu action; no-op with an info-bar notice when
     * Tor is not running or the control port is unavailable.
     */
    private void onTorNewIdentity() {
        if (!torService.isRunning()) {
            AccessibilitySupport.status(infoLabel, I18n.tr("Tor is not running"));
            return;
        }
        if (!torIdentityRequestInFlight.compareAndSet(false, true)) {
            return;
        }
        org.tor.TorController controller;
        try {
            controller = torService.createController(5000);
        } catch (Exception e) {
            torIdentityRequestInFlight.set(false);
            LOGGER.warn("Could not configure the Tor control client", e);
            AccessibilitySupport.status(infoLabel,
                    I18n.tr("New Tor identity unavailable (invalid control configuration)"));
            return;
        }
        long activityStarted = System.nanoTime();
        long serviceEpoch = torToggleEpoch.get();
        clearTorVerificationDisplay();
        torCircuitMonitor.suspend();
        syncTorPresentation();
        setTorTransitionStatus(I18n.tr("Requesting new Tor identity…"));
        CompletableFuture<Boolean> identityChange = controller.connect()
                .thenCompose(connected -> connected
                        ? controller.changeIp()
                        : CompletableFuture.completedFuture(false))
                .thenCompose(changed -> Boolean.TRUE.equals(changed)
                        ? completeAfterMinimumActivity(changed, activityStarted)
                        : CompletableFuture.completedFuture(false));
        trackActivity(identityChange).whenCompleteAsync((changed, error) -> {
            String message;
            if (error != null) {
                LOGGER.warn("New Tor identity request failed", error);
                message = I18n.format("New Tor identity failed: %s", UiErrors.message(error));
            } else if (Boolean.TRUE.equals(changed)) {
                message = I18n.tr("New Tor identity requested; new connections use clean circuits");
            } else {
                message = I18n.tr("New Tor identity unavailable (Tor control request failed)");
            }
            LOGGER.info(message);
            try {
                controller.shutdown();
            } finally {
                torIdentityRequestInFlight.set(false);
                UiThread.marshal(() -> {
                    if (finalExitStarted) {
                        return;
                    }
                    if (serviceEpoch == torToggleEpoch.get() && torDesiredRunning.get()) {
                        torCircuitMonitor.resume();
                        if (torService.isRunning()) {
                            finishTorTransition(message);
                            if (error == null && Boolean.TRUE.equals(changed)) {
                                torCircuitMonitor.checkNow();
                            }
                        }
                    }
                    syncTorPresentation();
                });
            }
        });
    }

    private static <T> CompletableFuture<T> completeAfterMinimumActivity(
            T result, long startedAtNanos) {
        long elapsed = System.nanoTime() - startedAtNanos;
        long minimum = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                NEW_IDENTITY_MIN_ACTIVITY_MILLIS);
        long remaining = Math.max(0, minimum - elapsed);
        if (remaining == 0) {
            return CompletableFuture.completedFuture(result);
        }
        return CompletableFuture.supplyAsync(() -> result,
                CompletableFuture.delayedExecutor(
                        remaining, java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    /** Restores window geometry + paned positions persisted from the last session. */
    private void restoreWindowState(GtkBuilder builder) {
        var s = downloadManager.getGlobalSettings();
        int w = s.getIntProperty("ui.window.width", -1);
        int h = s.getIntProperty("ui.window.height", -1);
        if (w > 0 && h > 0) {
            window.setDefaultSize(w, h);
        }
        int mainPos = s.getIntProperty("ui.paned.mainPosition", -1);
        if (mainPos > 0) {
            Widgets.require(builder, "main_paned", org.gnome.gtk.Paned.class).setPosition(mainPos);
        }
        int contentPos = s.getIntProperty("ui.paned.contentPosition", -1);
        if (contentPos > 0) {
            Widgets.require(builder, "content_paned", org.gnome.gtk.Paned.class).setPosition(contentPos);
        }
    }

    /** Persists window geometry + paned positions for the next session. */
    private void saveWindowState(GtkBuilder builder) {
        var s = downloadManager.getGlobalSettings();
        s.setProperty("ui.window.width", String.valueOf(window.getWidth()));
        s.setProperty("ui.window.height", String.valueOf(window.getHeight()));
        s.setProperty("ui.paned.mainPosition",
                String.valueOf(Widgets.require(builder, "main_paned", org.gnome.gtk.Paned.class).getPosition()));
        s.setProperty("ui.paned.contentPosition",
                String.valueOf(Widgets.require(builder, "content_paned", org.gnome.gtk.Paned.class).getPosition()));
        s.save();
    }

    private void onScraperClicked() {
        new NewWebsiteDialog(window, downloadManager,
                () -> UiThread.marshal(this::refresh), torService).present();
    }

    private void onStatusSelectionChanged() {
        // Re-entrant guard: rebuildFilterStore re-selects the filter row,
        // which fires "changed" again — without the guard this recurses
        // infinitely (store clear + re-select + refresh loop).
        if (listPresenter.isRestoringSelection()) {
            return;
        }
        selectRow(statusTreeview.getSelection(), (path, index) -> {
            if (listPresenter.selectStatusFilterAt(index)) {
                refreshWithActivity();
            }
        });
    }

    private void onCategorySelectionChanged() {
        if (listPresenter.isRestoringSelection()) {
            return;
        }
        selectRow(categoryTreeview.getSelection(), (path, index) -> {
            if (listPresenter.selectCategoryAt(index)) {
                refreshWithActivity();
            }
        });
    }

    private void onDownloadSelectionChanged() {
        Out<TreeModel> model = new Out<>();
        org.gnome.glib.List<TreePath> paths =
                downloadsTreeview.getSelection().getSelectedRows(model);
        List<Integer> indexes = paths == null ? List.of() : paths.stream()
                .map(TreePath::getIndices)
                .filter(indices -> indices != null && indices.length > 0)
                .map(indices -> indices[0])
                .toList();
        selectedDownloads = listPresenter.rowsAt(indexes);
        selectedDownload = selectedDownloads.isEmpty()
                ? null : selectedDownloads.getFirst();
        updateSelectionActionSensitivity();
        updateDownloadListStatus();
        updateInfoPanel();
    }

    private void selectAllDownloads() {
        downloadsTreeview.getSelection().selectAll();
        // GtkTreeSelection normally emits changed synchronously, but updating
        // explicitly also keeps programmatic/action activation deterministic.
        onDownloadSelectionChanged();
    }

    private void updateDownloadListStatus() {
        infoLabel.setLabel(torTransitionStatus != null
                ? torTransitionStatus
                : torStatusCheck != null ? I18n.tr("Verifying Tor connection…") : downloadListStatusText(
                        selectedDownloads.size(), loadedHistoryCount, knownDownloadCount));
    }

    static String downloadListStatusText(int selectedCount, int loadedCount, int totalCount) {
        if (selectedCount > 0) {
            return I18n.plural("%d download selected", "%d downloads selected", selectedCount);
        }
        return loadedCount < totalCount
                ? I18n.plural("%2$d of %1$d download loaded", "%2$d of %1$d downloads loaded", totalCount, loadedCount)
                : I18n.plural("%d download", "%d downloads", totalCount);
    }

    SelectionMode downloadSelectionMode() {
        return downloadsTreeview.getSelection().getMode();
    }

    boolean downloadNameTooltipEnabled() {
        return downloadsTreeview.getHasTooltip();
    }

    org.gnome.gio.Icon torStatusIcon() {
        return torIcon.getGicon();
    }

    boolean torStatusIconVisible() {
        return torCheckButton.getVisible();
    }

    boolean torEnabledActionState() {
        return menuActions.get("tor-enabled").getState().getBoolean();
    }

    String statusMessage() {
        return infoLabel.getLabel();
    }

    boolean activitySpinning() {
        return activitySpinner.getSpinning();
    }

    void requestNewTorIdentity() {
        onTorNewIdentity();
    }

    PropagationPhase downloadContextClickPhase() {
        return downloadContextClick.getPropagationPhase();
    }

    static List<String> downloadColumnLabels() {
        return DOWNLOAD_COLUMN_LABELS;
    }

    int mainMenuTopLevelCount() {
        org.gnome.gio.MenuModel model = menuBar.getMenuModel();
        return model == null ? 0 : model.getNItems();
    }

    boolean mainMenuSubmenuContainsAction(String submenuLabel, String detailedAction) {
        org.gnome.gio.MenuModel model = menuBar.getMenuModel();
        if (model == null) {
            return false;
        }
        org.gnome.glib.VariantType stringType = new org.gnome.glib.VariantType("s");
        for (int index = 0; index < model.getNItems(); index++) {
            org.gnome.glib.Variant label = model.getItemAttributeValue(
                    index, "label", stringType);
            if (label != null && submenuLabel.equals(
                    label.dupString(new org.javagi.base.Out<>()))) {
                return menuContainsAction(model.getItemLink(index, "submenu"),
                        detailedAction, stringType);
            }
        }
        return false;
    }

    private static boolean menuContainsAction(org.gnome.gio.MenuModel model,
            String detailedAction, org.gnome.glib.VariantType stringType) {
        if (model == null) {
            return false;
        }
        for (int index = 0; index < model.getNItems(); index++) {
            org.gnome.glib.Variant action = model.getItemAttributeValue(
                    index, "action", stringType);
            if (action != null && detailedAction.equals(
                    action.dupString(new org.javagi.base.Out<>()))) {
                return true;
            }
            for (String linkName : List.of("section", "submenu")) {
                if (menuContainsAction(model.getItemLink(index, linkName),
                        detailedAction, stringType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void selectRow(TreeSelection selection, SelectionConsumer consumer) {
        TreeIter iter = new TreeIter();
        Out<TreeModel> model = new Out<>();
        if (selection.getSelected(model, iter)) {
            TreePath path = model.get().getPath(iter);
            int[] indices = path.getIndices();
            if (indices != null && indices.length > 0) {
                consumer.accept(path, indices[0]);
            }
        }
    }

    private interface SelectionConsumer {
        void accept(TreePath path, int index);
    }

    /**
     * Refreshes the download view and side-band labels from the current
     * repository state. GTK thread only.
     */
    private void refresh() {
        refresh(false);
    }

    /** Shows the main activity indicator for an explicit search/filter pass. */
    private void refreshWithActivity() {
        refresh(true);
    }

    private void refresh(boolean indicateActivity) {
        if (!refreshInFlight.compareAndSet(false, true)) {
            refreshAgain.set(true);
            if (indicateActivity) {
                refreshActivityRequested.set(true);
            }
            return;
        }
        boolean showActivity = indicateActivity;
        if (refreshActivityRequested.getAndSet(false)) {
            showActivity = true;
        }
        String selectedId = selectedDownload != null ? selectedDownload.getId() : null;
        int requestedHistoryLimit = historyFetchLimit;
        String requestedSearch = listPresenter.searchText();
        try {
            CompletableFuture<RefreshSnapshot> refreshFuture = CompletableFuture.supplyAsync(
                    () -> loadRefreshSnapshot(selectedId, requestedHistoryLimit, requestedSearch),
                    backgroundExecutor);
            if (showActivity) {
                trackActivity(refreshFuture);
            }
            refreshFuture
                .whenComplete((snapshot, error) -> UiThread.marshal(() -> {
                    try {
                        if (error != null) {
                            LOGGER.warn("Failed to refresh download list", error);
                            return;
                        }
                        applyRefresh(snapshot);
                    } finally {
                        refreshInFlight.set(false);
                        if (refreshAgain.getAndSet(false)) {
                            refresh(refreshActivityRequested.getAndSet(false));
                        }
                    }
                }));
        } catch (java.util.concurrent.RejectedExecutionException shuttingDown) {
            refreshInFlight.set(false);
        }
    }

    /** Loads bounded row slices and aggregates full-history filter counts off the GTK thread. */
    private RefreshSnapshot loadRefreshSnapshot(String selectedId, int requestedHistoryLimit,
            String searchText) {
        java.util.LinkedHashMap<String, Download> visible = new java.util.LinkedHashMap<>();
        List<Download> history = downloadManager.getDownloads(0, requestedHistoryLimit);
        addRefreshRows(visible, history);
        for (Download.Status status : ALWAYS_VISIBLE_STATUSES) {
            addRefreshRows(visible,
                    downloadManager.getDownloadsByStatus(status, 0, HISTORY_PAGE_SIZE));
        }
        if (selectedId != null) {
            Download selected = downloadManager.getDownload(selectedId);
            if (selected != null) {
                visible.putIfAbsent(selected.getId(), selected);
            }
        }
        java.util.EnumMap<Download.Status, Integer> statusCounts =
                new java.util.EnumMap<>(Download.Status.class);
        for (Download.Status status : Download.Status.values()) {
            statusCounts.put(status, downloadManager.getDownloadCountByStatus(status));
        }
        int totalCount = downloadManager.getDownloadCount();
        var filterCounts = totalCount > history.size()
                ? downloadManager.getDownloadCounts(download -> DownloadListPresenter.filterBucket(download, searchText))
                : DownloadListPresenter.computeFilterBuckets(history, searchText);
        return new RefreshSnapshot(new java.util.ArrayList<>(visible.values()),
                totalCount, history.size(), statusCounts, searchText, filterCounts);
    }

    private static void addRefreshRows(java.util.Map<String, Download> target,
            List<Download> rows) {
        if (rows == null) {
            return;
        }
        for (Download download : rows) {
            if (download != null) {
                target.putIfAbsent(download.getId(), download);
            }
        }
    }

    /** Applies an already-fetched repository snapshot on the GTK thread. */
    private void applyRefresh(RefreshSnapshot snapshot) {
        if (!snapshot.searchText().equals(listPresenter.searchText())) {
            // A newer search was entered while this background count was running.
            refreshAgain.set(true);
            return;
        }
        List<String> selectedIds = selectedDownloads.stream()
                .map(Download::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        loadedHistoryCount = snapshot.loadedHistoryCount();
        knownDownloadCount = snapshot.totalCount();
        DownloadListPresenter.RefreshSummary summary = listPresenter.refresh(
                snapshot.downloads(), snapshot.totalCount(), snapshot.filterCounts());
        if (summary.modelRebuilt()) {
            // Clearing GtkListStore clears GtkTreeSelection. Restore by stable
            // download id so queue moves and other structural refreshes keep
            // their selection at the record's new visible position.
            int restored = listPresenter.restoreSelection(
                    downloadsTreeview.getSelection(), selectedIds);
            if (restored == 0) {
                selectedDownload = null;
                selectedDownloads = List.of();
                updateSelectionActionSensitivity();
            } else {
                onDownloadSelectionChanged();
            }
        }
        java.util.Map<Download.Status, Integer> counts = snapshot.statusCounts();
        int activeCount = counts.getOrDefault(Download.Status.STARTING, 0)
                + counts.getOrDefault(Download.Status.CONNECTING, 0)
                + counts.getOrDefault(Download.Status.DOWNLOADING, 0)
                + counts.getOrDefault(Download.Status.SEEDING, 0);
        setMenuActionEnabled("pause-all", activeCount > 0);
        hasPausedDownloads = counts.getOrDefault(Download.Status.PAUSED, 0) > 0;
        syncModeActions();
        setMenuActionEnabled("remove-finished",
                counts.getOrDefault(Download.Status.COMPLETED, 0) > 0);
        // Status changes are normally in-place row updates, so selection
        // signals do not fire. Re-evaluate Download-menu actions explicitly.
        updateSelectionActionSensitivity();
        updateDownloadListStatus();
        downSpeedLabel.setLabel(DownloadFormats.rate(summary.downBytesPerSec()));
        upSpeedLabel.setLabel(DownloadFormats.rate(summary.upBytesPerSec()));
        dhtStatusLabel.setLabel(summary.totalSeeders() > 0
                ? I18n.plural("DHT: %d seed", "DHT: %d seeds", summary.totalSeeders()) : "DHT: —");
        updateInfoPanel();
        // Re-evaluate after GTK has laid out appended rows. This also keeps
        // loading while a restrictive filter leaves the viewport under-filled.
        UiThread.marshal(this::loadNextHistoryPageIfNeeded);
    }

    private void loadNextHistoryPageIfNeeded() {
        if (loadedHistoryCount >= knownDownloadCount
                || historyFetchLimit > loadedHistoryCount
                || !isNearScrollBottom(downloadScrollAdjustment.getValue(),
                        downloadScrollAdjustment.getPageSize(),
                        downloadScrollAdjustment.getUpper())) {
            return;
        }
        historyFetchLimit = nextHistoryFetchLimit(historyFetchLimit, knownDownloadCount);
        refreshWithActivity();
    }

    static boolean isNearScrollBottom(double value, double pageSize, double upper) {
        return upper <= pageSize || value + pageSize >= upper - 64.0;
    }

    static int nextHistoryFetchLimit(int currentLimit, int totalCount) {
        if (totalCount <= 0) {
            return HISTORY_PAGE_SIZE;
        }
        if (currentLimit < HISTORY_PAGE_SIZE) {
            return Math.min(totalCount, HISTORY_PAGE_SIZE);
        }
        return Math.min(totalCount, currentLimit + HISTORY_PAGE_SIZE);
    }

    /**
     * Handles a torrent-file checkbox toggle in the Files tab: flips the row
     * and pushes the selected file indexes to aria2 via the
     * {@code select-file} option. Active torrents are paused around the
     * change because aria2 applies select-file reliably only while paused.
     * aria2 offers skip/include only — no per-file priority.
     */
    private void onFileSelectionToggled(String pathStr) {
        Download download = selectedDownload;
        if (download == null || !(download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings)) {
            return;
        }

        if (!FileTreeSupport.toggleSelection(filesStore, pathStr)) {
            return;
        }

        // Recursively collect leaf indexes; folder rows carry no aria2 index.
        java.util.List<Integer> selectedIndexes = FileTreeSupport.selectedIndexes(filesStore);
        if (selectedIndexes.isEmpty()) {
            LOGGER.warn("Refusing to deselect every file of " + download.getName());
            FileTreeSupport.toggleSelection(filesStore, pathStr);
            return;
        }

        String selectFile = selectedIndexes.stream().map(String::valueOf)
                .reduce((a, b) -> a + "," + b).orElse("");
        aria2Settings.setOption("select-file", selectFile);

        boolean wasActive = (download.getStatus() == Download.Status.DOWNLOADING
                || download.getStatus() == Download.Status.SEEDING)
                && download.getGid() != null;
        // The pause/change/resume chain performs aria2 RPC round trips; run
        // it off the GTK thread instead of blocking the main loop on join().
        CompletableFuture<Void> update = CompletableFuture.runAsync(() -> {
            if (wasActive) {
                downloadManager.pauseDownload(download).join();
            }
        }, backgroundExecutor)
                .thenCompose(v -> downloadManager.changeSettings(download))
                .handle((v, e) -> {
                    if (e != null) {
                        LOGGER.warn(
                                "Failed to apply file selection to " + download.getName(), e);
                    }
                    return null;
                })
                .thenCompose(v -> wasActive
                        ? downloadManager.resumeDownload(download)
                        : CompletableFuture.completedFuture(null));
        trackActivity(update);
    }

    /** Applies the same persisted file-priority metadata used by New Download. */
    private void onFilePriorityChanged(String path, String priority) {
        applyFilePriority(filesStore, selectedDownload, path, priority);
    }

    static boolean applyFilePriority(TreeStore store, Download download,
            String path, String priority) {
        if (store == null || download == null
                || !(download.getSettings() instanceof org.aria2.Aria2Settings aria2Settings)) {
            return false;
        }
        if (!FileTreeSupport.setPriority(store, path, priority)) {
            return false;
        }
        aria2Settings.setFilePriorities(FileTreeSupport.priorities(store));
        return true;
    }

    private void updateInfoPanel() {
        sourcesPresenter.load();
        infoProgressGraph.update(selectedDownload);
        String error = selectedDownload == null ? null : selectedDownload.getErrorMessage();
        boolean hasError = error != null && !error.isBlank();
        errorValue.setLabel(hasError ? UiErrors.message(error) : "—");
        errorDetailsButton.setSensitive(hasError);
        if (selectedDownload == null) {
            addedOnValue.setLabel("—");
            infoHashValue.setLabel("—");
            folderValue.setLabel("—");
            updateFolderLinkUri(null);
            folderOpenButton.setSensitive(false);
            engineIcon.clear();
            engineValue.setLabel("—");
            etaValue.setLabel("—");
            downloadedValue.setLabel("—");
            connectionsValue.setLabel("—");
            seedsPeersValue.setLabel("—");
            detailTabsPresenter.load();
            return;
        }
        addedOnValue.setLabel(selectedDownload.getCreatedAt() != null
                ? DownloadFormats.DATE_FORMAT.format(selectedDownload.getCreatedAt()) : "—");
        infoHashValue.setLabel(selectedDownload.getInfoHash() != null ? selectedDownload.getInfoHash() : "—");
        Path saveFolder = displayedSaveFolder(selectedDownload);
        folderValue.setLabel(saveFolder != null ? saveFolder.toString() : "—");
        updateFolderLinkUri(saveFolder);
        folderOpenButton.setSensitive(saveFolder != null);
        engineIcon.setFromGicon(DownloadEnginePresentation.icon(selectedDownload.getType()));
        engineValue.setLabel(DownloadEnginePresentation.displayName(selectedDownload.getType()));
        etaValue.setLabel(DownloadFormats.eta(selectedDownload));
        downloadedValue.setLabel(DownloadFormats.downloadedSize(selectedDownload));
        connectionsValue.setLabel(String.valueOf(selectedDownload.getConnectionCount()));
        seedsPeersValue.setLabel(selectedDownload.getSeeders() > 0
                ? I18n.plural("%d seed", "%d seeds", selectedDownload.getSeeders())
                : "—");
        detailTabsPresenter.load();
    }

    private void updateFolderLinkUri(Path saveFolder) {
        String uri = saveFolder == null ? "file:///" : saveFolder.toUri().toASCIIString();
        if (!uri.equals(folderOpenButton.getUri())) {
            folderOpenButton.setUri(uri);
        }
    }

}
