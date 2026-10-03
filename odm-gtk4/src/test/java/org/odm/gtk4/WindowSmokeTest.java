package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.gnome.gdk.Rectangle;
import org.gnome.glib.MainContext;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Align;
import org.gnome.gtk.ApplicationWindow;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CellRendererProgress;
import org.gnome.gtk.CellRendererCombo;
import org.gnome.gtk.CellRendererText;
import org.gnome.gtk.CheckButton;
import org.gnome.gtk.DropDown;
import org.gnome.gtk.DrawingArea;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.Frame;
import org.gnome.gtk.Grid;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Image;
import org.gnome.gtk.IconTheme;
import org.gnome.gtk.Label;
import org.gnome.gtk.LinkButton;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Notebook;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Paned;
import org.gnome.gtk.PositionType;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ProgressBar;
import org.gnome.gtk.PopoverMenuBar;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SpinButton;
import org.gnome.gtk.Spinner;
import org.gnome.gtk.SortType;
import org.gnome.gtk.StringList;
import org.gnome.gtk.Switch;
import org.gnome.gtk.TextView;
import org.gnome.gtk.TreeView;
import org.gnome.gtk.TreeViewColumn;
import org.gnome.gtk.TreeViewColumnSizing;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeSortable;
import org.gnome.gtk.TreeStore;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.javagi.base.Out;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Builds every window's .ui through the strict Widgets.require path for all
 * ids the code uses. A missing or mistyped widget id fails this test — the
 * "25 dead settings widgets" class of bug cannot compile through here.
 * Requires a display (run under Xvfb, as the Docker test env does).
 */
class WindowSmokeTest {

    @BeforeAll
    static void initGtk() throws ClassNotFoundException {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        // Own the context until this test JVM exits, including between event
        // iterations. Otherwise Java-GI can destroy GTK objects on its Cleaner thread.
        assertTrue(MainContext.default_().acquire(), "Could not own the GTK main context");
        Gtk.init();
    }

    @Test
    @DisplayName("Background native callbacks wait for the GTK thread")
    void backgroundMainContextCallbacksStayOnGtkThread() throws Exception {
        MainContext context = MainContext.default_();
        Thread gtkThread = Thread.currentThread();
        AtomicReference<Thread> callbackThread = new AtomicReference<>();

        // Java-GI's Cleaner uses this same native dispatch path.
        CompletableFuture.runAsync(() -> context.invoke(() -> {
            callbackThread.set(Thread.currentThread());
            return false;
        })).get(5, TimeUnit.SECONDS);

        assertNull(callbackThread.get(),
                "native cleanup must wait for the GTK thread to iterate its context");
        while (context.pending()) {
            context.iteration(false);
        }
        assertSame(gtkThread, callbackThread.get(),
                "native GTK callbacks must not execute on a background thread");
    }

    @Test
    @DisplayName("Enumerating builder objects preserves their native ownership")
    void builderEnumerationPreservesNativeOwnership() {
        GtkBuilder builder = GtkBuilder.fromString("""
                <interface>
                  <object class="GtkTextBuffer" id="buffer"/>
                </interface>
                """, -1);
        var objects = UiLoader.objects(builder);
        var address = objects.handle().reinterpret(ValueLayout.ADDRESS.byteSize())
                .get(ValueLayout.ADDRESS, 0);
        var nativeObject = address.reinterpret(GObject.getMemoryLayout().byteSize());
        long refCountOffset = GObject.getMemoryLayout()
                .byteOffset(MemoryLayout.PathElement.groupElement("ref_count"));
        int builderReferences = nativeObject.get(ValueLayout.JAVA_INT, refCountOffset);

        GObject buffer = objects.getFirst();
        assertEquals(builderReferences + 1, nativeObject.get(ValueLayout.JAVA_INT, refCountOffset),
                "the Java wrapper must own a reference in addition to the builder's reference");
        assertSame(buffer, UiLoader.objects(builder).getFirst());
        assertEquals(builderReferences + 1, nativeObject.get(ValueLayout.JAVA_INT, refCountOffset),
                "enumerating the same object again must not leak another reference");
    }

    @Test
    @DisplayName("main-window.ui parses and contains all 1:1 widgets")
    void mainWindow() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        Widgets.require(builder, "main_window", ApplicationWindow.class);
        PopupMenu contextMenu = new PopupMenu()
                .add("Enabled action", true, () -> { })
                .add("Disabled action", false, () -> { });
        assertFalse(contextMenu.getPopover().getHasArrow(),
                "download context menu should match arrowless menubar dropdowns");
        assertTrue(contextMenu.getPopover().getAutohide());
        Box contextItems = assertInstanceOf(Box.class,
                contextMenu.getPopover().getChild());
        Button enabledContextItem = assertInstanceOf(Button.class,
                contextItems.getFirstChild());
        Button disabledContextItem = assertInstanceOf(Button.class,
                enabledContextItem.getNextSibling());
        assertTrue(enabledContextItem.hasCssClass("flat"));
        assertTrue(enabledContextItem.hasCssClass("odm-context-menu-item"));
        assertEquals(Align.FILL, enabledContextItem.getHalign());
        Label enabledContextLabel = assertInstanceOf(Label.class,
                enabledContextItem.getChild());
        assertEquals(0.0f, enabledContextLabel.getXalign());
        assertTrue(enabledContextItem.getSensitive());
        assertFalse(disabledContextItem.getSensitive());
        org.gnome.gio.ListModel<org.gnome.gtk.EventController> contextControllers =
                enabledContextItem.observeControllers();
        EventControllerMotion hoverController = null;
        for (int i = 0; i < contextControllers.getNItems(); i++) {
            if (contextControllers.getItem(i) instanceof EventControllerMotion motion) {
                hoverController = motion;
                break;
            }
        }
        assertNotNull(hoverController);
        hoverController.emitEnter(1.0, 1.0);
        assertTrue(enabledContextItem.hasCssClass("odm-context-menu-item-hover"));
        hoverController.emitLeave();
        assertFalse(enabledContextItem.hasCssClass("odm-context-menu-item-hover"));
        hoverController.emitMotion(2.0, 2.0);
        assertTrue(enabledContextItem.hasCssClass("odm-context-menu-item-hover"),
                "motion must recover hover when mapping produced no enter event");
        // stores
        for (String id : new String[]{"status_store", "category_store", "download_store",
                "completion_details_store", "global_progress_store",
                "peers_store", "trackers_store"}) {
            Widgets.require(builder, id, ListStore.class);
        }
        Widgets.require(builder, "files_store", TreeStore.class);
        // side panel treeviews + columns
        ScrolledWindow statusScrolled = Widgets.require(builder,
                "status_scrolled_window", ScrolledWindow.class);
        ScrolledWindow categoryScrolled = Widgets.require(builder,
                "category_scrolled_window", ScrolledWindow.class);
        assertFalse(statusScrolled.getVexpand());
        assertTrue(categoryScrolled.getVexpand());
        assertTrue(statusScrolled.getPropagateNaturalHeight());
        assertTrue(categoryScrolled.getPropagateNaturalHeight());
        TreeView statusTree = Widgets.require(builder, "status_treeview", TreeView.class);
        Label statusHeading = Widgets.require(builder, "status_label", Label.class);
        Label categoryHeading = Widgets.require(builder, "category_label", Label.class);
        assertTrue(statusHeading.getUseMarkup());
        assertTrue(categoryHeading.getUseMarkup());
        assertEquals("<b>Status</b>", statusHeading.getLabel());
        assertEquals("<b>Categories</b>", categoryHeading.getLabel());
        TreeViewColumn statusColumn = Widgets.require(builder,
                "status_column", TreeViewColumn.class);
        TreeViewColumn countColumn = Widgets.require(builder,
                "count_column", TreeViewColumn.class);
        TreeView categoryTree = Widgets.require(builder, "category_treeview", TreeView.class);
        TreeViewColumn categoryColumn = Widgets.require(builder,
                "category_column", TreeViewColumn.class);
        TreeViewColumn categoryCountColumn = Widgets.require(builder,
                "category_count_column", TreeViewColumn.class);
        assertTrue(statusColumn.getExpand());
        assertTrue(categoryColumn.getExpand());

        ListStore statusStore = Widgets.require(builder, "status_store", ListStore.class);
        TreeIter statusRow = new TreeIter();
        statusStore.append(statusRow);
        ListStoreCells.setString(statusStore, statusRow, 0, "view-list-symbolic");
        ListStoreCells.setString(statusStore, statusRow, 1, "7/12");
        ListStoreCells.setString(statusStore, statusRow, 2, "All Status");
        statusColumn.cellSetCellData(statusStore, statusRow, false, false);
        countColumn.cellSetCellData(statusStore, statusRow, false, false);
        assertEquals("All Status", Widgets.require(builder,
                "status_label_renderer", CellRendererText.class).getProperty("text"));
        assertEquals("7/12", Widgets.require(builder,
                "status_count_renderer", CellRendererText.class).getProperty("text"));

        ListStore categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        TreeIter categoryRow = new TreeIter();
        categoryStore.append(categoryRow);
        ListStoreCells.setString(categoryStore, categoryRow, 0, "video-x-generic-symbolic");
        ListStoreCells.setString(categoryStore, categoryRow, 1, "3/9");
        ListStoreCells.setString(categoryStore, categoryRow, 2, "Videos");
        categoryColumn.cellSetCellData(categoryStore, categoryRow, false, false);
        categoryCountColumn.cellSetCellData(categoryStore, categoryRow, false, false);
        assertEquals("Videos", Widgets.require(builder,
                "category_label_renderer", CellRendererText.class).getProperty("text"));
        assertEquals("3/9", Widgets.require(builder,
                "category_count_renderer", CellRendererText.class).getProperty("text"));
        PopoverMenuBar menuBar = Widgets.require(builder, "menu_bar", PopoverMenuBar.class);
        assertFalse(menuBar.getVexpand());
        Box toolbar = Widgets.require(builder, "download_toolbar", Box.class);
        assertTrue(toolbar.hasCssClass("toolbar"));
        assertFalse(toolbar.getVexpand());
        // toolbar buttons
        for (String id : new String[]{"new_download_button", "pause_button", "resume_button",
                "delete_button", "move_up_button", "move_top_button", "move_down_button", "move_bottom_button",
                "settings_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        assertEquals("Start / Resume",
                Widgets.require(builder, "resume_button", Button.class).getTooltipText());
        assertNull(builder.getObject("tor_switch"));
        assertFalse(Widgets.require(builder, "tor_check_button", Button.class).getVisible());
        Image torIcon = Widgets.require(builder, "tor_icon", Image.class);
        assertNull(torIcon.getIconName(),
                "the status bar must use the bundled Tor icon");
        assertEquals(16, torIcon.getPixelSize());
        Widgets.require(builder, "search_entry", org.gnome.gtk.SearchEntry.class);
        Box toolbarSpacer = Widgets.require(builder, "toolbar_spacer", Box.class);
        Box searchToolItem = Widgets.require(builder, "search_tool_item", Box.class);
        assertTrue(toolbarSpacer.getHexpand());
        assertSame(searchToolItem, toolbar.getLastChild(),
                "the search control must be the toolbar's trailing item");
        // download treeview + columns
        Paned contentPaned = Widgets.require(builder, "content_paned", Paned.class);
        assertTrue(contentPaned.getVexpand());
        assertTrue(contentPaned.getResizeStartChild());
        assertTrue(contentPaned.getResizeEndChild());
        assertFalse(contentPaned.getShrinkEndChild(),
                "the detail-tab buttons must remain visible when the pane is compressed");
        Box downloadList = Widgets.require(builder, "download_list_container", Box.class);
        ScrolledWindow downloadScrolled = Widgets.require(builder,
                "download_scrolled_window", ScrolledWindow.class);
        assertTrue(downloadList.getVexpand());
        assertTrue(downloadScrolled.getVexpand());
        TreeView downloadTree = Widgets.require(builder, "download_treeview", TreeView.class);
        String[] downloadColumnIds = {"number_column", "status_icon_column", "name_column",
                "complete_column", "size_column",
                "percent_progress_column", "elapsed_column", "left_column", "speed_column", "up_speed_column",
                "ratio_column", "retry_column", "start_date_column", "end_date_column", "tor_icon_column"};
        int[] sortColumnIds = {0, 16, 1, 17, 18, 19, 20, 21, 22, 23, 29, 8, 24, 25, 26};
        for (int index = 0; index < downloadColumnIds.length; index++) {
            String id = downloadColumnIds[index];
            Widgets.require(builder, id, org.gnome.gtk.TreeViewColumn.class);
            assertEquals(sortColumnIds[index], Widgets.require(builder,
                    id, TreeViewColumn.class).getSortColumnId(),
                    id + " must expose a typed sort key");
        }
        assertEquals(30, Widgets.require(builder, "download_store", ListStore.class).getNColumns());
        for (org.manager.download.Download.Type type
                : org.manager.download.Download.Type.values()) {
            org.gnome.gio.Icon icon = DownloadEnginePresentation.icon(type);
            assertNotNull(icon, type + " must have an engine icon");
            if (type == org.manager.download.Download.Type.ARIA2) {
                org.gnome.gio.ThemedIcon themedIcon = assertInstanceOf(
                        org.gnome.gio.ThemedIcon.class, icon);
                assertEquals("network-server-symbolic", themedIcon.getNames()[0]);
            } else {
                org.gnome.gdk.Texture texture = assertInstanceOf(
                        org.gnome.gdk.Texture.class, icon);
                assertTrue(texture.getWidth() <= DownloadEnginePresentation.ICON_SIZE);
                assertTrue(texture.getHeight() <= DownloadEnginePresentation.ICON_SIZE);
            }
        }
        assertEquals(7, Widgets.require(builder,
                "completion_details_store", ListStore.class).getNColumns());
        Widgets.require(builder, "completion_details_view", TreeView.class);
        assertEquals("Actions", Widgets.require(builder,
                "actions_tab_label", Label.class).getLabel());
        TreeViewColumn statusIconColumn = Widgets.require(builder,
                "status_icon_column", TreeViewColumn.class);
        TreeViewColumn resultIconColumn = Widgets.require(builder,
                "tor_icon_column", TreeViewColumn.class);
        TreeViewColumn nameColumn = Widgets.require(builder, "name_column", TreeViewColumn.class);
        assertTrue(statusIconColumn.getTitle() == null || statusIconColumn.getTitle().isEmpty(),
                "the icon-only status column must not show a header label");
        assertEquals(TreeViewColumnSizing.FIXED, statusIconColumn.getSizing());
        assertEquals(32, statusIconColumn.getFixedWidth(),
                "the status column should remain close to the icon's natural width");
        assertEquals("Res.", resultIconColumn.getTitle());
        assertEquals(TreeViewColumnSizing.FIXED, resultIconColumn.getSizing());
        assertTrue(resultIconColumn.getFixedWidth() <= 48,
                "the Result column should remain icon-sized");
        assertEquals(40, resultIconColumn.getMaxWidth());
        assertTrue(treeColumnIndex(downloadTree, statusIconColumn)
                        < treeColumnIndex(downloadTree, nameColumn),
                "the lifecycle icon must appear before the download name");
        Widgets.require(builder, "status_icon_renderer", org.gnome.gtk.CellRendererPixbuf.class);
        Widgets.require(builder, "engine_icon_renderer", org.gnome.gtk.CellRendererPixbuf.class);
        Widgets.require(builder, "download_progress_renderer", CellRendererProgress.class);
        assertTrue(nameColumn.getMinWidth() >= 200);
        assertFalse(nameColumn.getExpand());
        assertEquals(420, nameColumn.getMaxWidth(),
                "the name column must stay within its maximum width");
        CellRendererText nameRenderer = Widgets.require(builder,
                "name_renderer", CellRendererText.class);
        assertEquals(org.gnome.pango.EllipsizeMode.END,
                nameRenderer.getProperty("ellipsize"));
        TreeViewColumn progressColumn = Widgets.require(builder,
                "percent_progress_column", TreeViewColumn.class);
        assertEquals(TreeViewColumnSizing.FIXED, progressColumn.getSizing());
        assertTrue(progressColumn.getFixedWidth() >= 110,
                "the progress renderer needs enough room for its percentage");
        // info panel
        Box infoPanel = Widgets.require(builder, "info_panel_box", Box.class);
        Notebook infoNotebook = Widgets.require(builder, "info_notebook", Notebook.class);
        DrawingArea infoProgress = Widgets.require(builder, "info_progress_bar", DrawingArea.class);
        assertTrue(infoPanel.getVexpand());
        assertTrue(infoNotebook.getVexpand());
        assertEquals(PositionType.BOTTOM, infoNotebook.getTabPos());
        assertTrue(infoProgress.getHexpand());
        assertTrue(infoProgress.getContentHeight() >= 80,
                "the speed graph needs enough height to show rate variations");
        Widgets.require(builder, "info_progress_value", Label.class);
        Widgets.require(builder, "info_speed_value", Label.class);
        Widgets.require(builder, "info_average_speed_value", Label.class);
        Box generalColumns = Widgets.require(builder, "general_columns", Box.class);
        Box generalTab = Widgets.require(builder, "general_tab", Box.class);
        ScrolledWindow generalScrolled = Widgets.require(builder, "general_scrolled", ScrolledWindow.class);
        assertSame(generalScrolled, infoNotebook.getNthPage(0));
        assertFalse(generalScrolled.getPropagateNaturalHeight(),
                "the full General layout must not impose its height on the split pane");
        Frame progressFrame = Widgets.require(builder, "progress_frame", Frame.class);
        assertSame(generalColumns, generalTab.getFirstChild());
        assertSame(progressFrame, generalColumns.getNextSibling(),
                "the full-width progress graph belongs below Information and Transfer");
        assertSame(progressFrame, generalTab.getLastChild());
        assertSame(Widgets.require(builder, "progress_container", Box.class), infoProgress.getParent());
        assertEquals(Orientation.HORIZONTAL, generalColumns.getOrientation());
        assertTrue(generalColumns.getHomogeneous());
        assertSame(Widgets.require(builder, "information_frame", org.gnome.gtk.Frame.class),
                generalColumns.getFirstChild());
        assertSame(Widgets.require(builder, "transfer_frame", org.gnome.gtk.Frame.class),
                generalColumns.getLastChild());
        assertBoldFrameTitles(builder, "information_frame", "transfer_frame", "progress_frame");
        for (String id : new String[]{"added_on_value", "info_hash_v1_value",
                "folder_value", "engine_value", "eta_value", "downloaded_value",
                "connections_value", "seeds_peers_value"}) {
            Widgets.require(builder, id, Label.class);
        }
        LinkButton folderButton = Widgets.require(builder,
                "folder_open_button", LinkButton.class);
        Button errorButton = Widgets.require(builder, "info_error_button", Button.class);
        Box errorContent = Widgets.require(builder, "info_error_content", Box.class);
        Label errorValue = Widgets.require(builder, "info_error_value", Label.class);
        assertFalse(errorButton.getSensitive());
        assertEquals(org.gnome.pango.EllipsizeMode.END, errorValue.getEllipsize());
        assertSame(errorContent, errorValue.getParent(),
                "the nested label must not receive the theme's direct-link underline");
        assertSame(errorButton, errorContent.getParent());
        assertFalse(errorButton.getHasFrame(),
                "error details must not display a button border");
        assertFalse(errorValue.hasCssClass("error"),
                "the error summary must use normal theme text");
        assertEquals(2, gridRow(Widgets.require(builder, "general_info_grid", Grid.class), errorButton));
        assertEquals(1, gridRow(Widgets.require(builder, "general_info_grid", Grid.class),
                Widgets.require(builder, "info_hash_v1_value", Label.class)));
        assertEquals(0, gridRow(Widgets.require(builder, "transfer_info_grid", Grid.class),
                Widgets.require(builder, "engine_value_box", Box.class)));
        Box folderContent = Widgets.require(builder, "folder_open_content", Box.class);
        Label folderValue = Widgets.require(builder, "folder_value", Label.class);
        assertSame(folderContent, folderValue.getParent());
        assertSame(folderButton, folderContent.getParent());
        assertTrue(folderButton.hasCssClass("link"),
                "the save folder action must use GTK's HTML-link presentation");
        assertEquals("file:///", folderButton.getUri());
        assertFalse(folderButton.getSensitive(),
                "the folder action must remain disabled until a destination is selected");
        Widgets.require(builder, "engine_icon", Image.class);
        Widgets.require(builder, "trackers_view", TreeView.class);
        Widgets.require(builder, "peers_view", TreeView.class);
        Widgets.require(builder, "files_view", TreeView.class);
        TreeStore detailFilesStore = Widgets.require(builder, "files_store", TreeStore.class);
        assertEquals(FileTreeSupport.COLUMN_COUNT, detailFilesStore.getNColumns(),
                "detail files need hierarchy, aggregate state, sort keys, and full paths");
        TreeViewColumn filesNameColumn = Widgets.require(builder,
                "files_name_column", TreeViewColumn.class);
        assertEquals(420, filesNameColumn.getMaxWidth(),
                "the detail file name column must not grow without bound");
        String[] detailFileColumnIds = {"files_selected_column", "files_name_column",
                "files_size_column", "files_progress_column", "files_priority_column"};
        int[] detailFileSortIds = {0, 1, 7, 8, 12};
        for (int index = 0; index < detailFileColumnIds.length; index++) {
            assertEquals(detailFileSortIds[index], Widgets.require(builder,
                    detailFileColumnIds[index], TreeViewColumn.class).getSortColumnId(),
                    detailFileColumnIds[index] + " must be sortable");
        }
        Widgets.require(builder, "files_priority_store", ListStore.class);
        CellRendererCombo filePriority = Widgets.require(builder,
                "files_priority_renderer", CellRendererCombo.class);
        assertEquals(true, filePriority.getProperty("editable"));
        // status bar
        Widgets.require(builder, "statusbar", org.gnome.gtk.Box.class);
        for (String id : new String[]{"info_label", "up_speed_label", "down_speed_label",
                "dht_status_label"}) {
            Widgets.require(builder, id, Label.class);
        }
        Label infoLabel = Widgets.require(builder, "info_label", Label.class);
        assertEquals(org.gnome.pango.EllipsizeMode.END, infoLabel.getEllipsize());
        assertTrue(infoLabel.getSingleLineMode());
        assertEquals(72, infoLabel.getMaxWidthChars());
        Widgets.require(builder, "activity_spinner", Spinner.class);
        assertFalse(Widgets.require(builder, "statusbar_box", Box.class).getVexpand());
        Box rightStatus = Widgets.require(builder, "statusbar_right_box", Box.class);
        assertChildrenOrdered(rightStatus,
                Widgets.require(builder, "activity_spinner", Spinner.class),
                Widgets.require(builder, "tor_check_button", Button.class),
                Widgets.require(builder, "dht_progress_box", Box.class),
                Widgets.require(builder, "upload_speed_box", Box.class),
                Widgets.require(builder, "download_speed_box", Box.class),
                Widgets.require(builder, "global_progress_tree", TreeView.class));
        assertChildrenOrdered(Widgets.require(builder, "tor_status_box", Box.class),
                Widgets.require(builder, "tor_ip_label", Label.class),
                Widgets.require(builder, "tor_icon", Image.class));
        assertFalse(Widgets.require(builder, "tor_ip_label", Label.class).getVisible());
        TreeView globalProgress = Widgets.require(builder, "global_progress_tree", TreeView.class);
        Widgets.require(builder, "global_progress_renderer", CellRendererProgress.class);
        Out<Integer> progressWidth = new Out<>();
        globalProgress.getSizeRequest(progressWidth, new Out<>());
        assertEquals(110, progressWidth.get());
    }

    @Test
    @DisplayName("context menu presentation waits until pointer dispatch completes")
    void contextMenuPresentationIsDeferred() throws InterruptedException {
        AtomicBoolean presented = new AtomicBoolean();

        MainWindow.deferContextMenuPopup(() -> presented.set(true));

        assertFalse(presented.get());
        awaitGtk(presented::get, "deferred context menu callback was not dispatched");
        assertTrue(presented.get());
    }

    @Test
    @DisplayName("reopening the download context menu keeps the GTK CSS tree valid")
    void contextMenuCanBeReopenedWithoutGtkCritical() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ApplicationWindow window = Widgets.require(builder,
                "main_window", ApplicationWindow.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        PopoverMenuBar popoverHost = Widgets.require(builder,
                "menu_bar", PopoverMenuBar.class);
        org.gnome.gio.Menu menuBarModel = new org.gnome.gio.Menu();
        org.gnome.gio.Menu fileMenu = new org.gnome.gio.Menu();
        fileMenu.append("Example", "win.example");
        menuBarModel.appendSubmenu("_File", fileMenu);
        popoverHost.setMenuModel(menuBarModel);
        AtomicReference<String> critical = new AtomicReference<>();
        int handler = org.gnome.glib.GLib.logSetHandler("Gtk",
                org.gnome.glib.LogLevelFlags.LEVEL_CRITICAL,
                (domain, level, message) -> critical.compareAndSet(null, message));
        PopupMenu menu = null;
        window.present();
        drainGtkEvents();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (menu != null) {
                    menu.dispose();
                }
                menu = new PopupMenu().add("Properties", () -> { });
                menu.popupAt(popoverHost, tree, 20, 20);
                drainGtkEvents();
                assertTrue(menu.getPopover().getMapped(),
                        "context popover must remain visible with its supported host");
            }
            assertNull(critical.get(), () -> "GTK critical while reopening context menu: "
                    + critical.get());
        } finally {
            if (menu != null) {
                menu.dispose();
            }
            org.gnome.glib.GLib.logRemoveHandler("Gtk", handler);
            window.close();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("settings.ui parses with 1:1 original ids (6 tabs)")
    void settings() {
        GtkBuilder builder = UiLoader.load("/ui/settings.ui");
        Widgets.require(builder, "settings_dialog", Window.class);
        Notebook settingsNotebook = Widgets.require(builder,
                "settings_notebook", Notebook.class);
        assertNotebookInset(settingsNotebook);
        // General
        for (String id : new String[]{"max_concurrent_downloads_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"browse_aria2_button", "browse_ytdlp_button", "browse_httrack_button",
                "browse_proxychains_button", "browse_tor_button", "browse_curl_button",
                "browse_subliminal_button",
                "settings_cancel_button", "settings_reset_button", "settings_apply_button", "settings_ok_button"}) {
            Widgets.require(builder, id, Button.class);
        }
        Widgets.require(builder, "default_download_folder_chooser", MenuButton.class);
        Widgets.require(builder, "monitored_folder_chooser", MenuButton.class);
        for (String id : new String[]{"retain_completed_canceled_history_check",
                "automatic_cleanup_check", "clipboard_monitor_check",
                "clipboard_silent_check", "system_tray_check", "start_automatically_check",
                "override_output_path_check",
                "uniquify_output_name_check",
                "verify_https_certificates_check",
                "move_torrent_check", "startup_check", "folder_monitoring_check", "folder_recursive_check",
                "move_to_trash_check",
                "continue_download_check", "check_integrity_check", "enable_auto_save_check",
                "remote_time_check", "honor_external_aria2_config_check",
                "write_thumbnail_check", "embed_thumbnail_check",
                "embed_metadata_check", "use_aria2_external_check",
                "skip_downloaded_media_check", "honor_external_ytdlp_config_check",
                "enable_scheduling_check"}) {
            Widgets.require(builder, id, CheckButton.class);
        }
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "min_split_size_spin1",
                "max_peers_spin", "peer_speed_limit_spin", "seed_ratio_spin", "seed_time_spin",
                "aria2_rpc_port_spin",
                "httrack_max_total_size_spin", "httrack_max_non_html_size_spin",
                "httrack_max_html_size_spin", "httrack_max_duration_spin",
                "httrack_max_links_spin", "httrack_connections_per_second_spin",
                "httrack_delay_between_files_spin", "proxy_port_spin",
                "cleanup_interval_spin", "max_history_records_spin", "max_completed_records_spin",
                "completed_retention_spin", "error_retention_spin",
                "max_import_urls_spin", "max_import_source_size_spin",
                "antivirus_timeout_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"aria2_path_entry", "ytdlp_path_entry", "httrack_path_entry",
                "referer_entry", "cookie_entry", "user_agent_entry", "proxy_host_entry",
                "proxy_username_entry", "proxy_password_entry",
                "torrent_listen_ports_entry",
                "proxychains_path_entry", "tor_path_entry", "curl_path_entry",
                "subliminal_path_entry", "antivirus_command_entry"}) {
            Widgets.require(builder, id, Entry.class);
        }
        assertNull(builder.getObject("httrack_headers_view"));
        assertNull(builder.getObject("httrack_cookie_file_entry"));
        assertNull(builder.getObject("browse_httrack_cookie_button"));
        Widgets.require(builder, "proxy_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "file_allocation_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "seeding_policy_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "ipv6_dht_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "peer_exchange_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "local_peer_discovery_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "torrent_encryption_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "antivirus_type_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "antivirus_detection_label", Label.class);
        org.gnome.gtk.Switch settingsTor = Widgets.require(builder,
                "tor_switch", org.gnome.gtk.Switch.class);
        assertSame(Widgets.require(builder, "tor_settings_grid", Grid.class),
                settingsTor.getParent(),
                "the Settings Tor switch must be a direct grid child");
        Widgets.require(builder, "available_space_label", Label.class);
        Widgets.require(builder, "settings_status_label", Label.class);
        assertDiskLabelBelowChooser(builder, "default_download_folder_chooser",
                "available_space_label");
        Grid generalLayout = Widgets.require(builder, "general_layout_grid", Grid.class);
        assertFlatFieldGrid(generalLayout);
        assertOptionsMargins(generalLayout);
        assertEquals(1, gridColumn(generalLayout,
                Widgets.require(builder, "max_concurrent_downloads_spin", SpinButton.class)));
        assertTrue(Widgets.require(builder, "clipboard_silent_check", CheckButton.class)
                .getMarginStart() >= 18,
                "clipboard silent mode must read as a child of clipboard monitoring");
        assertTrue(Widgets.require(builder, "folder_recursive_check", CheckButton.class)
                .getMarginStart() >= 18,
                "recursive monitoring must read as a child of folder monitoring");
        assertTrue(Widgets.require(builder, "move_to_trash_check", CheckButton.class)
                .getMarginStart() >= 18,
                "processed-descriptor Trash must read as a child of folder monitoring");
        assertSame(Widgets.require(builder, "general_options_grid", Grid.class),
                Widgets.require(builder, "enable_auto_save_check", CheckButton.class).getParent(),
                "ODM auto save belongs to General rather than the Aria2 engine tab");
        CheckButton saveHistory = Widgets.require(builder,
                "retain_completed_canceled_history_check", CheckButton.class);
        assertEquals("Keep completed and canceled records after restart", saveHistory.getLabel());
        assertSame(Widgets.require(builder, "history_cleanup_box", Box.class),
                saveHistory.getParent(),
                "Keep completed and canceled records after restart belongs to Advanced > Download History");
        assertNull(builder.getObject("start_automatically_check2"),
                "the global automatic-start policy must not be duplicated on Network");
        assertNull(builder.getObject("move_torrent_check2"),
                "the global descriptor Trash policy must not be duplicated on Network");
        for (String id : new String[]{"video_format_entry", "container_profile_combo",
                "subtitle_language_entry", "write_subtitles_check", "extract_audio_check",
                "cookie_browser_combo", "cookie_browser_profile_entry",
                "depth_spin", "httrack_scope_combo", "httrack_external_depth_spin",
                "include_entry", "exclude_entry", "include_archives_check"}) {
            assertNull(builder.getObject(id),
                    id + " is a per-record choice owned by its download dialog");
        }
        assertDownloadOptionsLayout(builder);
        for (String id : new String[]{"aria2_layout_grid", "ytdlp_layout_grid",
                "httrack_layout_grid", "advanced_layout_grid"}) {
            Grid layout = Widgets.require(builder, id, Grid.class);
            assertFlatFieldGrid(layout);
            assertOptionsMargins(layout);
        }
        assertEquals(1, gridColumn(Widgets.require(builder, "aria2_layout_grid", Grid.class),
                Widgets.require(builder, "min_split_size_spin1", SpinButton.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "ytdlp_layout_grid", Grid.class),
                Widgets.require(builder, "ytdlp_path_box", Box.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "httrack_layout_grid", Grid.class),
                Widgets.require(builder, "httrack_path_box", Box.class)));
        assertBoldLabels(builder, "download_settings_heading", "http_connection_heading",
                "proxy_settings_heading", "tor_settings_heading",
                "scheduling_heading", "history_cleanup_heading",
                "import_limits_heading", "advanced_tools_heading");
        Widgets.require(builder, "import_limits_grid", Grid.class);
        Widgets.require(builder, "scheduler_selection_label", Label.class);
        Box legend = Widgets.require(builder, "scheduler_legend_box", Box.class);
        assertEquals(Orientation.VERTICAL, legend.getOrientation());
        assertTrue(Widgets.require(builder, "scheduler_active_swatch", Box.class)
                .hasCssClass("scheduler-active-swatch"));
        assertTrue(Widgets.require(builder, "scheduler_inactive_swatch", Box.class)
                .hasCssClass("scheduler-inactive-swatch"));
    }

    @Test
    @DisplayName("Network Options disable controls unsupported by the engine and protocol")
    void networkOptionCapabilitiesDriveControlSensitivity() {
        SpinButton connections = SpinButton.withRange(1, 16, 1);
        SpinButton downloadLimit = SpinButton.withRange(0, 1000, 1);
        SpinButton uploadLimit = SpinButton.withRange(0, 1000, 1);
        SpinButton retries = SpinButton.withRange(0, 50, 1);
        SpinButton retryDelay = SpinButton.withRange(0, 600, 1);
        Entry referer = new Entry();
        Entry userAgent = new Entry();
        Entry cookie = new Entry();
        DropDown proxyType = DropDown.fromStrings(DialogOptions.PROXY_TYPES);
        Entry proxyHost = new Entry();
        SpinButton proxyPort = SpinButton.withRange(0, 65_535, 1);
        Entry proxyUsername = new Entry();
        Entry proxyPassword = new Entry();
        Switch tor = new Switch();
        NetworkOptionControls controls = new NetworkOptionControls(connections,
                downloadLimit, uploadLimit, retries, retryDelay, referer,
                userAgent, cookie, proxyType, proxyHost, proxyPort,
                proxyUsername, proxyPassword, tor);

        controls.applyCapabilities(NetworkOptionControls.capabilitiesFor(
                new org.manager.GlobalSettings(),
                org.manager.download.Download.Type.WEBSITE_SCRAPING,
                org.manager.download.Download.Protocol.HTTPS));

        assertTrue(connections.getSensitive());
        assertTrue(downloadLimit.getSensitive());
        assertFalse(uploadLimit.getSensitive());
        assertTrue(retries.getSensitive());
        assertFalse(retryDelay.getSensitive());
        assertTrue(referer.getSensitive());
        assertTrue(proxyType.getSensitive());
        assertFalse(tor.getSensitive(), "Tor service was not supplied");
        assertFalse(proxyHost.getSensitive(),
                "proxy details remain disabled while proxy type is None");
        proxyType.setSelected(1);
        assertTrue(proxyHost.getSensitive());

        controls.applyCapabilities(NetworkOptionControls.capabilitiesFor(
                new org.manager.GlobalSettings(),
                org.manager.download.Download.Type.ARIA2,
                org.manager.download.Download.Protocol.TORRENT));
        assertFalse(connections.getSensitive());
        assertTrue(uploadLimit.getSensitive());
        assertFalse(referer.getSensitive());
        assertFalse(tor.getSensitive(), "Tor service was not supplied");
    }

    @Test
    void connectionRangesFollowEngineWithoutLosingTheRequestedValue() {
        var global = new org.manager.GlobalSettings();
        global.setProperty("network.maxConnections", "64");
        NetworkOptionsPane pane = new NetworkOptionsPane(global,
                org.manager.download.Download.Type.ARIA2,
                org.manager.download.Download.Protocol.HTTPS);
        SpinButton connections = firstDescendant(pane.widget(), SpinButton.class);
        assertEquals(16, connections.getAdjustment().getUpper());
        assertEquals(16, pane.values().connections());

        pane.updateCapabilities(global, org.manager.download.Download.Type.YOUTUBE,
                org.manager.download.Download.Protocol.HTTPS);
        assertEquals(64, connections.getAdjustment().getUpper());
        assertEquals(64, pane.values().connections());
        assertFalse(pane.hasChanges(), "changing engine must not mark values as manually edited");

        connections.setValue(128);
        assertEquals(64, pane.values().connections());
        connections.setValue(32);
        pane.updateCapabilities(global, org.manager.download.Download.Type.WEBSITE_SCRAPING,
                org.manager.download.Download.Protocol.HTTPS);
        assertEquals(8, connections.getAdjustment().getUpper());
        assertEquals(8, pane.values().connections());
        pane.updateCapabilities(global, org.manager.download.Download.Type.YOUTUBE,
                org.manager.download.Download.Protocol.HTTPS);
        assertEquals(32, pane.values().connections());

        pane.updateCapabilities(global, org.manager.download.Download.Type.CURL,
                org.manager.download.Download.Protocol.HTTPS);
        assertFalse(connections.getSensitive());
    }

    @Test
    void propertyConnectionRangeFitsEverySelectedEngine() {
        var media = new org.manager.download.Download(java.net.URI.create(
                "https://www.youtube.com/watch?v=12345678901"));
        media.setType(org.manager.download.Download.Type.YOUTUBE);
        media.setSettings(new org.ytdlp.YtDlpSettings().setConnections(64));
        var file = new org.manager.download.Download(java.net.URI.create(
                "https://example.test/file.iso"));
        file.setSettings(new org.aria2.Aria2Settings());

        NetworkOptionsPane mediaPane = new NetworkOptionsPane(java.util.List.of(media));
        assertEquals(64, mediaPane.values().connections());
        assertEquals(64,
                firstDescendant(mediaPane.widget(), SpinButton.class).getAdjustment().getUpper());

        NetworkOptionsPane mixedPane = new NetworkOptionsPane(java.util.List.of(media, file));
        assertEquals(16,
                firstDescendant(mixedPane.widget(), SpinButton.class).getAdjustment().getUpper());
        assertEquals(16, mixedPane.values().connections());
        assertFalse(mixedPane.hasChanges());
        assertEquals(64, media.getSettings().getMaxConnections(),
                "opening mixed properties must not modify the selected records");
    }

    @Test
    @DisplayName("new-download.ui parses with 1:1 original ids")
    void newDownload() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        Widgets.require(builder, "new_download_dialog", Window.class);
        assertNotebookInset(Widgets.require(builder,
                "options_notebook", Notebook.class));
        assertPrimaryTabMargins(Widgets.require(builder, "download_page", Grid.class));
        assertListTabMargins(Widgets.require(builder, "files_page", Box.class));
        Widgets.require(builder, "url_entry", Entry.class);
        Widgets.require(builder, "torrent_file_chooser", Button.class);
        Widgets.require(builder, "save_folder_chooser", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "filename_entry", Entry.class);
        Widgets.require(builder, "files_treeview", TreeView.class);
        Widgets.require(builder, "files_status_label", Label.class);
        Widgets.require(builder, "select_all_files_check", CheckButton.class);
        TreeStore filesStore = Widgets.require(builder, "files_liststore", TreeStore.class);
        assertEquals(FileTreeSupport.COLUMN_COUNT, filesStore.getNColumns(),
                "new-download files need the shared hierarchical file schema");
        String[] fileColumnIds = {"new_files_selected_column", "new_files_name_column",
                "new_files_size_column", "new_files_priority_column"};
        int[] fileSortIds = {0, 1, 7, 12};
        for (int index = 0; index < fileColumnIds.length; index++) {
            assertEquals(fileSortIds[index], Widgets.require(builder,
                    fileColumnIds[index], TreeViewColumn.class).getSortColumnId(),
                    fileColumnIds[index] + " must be sortable");
        }
        Widgets.require(builder, "new_download_options_content", Box.class);
        Widgets.require(builder, "new_download_network_options_host", Box.class);
        Widgets.require(builder, "sftp_host_key_grid", Grid.class);
        Widgets.require(builder, "sftp_host_key_label", Label.class);
        Widgets.require(builder, "sftp_host_key_entry", Entry.class);
        assertNoEmbeddedNetworkOptions(builder);
        assertNull(builder.getObject("start_automatically_check"));
        assertNull(builder.getObject("move_torrent_check"));
        Widgets.require(builder, "new_download_spinner", Spinner.class);
        Widgets.require(builder, "new_download_cancel_button", Button.class);
        Widgets.require(builder, "new_download_start_button", Button.class);
        assertDiskLabelBelowChooser(builder, "save_folder_chooser", "disk_space_label");
    }

    @Test
    @DisplayName("new-media.ui parses with expected ids")
    void newMedia() {
        GtkBuilder builder = UiLoader.load("/ui/new-media.ui");
        Widgets.require(builder, "new_media_dialog", Window.class);
        Widgets.require(builder, "media_url_entry", Entry.class);
        Widgets.require(builder, "fetch_info_button", Button.class);
        Widgets.require(builder, "media_status_label", Label.class);
        Widgets.require(builder, "media_info_label", Label.class);
        Widgets.require(builder, "format_drop", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "media_container_profile_combo", DropDown.class);
        Widgets.require(builder, "audio_only_check", CheckButton.class);
        Widgets.require(builder, "playlist_check", CheckButton.class);
        Widgets.require(builder, "playlist_items_entry", Entry.class);
        Widgets.require(builder, "playlist_select_all_check", CheckButton.class);
        Widgets.require(builder, "playlist_store", ListStore.class);
        Widgets.require(builder, "playlist_treeview", TreeView.class);
        Widgets.require(builder, "playlist_preview_scroller", ScrolledWindow.class);
        Widgets.require(builder, "subtitles_check", CheckButton.class);
        Widgets.require(builder, "subtitle_lang_entry", Entry.class);
        Widgets.require(builder, "media_cookie_browser_combo", DropDown.class);
        Widgets.require(builder, "media_cookie_browser_profile_entry", Entry.class);
        Widgets.require(builder, "cookie_file_chooser", Button.class);
        Widgets.require(builder, "sponsorblock_mode_combo", DropDown.class);
        Widgets.require(builder, "sponsorblock_categories_entry", Entry.class);
        Widgets.require(builder, "media_folder_chooser", MenuButton.class);
        Widgets.require(builder, "media_disk_space_label", Label.class);
        Widgets.require(builder, "media_cancel_button", Button.class);
        Widgets.require(builder, "media_start_button", Button.class);
        Grid fields = Widgets.require(builder, "media_fields_grid", Grid.class);
        assertFieldGrid(fields);
        assertPrimaryTabMargins(fields);
        assertPrimaryTabMargins(Widgets.require(builder,
                "new_media_content", Box.class));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "media_url_entry", Entry.class).getParent()));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "format_drop", org.gnome.gtk.DropDown.class)));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "subtitle_lang_entry", Entry.class)));
        Grid options = Widgets.require(builder, "media_options_grid", Grid.class);
        assertEquals(0, gridRow(options,
                Widgets.require(builder, "audio_only_check", CheckButton.class)));
        assertEquals(0, gridRow(options,
                Widgets.require(builder, "playlist_check", CheckButton.class)));
        assertEquals(1, gridRow(options,
                Widgets.require(builder, "subtitles_check", CheckButton.class)));
        assertDiskLabelBelowChooser(builder, "media_folder_chooser",
                "media_disk_space_label");
    }

    @Test
    @DisplayName("new-website.ui parses with expected ids")
    void newWebsite() {
        GtkBuilder builder = UiLoader.load("/ui/new-website.ui");
        Widgets.require(builder, "new_website_dialog", Window.class);
        Widgets.require(builder, "website_notebook", Notebook.class);
        Widgets.require(builder, "website_url_entry", Entry.class);
        Widgets.require(builder, "website_depth_spin", SpinButton.class);
        Widgets.require(builder, "website_scope_combo", DropDown.class);
        Widgets.require(builder, "website_external_depth_spin", SpinButton.class);
        Widgets.require(builder, "website_include_entry", Entry.class);
        Widgets.require(builder, "website_exclude_entry", Entry.class);
        Widgets.require(builder, "website_include_archives_check", CheckButton.class);
        Widgets.require(builder, "website_headers_entry", Entry.class);
        Widgets.require(builder, "website_cookie_file_button", Button.class);
        Widgets.require(builder, "website_clear_cookie_button", Button.class);
        Widgets.require(builder, "website_network_options_host", Box.class);
        Widgets.require(builder, "website_status_label", Label.class);
        Widgets.require(builder, "new_website_spinner", Spinner.class);
        Widgets.require(builder, "website_cancel_button", Button.class);
        Widgets.require(builder, "website_start_button", Button.class);
        Grid fields = Widgets.require(builder, "website_fields_grid", Grid.class);
        assertFieldGrid(fields);
        assertPrimaryTabMargins(fields);
        assertPrimaryTabMargins(Widgets.require(builder,
                "new_website_content", Box.class));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "website_url_entry", Entry.class)));
        assertEquals(1, gridColumn(fields,
                Widgets.require(builder, "website_cookie_file_box", Box.class)));
    }

    @Test
    @DisplayName("shared per-record network pane follows the Network tab columns")
    void sharedNetworkOptionsLayout() {
        GtkBuilder builder = UiLoader.load("/ui/network-options.ui");
        Box resourceRoot = Widgets.require(builder, "network_options_root", Box.class);
        Grid resourceColumns = Widgets.require(builder, "network_options_columns", Grid.class);
        assertSame(resourceColumns, resourceRoot.getFirstChild());
        for (String id : new String[]{"network_connections_spin",
                "network_retry_limit_spin", "network_retry_delay_spin",
                "network_download_limit_spin", "network_upload_limit_spin",
                "network_proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        for (String id : new String[]{"network_referer_entry",
                "network_cookie_entry", "network_user_agent_entry",
                "network_proxy_host_entry", "network_proxy_username_entry",
                "network_proxy_password_entry"}) {
            Widgets.require(builder, id, Entry.class);
        }
        Widgets.require(builder, "network_proxy_type_combo", DropDown.class);
        Widgets.require(builder, "network_tor_switch", Switch.class);
        StringList plainProxyTypes = Widgets.require(builder,
                "network_plain_proxy_types", StringList.class);
        assertEquals(3, plainProxyTypes.getNItems());
        assertEquals("None", plainProxyTypes.getString(0));
        assertEquals("HTTP", plainProxyTypes.getString(1));
        assertEquals("HTTPS", plainProxyTypes.getString(2));
        StringList allProxyTypes = Widgets.require(builder,
                "network_all_proxy_types", StringList.class);
        assertEquals(DialogOptions.PROXY_TYPES.length, allProxyTypes.getNItems());
        for (int index = 0; index < DialogOptions.PROXY_TYPES.length; index++) {
            assertEquals(DialogOptions.PROXY_TYPES[index], allProxyTypes.getString(index));
        }

        NetworkOptionsPane pane = new NetworkOptionsPane(new org.manager.GlobalSettings(),
                org.manager.download.Download.Type.WEBSITE_SCRAPING,
                org.manager.download.Download.Protocol.HTTPS);
        Grid columns = assertInstanceOf(Grid.class, pane.widget().getFirstChild());
        assertTrue(columns.getColumnHomogeneous());
        assertTrue(columns.getColumnSpacing() >= 16);
        assertOptionsMargins(columns);
        Switch torSwitch = firstDescendant(pane.widget(), Switch.class);
        assertNotNull(torSwitch);
        assertFalse(torSwitch.getHexpand(),
                "switch controls must keep their natural width");
        assertEquals(Align.START, torSwitch.getHalign(),
                "switch controls must align with the other option values");
        int leftColumns = 0;
        int rightColumns = 0;
        for (Widget child = columns.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            assertInstanceOf(Box.class, child);
            if (gridColumn(columns, child) == 0) {
                leftColumns++;
            } else if (gridColumn(columns, child) == 1) {
                rightColumns++;
            }
        }
        assertEquals(1, leftColumns);
        assertEquals(1, rightColumns);
    }

    @Test
    @DisplayName("shared Network Options loads defaults and updates protocol capabilities")
    void sharedNetworkOptionsDefaultsAndCapabilities() {
        org.manager.GlobalSettings settings = new org.manager.GlobalSettings();
        new org.manager.download.DownloadSettingsFactory.NetworkDefaults(
                7, 8, 512, 96, 3, "https://referrer.test/",
                "ODM test", "session=one").saveTo(settings);

        NetworkOptionsPane pane = new NetworkOptionsPane(settings,
                org.manager.download.Download.Type.ARIA2,
                org.manager.download.Download.Protocol.HTTPS);
        DialogOptions.NetworkValues values = pane.values();
        assertEquals(7, values.connections());
        assertEquals(8, values.maxRetries());
        assertEquals(512, values.downloadLimitKb());
        assertEquals(96, values.uploadLimitKb());
        assertEquals(3, values.retryDelaySeconds());
        assertEquals("https://referrer.test/", values.referer());
        assertEquals("ODM test", values.userAgent());
        assertEquals("session=one", values.cookie());
        assertTrue(pane.isSensitive(
                org.manager.download.ExternalToolSettings.Capability.CONNECTIONS));
        assertFalse(pane.isSensitive(
                org.manager.download.ExternalToolSettings.Capability.UPLOAD_LIMIT));
        assertFalse(pane.hasChanges());

        pane.updateCapabilities(settings, org.manager.download.Download.Type.ARIA2,
                org.manager.download.Download.Protocol.TORRENT);
        assertFalse(pane.isSensitive(
                org.manager.download.ExternalToolSettings.Capability.CONNECTIONS));
        assertTrue(pane.isSensitive(
                org.manager.download.ExternalToolSettings.Capability.UPLOAD_LIMIT));
        assertFalse(pane.isSensitive(
                org.manager.download.ExternalToolSettings.Capability.REFERER));
        assertFalse(pane.hasChanges(),
                "route-driven sensitivity changes must not look like user edits");
    }

    @Test
    @DisplayName("shared Network Options loads records and tracks mixed user edits")
    void sharedNetworkOptionsRecordEditing() {
        org.aria2.Aria2Settings firstSettings = new org.aria2.Aria2Settings();
        firstSettings.setMaxConnections(7);
        firstSettings.setDownloadLimitKB(512);
        firstSettings.setMaxRetries(8);
        firstSettings.setRetryDelaySeconds(3);
        firstSettings.setReferer("https://referrer.test/");
        firstSettings.setUserAgent("ODM test");
        firstSettings.setCookieHeader("Cookie: session=one");
        org.manager.download.Download first = new org.manager.download.Download(
                URI.create("https://example.test/one.iso"));
        first.setSettings(firstSettings);
        first.setUseProxy(true);
        first.setProxyAddress("https://alice:secret@proxy.test:8443");

        org.aria2.Aria2Settings secondSettings =
                (org.aria2.Aria2Settings) firstSettings.copy();
        secondSettings.setMaxConnections(4);
        org.manager.download.Download second = new org.manager.download.Download(
                URI.create("https://example.test/two.iso"));
        second.setSettings(secondSettings);
        second.setUseProxy(true);
        second.setProxyAddress("https://alice:secret@proxy.test:8443");

        NetworkOptionsPane pane = new NetworkOptionsPane(List.of(first, second));
        DialogOptions.NetworkValues values = pane.values();
        assertEquals(7, values.connections());
        assertEquals(512, values.downloadLimitKb());
        assertEquals("session=one", values.cookie());
        assertEquals(2, values.proxyTypeIndex());
        assertEquals("proxy.test", values.proxyHost());
        assertEquals(8443, values.proxyPort());
        assertTrue(pane.hasMixedValues());
        assertFalse(pane.hasChanges(),
                "loading existing or mixed values must not mark them dirty");

        SpinButton connections = firstDescendant(pane.widget(), SpinButton.class);
        assertNotNull(connections);
        connections.setValue(8);
        assertTrue(pane.hasChanges());
        assertTrue(pane.changedCapabilities().contains(
                org.manager.download.ExternalToolSettings.Capability.CONNECTIONS));
    }

    @Test
    @DisplayName("action-output.ui parses with its complete static layout")
    void actionOutput() {
        GtkBuilder builder = UiLoader.load("/ui/action-output.ui");
        Widgets.require(builder, "action_output_dialog", Window.class);
        Box content = Widgets.require(builder, "action_output_content", Box.class);
        assertPrimaryTabMargins(content);
        Widgets.require(builder, "action_output_action_label", Label.class);
        Widgets.require(builder, "action_output_result_label", Label.class);
        Widgets.require(builder, "action_output_scroller", ScrolledWindow.class);
        TextView output = Widgets.require(builder, "action_output_text_view", TextView.class);
        assertFalse(output.getEditable());
        assertTrue(output.getMonospace());
        Widgets.require(builder, "action_output_close_button", Button.class);
    }

    @Test
    @DisplayName("completion-command.ui parses with its complete static layout")
    void completionCommand() {
        GtkBuilder builder = UiLoader.load("/ui/completion-command.ui");
        Widgets.require(builder, "completion_command_dialog", Window.class);
        Box content = Widgets.require(builder, "completion_command_content", Box.class);
        assertPrimaryTabMargins(content);
        Widgets.require(builder, "completion_command_help_label", Label.class);
        Widgets.require(builder, "completion_command_entry", Entry.class);
        Widgets.require(builder, "completion_command_actions", Box.class);
        Widgets.require(builder, "completion_command_cancel_button", Button.class);
        Widgets.require(builder, "completion_command_save_button", Button.class);
    }

    @Test
    @DisplayName("import-remote.ui parses with its complete static layout")
    void remoteImport() {
        GtkBuilder builder = UiLoader.load("/ui/import-remote.ui");
        Widgets.require(builder, "remote_import_dialog", Window.class);
        Box content = Widgets.require(builder, "remote_import_content", Box.class);
        assertPrimaryTabMargins(content);
        Widgets.require(builder, "remote_import_help_label", Label.class);
        Widgets.require(builder, "remote_import_url_entry", Entry.class);
        Widgets.require(builder, "remote_import_status_label", Label.class);
        Widgets.require(builder, "remote_import_actions", Box.class);
        Widgets.require(builder, "remote_import_cancel_button", Button.class);
        Widgets.require(builder, "remote_import_start_button", Button.class);
    }

    @Test
    @DisplayName("property.ui parses with the shared Network Options host")
    void property() {
        GtkBuilder builder = UiLoader.load("/ui/property.ui");
        Widgets.require(builder, "property_dialog", Window.class);
        Notebook propertiesNotebook = Widgets.require(builder,
                "properties_notebook", Notebook.class);
        assertNotebookInset(propertiesNotebook);
        Widgets.require(builder, "property_options_scrolled", ScrolledWindow.class);
        Widgets.require(builder, "property_network_options_host", Box.class);
        assertNoEmbeddedNetworkOptions(builder);
        assertNull(builder.getObject("start_automatically_check"));
        assertNull(builder.getObject("move_torrent_check"));
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "apply_button", Button.class);
        Widgets.require(builder, "ok_button", Button.class);
    }

    @Test
    @DisplayName("about.ui parses")
    void about() {
        GtkBuilder builder = UiLoader.load("/ui/about.ui");
        Widgets.require(builder, "about_dialog", org.gnome.gtk.AboutDialog.class);
        assertTrue(AboutDialogPresenter.LOGO_RESOURCE.endsWith(".svg"));
        assertNotNull(AboutDialogPresenter.loadLogo(), "the SVG logo must load as a paintable");
    }

    @Test
    @DisplayName("start-shutdown.ui parses with 1:1 original ids")
    void startShutdown() {
        GtkBuilder builder = UiLoader.load("/ui/start-shutdown.ui");
        Widgets.require(builder, "startup_shutdown_dialog", Window.class);
        Widgets.require(builder, "odm_logo_image", org.gnome.gtk.Image.class);
        Widgets.require(builder, "status_message_label", Label.class);
        Widgets.require(builder, "progress_bar", ProgressBar.class);
    }

    @Test
    @DisplayName("user-facing dialogs are non-modal movable transient windows")
    void dialogsUseIndependentWindowPolicy() {
        String[][] dialogs = {
            {"/ui/about.ui", "about_dialog"},
            {"/ui/action-output.ui", "action_output_dialog"},
            {"/ui/completion-command.ui", "completion_command_dialog"},
            {"/ui/import-list.ui", "import_dialog"},
            {"/ui/import-remote.ui", "remote_import_dialog"},
            {"/ui/import-sequence.ui", "import_sequence_dialog"},
            {"/ui/new-download.ui", "new_download_dialog"},
            {"/ui/new-media.ui", "new_media_dialog"},
            {"/ui/new-website.ui", "new_website_dialog"},
            {"/ui/property.ui", "property_dialog"},
            {"/ui/settings.ui", "settings_dialog"}
        };
        for (String[] specification : dialogs) {
            Window dialog = Widgets.require(UiLoader.load(specification[0]),
                    specification[1], Window.class);
            assertFalse(dialog.getModal(), specification[0] + " must be movable independently");
            dialog.destroy();
        }

        Window parent = new Window();
        Window child = new Window();
        child.setModal(true);
        DialogSupport.configureIndependent(child, parent);
        assertFalse(child.getModal());
        assertSame(parent, child.getTransientFor());
        assertTrue(child.getDestroyWithParent());

        org.gnome.gtk.FileDialog fileDialog = new org.gnome.gtk.FileDialog();
        DialogSupport.configureIndependent(fileDialog);
        assertFalse(fileDialog.getModal());
        org.gnome.gtk.AlertDialog alertDialog = new org.gnome.gtk.AlertDialog();
        DialogSupport.configureIndependent(alertDialog);
        assertFalse(alertDialog.getModal());

        child.destroy();
        parent.destroy();
    }

    @Test
    @DisplayName("import-list.ui parses with 1:1 original ids")
    void importListStructure() {
        GtkBuilder builder = UiLoader.load("/ui/import-list.ui");
        Widgets.require(builder, "import_dialog", Window.class);
        Notebook importNotebook = Widgets.require(builder,
                "options_notebook", Notebook.class);
        assertNotebookInset(importNotebook);
        Box clipboardPage = Widgets.require(builder, "clipboard_page", Box.class);
        assertListTabMargins(clipboardPage);
        Widgets.require(builder, "filter_label", Label.class);
        Widgets.require(builder, "extension_filter_combo", MenuButton.class);
        Widgets.require(builder, "engine_label", Label.class);
        Widgets.require(builder, "engine_combo", DropDown.class);
        Widgets.require(builder, "url_treeview", TreeView.class);
        Widgets.require(builder, "url_liststore", ListStore.class);
        Widgets.require(builder, "mark_renderer", org.gnome.gtk.CellRendererToggle.class);
        assertEquals(2, Widgets.require(builder, "extension_column",
                TreeViewColumn.class).getSortColumnId(),
                "the imported URL extension must be sortable");
        Widgets.require(builder, "folder_destination", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_spinnet", Spinner.class);
        Widgets.require(builder, "item_count_label", Label.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        assertDiskLabelBelowChooser(builder, "folder_destination", "disk_space_label");
        // Options tab ids
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after", "proxy_port_spin"}) {
            Widgets.require(builder, id, SpinButton.class);
        }
        Widgets.require(builder, "tor_switch", org.gnome.gtk.Switch.class);
        assertNull(builder.getObject("start_automatically_check1"));
        assertNull(builder.getObject("move_torrent_check1"));
        assertDownloadOptionsLayout(builder);
    }

    @Test
    @DisplayName("import-sequence.ui parses with 1:1 original ids")
    void importSequence() {
        GtkBuilder builder = UiLoader.load("/ui/import-sequence.ui");
        Widgets.require(builder, "import_sequence_dialog", Window.class);
        assertNotebookInset(Widgets.require(builder, "main_notebook", Notebook.class));
        assertPrimaryTabMargins(Widgets.require(builder, "sequence_page", Box.class));
        Widgets.require(builder, "uri_entry", Entry.class);
        Widgets.require(builder, "num_start_spin", SpinButton.class);
        Widgets.require(builder, "num_vers_spin", SpinButton.class);
        Widgets.require(builder, "num_count_spin", SpinButton.class);
        Widgets.require(builder, "char_entry", Entry.class);
        Widgets.require(builder, "char_vers_entry", Entry.class);
        Widgets.require(builder, "num_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "char_combo", org.gnome.gtk.DropDown.class);
        Widgets.require(builder, "engine_label", Label.class);
        Widgets.require(builder, "engine_combo", DropDown.class);
        Widgets.require(builder, "preview_treeview", TreeView.class);
        Widgets.require(builder, "item_count_label", Label.class);
        Widgets.require(builder, "preview_liststore", ListStore.class);
        Widgets.require(builder, "destination_folder", MenuButton.class);
        Widgets.require(builder, "disk_space_label", Label.class);
        Widgets.require(builder, "import_sequence_spinner", Spinner.class);
        Widgets.require(builder, "cancel_button", Button.class);
        Widgets.require(builder, "validate_button", Button.class);
        Widgets.require(builder, "referrer_entry", Entry.class);
        Widgets.require(builder, "cookie_entry", Entry.class);
        Widgets.require(builder, "user_agent_entry", Entry.class);
        assertDiskLabelBelowChooser(builder, "destination_folder", "disk_space_label");
        assertNull(builder.getObject("start_automatically_check1"));
        assertNull(builder.getObject("move_torrent_check1"));
        assertDownloadOptionsLayout(builder);
    }

    @Test
    @DisplayName("Import URL Sequence constructs and presents from its runtime dependencies")
    void importSequenceDialogConstructsAndPresents() {
        org.manager.download.DownloadManager stub =
                (org.manager.download.DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                        org.manager.download.DownloadManager.class.getClassLoader(),
                        new Class<?>[]{org.manager.download.DownloadManager.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getGlobalSettings" -> new org.manager.GlobalSettings();
                            default -> defaultValue(method.getReturnType());
                        });
        Window parent = new Window();
        ImportSequenceDialog sequenceDialog = new ImportSequenceDialog(parent, stub, () -> { });
        try {
            sequenceDialog.present();
            assertTrue(sequenceDialog.isVisible());
        } finally {
            sequenceDialog.close();
            parent.close();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("import counts follow admitted URLs, marks, and extension filters")
    void importListCountsFollowSelection() throws Exception {
        var constructor = ImportListDialog.class.getDeclaredConstructor(Window.class,
                org.manager.download.DownloadManager.class, Runnable.class, List.class,
                ImportLimits.class);
        constructor.setAccessible(true);
        ImportListDialog imported = constructor.newInstance(null, emptyManager(), null,
                List.of("https://files.test/a.zip", "https://files.test/b.txt",
                        "https://files.test/c.zip", "https://files.test/a.zip",
                        "not a URL", "https://files.test/over-limit.zip"),
                new ImportLimits(3, 1));
        GtkBuilder builder = dialogBuilder(imported, "builder");
        Window dialog = Widgets.require(builder, "import_dialog", Window.class);
        Label count = Widgets.require(builder, "item_count_label", Label.class);
        var mark = Widgets.require(builder, "mark_renderer", org.gnome.gtk.CellRendererToggle.class);
        MenuButton filter = Widgets.require(builder, "extension_filter_combo", MenuButton.class);
        ScrolledWindow filterScroller = assertInstanceOf(ScrolledWindow.class, filter.getPopover().getChild());
        var filterViewport = assertInstanceOf(org.gnome.gtk.Viewport.class, filterScroller.getChild());
        Box choices = assertInstanceOf(Box.class, filterViewport.getChild());
        CheckButton all = assertInstanceOf(CheckButton.class, choices.getFirstChild());
        CheckButton zip = assertInstanceOf(CheckButton.class, all.getNextSibling());
        CheckButton txt = assertInstanceOf(CheckButton.class, zip.getNextSibling());
        DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
        try {
            imported.present();
            drainGtkEvents();
            assertTrue(engine.getMapped());
            assertEquals(0, engine.getSelected());
            assertEquals("Auto", ((org.gnome.gtk.StringObject) engine.getSelectedItem()).getString());
            assertEquals(4, engine.getModel().getNItems());
            SpinButton connections = Widgets.require(builder, "max_connections_spin", SpinButton.class);
            engine.setSelected(ImportEngine.YT_DLP.ordinal());
            assertEquals(64, connections.getAdjustment().getUpper());
            engine.setSelected(ImportEngine.HTTRACK.ordinal());
            assertEquals(8, connections.getAdjustment().getUpper());
            engine.setSelected(ImportEngine.AUTO.ordinal());
            assertTrue(connections.getSensitive());
            assertEquals(16, connections.getAdjustment().getUpper());
            assertTrue(count.getMapped());
            assertEquals("3 of 3 items selected", count.getLabel());
            mark.emitToggled("0");
            assertEquals("2 of 3 items selected", count.getLabel());

            all.setActive(false);
            assertEquals("0 of 3 items selected", count.getLabel());
            zip.setActive(true); // .zip marks the first and third rows.
            assertEquals("2 of 3 items selected", count.getLabel());
            assertTrue(all.getInconsistent());
            txt.setActive(true);
            assertTrue(all.getActive());
            assertFalse(all.getInconsistent());
            assertEquals("3 of 3 items selected", count.getLabel());
            txt.setActive(false);
            mark.emitToggled("0");
            mark.emitToggled("2");
            assertEquals("0 of 3 items selected", count.getLabel());
            all.setActive(true);
            assertEquals("3 of 3 items selected", count.getLabel());

            Widgets.require(builder, "options_notebook", Notebook.class).setCurrentPage(1);
            drainGtkEvents();
            assertTrue(count.getMapped(), "the count remains visible while editing import options");
        } finally {
            dialog.destroy();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("sequence counts track valid previews and configured limits")
    void importSequenceCountsFollowPreview() throws Exception {
        var settings = new org.manager.GlobalSettings();
        settings.setProperty(ImportLimits.MAX_URLS_KEY, "3");
        org.manager.download.DownloadManager manager =
                (org.manager.download.DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                        org.manager.download.DownloadManager.class.getClassLoader(),
                        new Class<?>[]{org.manager.download.DownloadManager.class},
                        (proxy, method, args) -> method.getName().equals("getGlobalSettings")
                                ? settings : defaultValue(method.getReturnType()));
        ImportSequenceDialog imported = new ImportSequenceDialog(null, manager, null);
        GtkBuilder builder = dialogBuilder(imported, "builder");
        Label count = Widgets.require(builder, "item_count_label", Label.class);
        Entry pattern = Widgets.require(builder, "uri_entry", Entry.class);
        SpinButton end = Widgets.require(builder, "num_vers_spin", SpinButton.class);
        ListStore preview = Widgets.require(builder, "preview_liststore", ListStore.class);
        try {
            imported.present();
            assertEquals("0 items", count.getLabel());
            Widgets.require(builder, "num_start_spin", SpinButton.class).setValue(1);
            end.setValue(10);
            Widgets.require(builder, "num_count_spin", SpinButton.class).setValue(100);
            pattern.setText("https://files.test/{}.zip");
            awaitGtk(() -> count.getLabel().equals("3 items"), "Limited preview was not counted");
            assertEquals(3, preview.iterNChildren(null));

            DropDown engine = Widgets.require(builder, "engine_combo", DropDown.class);
            assertTrue(engine.getMapped());
            assertEquals(4, engine.getModel().getNItems());
            assertEquals("Auto", ((org.gnome.gtk.StringObject) engine.getSelectedItem()).getString());
            SpinButton connections = Widgets.require(builder, "max_connections_spin", SpinButton.class);
            engine.setSelected(ImportEngine.YT_DLP.ordinal());
            assertEquals(64, connections.getAdjustment().getUpper());
            engine.setSelected(ImportEngine.HTTRACK.ordinal());
            assertEquals(8, connections.getAdjustment().getUpper());
            engine.setSelected(ImportEngine.AUTO.ordinal());
            assertEquals(16, connections.getAdjustment().getUpper());
            assertEquals("3 items", count.getLabel(), "engine selection keeps the current preview");

            end.setValue(1);
            assertEquals("0 items", count.getLabel(), "an obsolete preview clears immediately");
            awaitGtk(() -> count.getLabel().equals("1 item"), "Single-item preview was not counted");

            Widgets.require(builder, "char_entry", Entry.class).setText("a");
            Widgets.require(builder, "char_vers_entry", Entry.class).setText("z");
            Widgets.require(builder, "num_combo", DropDown.class).setSelected(1);
            awaitGtk(() -> count.getLabel().equals("3 items"), "Character preview was not counted");

            // Older asynchronous previews must not restore a count after invalid input.
            pattern.setText("https://files.test/new-{}.zip");
            pattern.setText("javascript:{}");
            awaitGtk(() -> !Widgets.require(builder, "import_sequence_spinner", Spinner.class)
                    .getSpinning(), "Preview work did not finish");
            assertEquals("0 items", count.getLabel());
            assertEquals(0, preview.iterNChildren(null));
            assertFalse(Widgets.require(builder, "validate_button", Button.class).getSensitive());
        } finally {
            imported.close();
            drainGtkEvents();
        }
    }

    private static GtkBuilder dialogBuilder(Object dialog, String fieldName) throws Exception {
        var field = dialog.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (GtkBuilder) field.get(dialog);
    }

    @Test
    @DisplayName("context copy writes every selected link to the GTK clipboard")
    void contextCopySupportsMixedSelectionsAndClipboardBypass() throws Exception {
        String url = "https://files.test/video%20name.mp4?token=a%2Bb";
        String magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                + "&dn=My%20release&tr=https%3A%2F%2Ftracker.test%2Fannounce";
        var downloads = List.of(new org.manager.download.Download(URI.create(url)),
                new org.manager.download.Download(URI.create(magnet)));
        AtomicReference<org.manager.clipboard.ClipboardService> clipboardService = new AtomicReference<>();
        AtomicReference<String> bypassed = new AtomicReference<>();
        org.manager.download.DownloadManager manager =
                (org.manager.download.DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                        org.manager.download.DownloadManager.class.getClassLoader(),
                        new Class<?>[]{org.manager.download.DownloadManager.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getGlobalSettings" -> new org.manager.GlobalSettings();
                            case "getDownloads", "getAllDownloads" -> downloads;
                            case "getDownloadsByStatus", "getDownloadSources",
                                    "getDownloadTrackers", "getDownloadPeers",
                                    "getDownloadFiles" -> List.of();
                            case "getDownloadCount" -> downloads.size();
                            case "getClipboardService" -> clipboardService.get();
                            default -> defaultValue(method.getReturnType());
                        });
        GdkClipboardMonitor monitor = new GdkClipboardMonitor();
        clipboardService.set(new org.manager.clipboard.ClipboardService(manager, monitor) {
            @Override
            public void bypassNextMonitoredContent(String text) {
                super.bypassNextMonitoredContent(text);
                bypassed.set(text);
            }
        });
        MainWindow window = new MainWindow(null, manager, new org.tor.TorService("tor"),
                new org.manager.schedule.ScheduleManager(manager));
        GtkBuilder builder = dialogBuilder(window, "uiBuilder");
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        try {
            window.present();
            awaitGtk(() -> tree.getModel().iterNChildren(null) == 2, "Downloads were not presented");
            tree.getSelection().selectAll();
            clickContextCopy(window, "Copy Links");
            assertEquals(url + "\n" + magnet, readClipboard(tree));
            assertEquals(url + "\n" + magnet, bypassed.get(),
                    "clipboard monitoring must bypass the complete batch, not just its first URI");

            tree.getSelection().unselectAll();
            tree.getSelection().selectPath(TreePath.fromString("0"));
            clickContextCopy(window, "Copy URL");
            assertEquals(url, readClipboard(tree));
            assertEquals(url, bypassed.get());

            tree.getSelection().unselectAll();
            tree.getSelection().selectPath(TreePath.fromString("1"));
            clickContextCopy(window, "Copy Magnet URI");
            assertEquals(magnet, readClipboard(tree));
        } finally {
            window.dispose();
            var stopped = monitor.stopMonitoring();
            awaitGtk(stopped::isDone, "Clipboard monitor did not stop");
            stopped.join();
            drainGtkEvents();
        }
    }

    private static void clickContextCopy(MainWindow window, String label) throws Exception {
        var show = MainWindow.class.getDeclaredMethod("showContextMenuAt", int.class, int.class);
        show.setAccessible(true);
        show.invoke(window, 0, 0);
        var field = MainWindow.class.getDeclaredField("contextMenu");
        field.setAccessible(true);
        PopupMenu menu = (PopupMenu) field.get(window);
        for (Widget item = menu.getPopover().getChild().getFirstChild(); item != null;
                item = item.getNextSibling()) {
            if (item instanceof Button button && button.getChild() instanceof Label text
                    && label.equals(text.getLabel())) {
                assertTrue(button.getSensitive(), label + " should be enabled");
                button.emitClicked();
                return;
            }
        }
        throw new AssertionError("Context menu did not contain " + label);
    }

    private static String readClipboard(Widget owner) throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var clipboard = owner.getClipboard();
        clipboard.readTextAsync(null, result -> {
            try {
                text.set(clipboard.readTextFinish(result));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        awaitGtk(() -> text.get() != null || failure.get() != null, "Clipboard read did not complete");
        assertNull(failure.get());
        return text.get();
    }

    @Test
    @DisplayName("MainWindow constructs against its .ui (catches require-type mismatches)")
    void mainWindowConstructs() {
        org.manager.download.DownloadManager stub = emptyManager();
        MainWindow window = new MainWindow(null, stub, new org.tor.TorService("tor"),
                new org.manager.schedule.ScheduleManager(stub));
        // Constructing is the test: every Widgets.require in the constructor
        // must resolve. (Null app: the window is a standalone toplevel here.)
        assertEquals(org.gnome.gtk.SelectionMode.MULTIPLE, window.downloadSelectionMode());
        assertTrue(window.downloadNameTooltipEnabled());
        org.gnome.gdk.Texture torStatusIcon = assertInstanceOf(
                org.gnome.gdk.Texture.class, window.torStatusIcon());
        assertEquals(DownloadEnginePresentation.TOR_STATUS_ICON_SIZE, torStatusIcon.getWidth());
        assertEquals(DownloadEnginePresentation.TOR_STATUS_ICON_SIZE, torStatusIcon.getHeight());
        assertFalse(window.torStatusIconVisible());
        assertFalse(window.torEnabledActionState());
        assertFalse(window.menuActionEnabled("tor-new-identity"));
        assertTrue(window.mainMenuSubmenuContainsAction("_Edit", "win.tor-enabled"));
        assertTrue(window.mainMenuSubmenuContainsAction("_Edit", "win.tor-new-identity"));
        assertEquals(PropagationPhase.CAPTURE, window.downloadContextClickPhase(),
                "right-click handling must run before TreeView child gestures consume it");
        assertEquals(List.of("#", "Status", "Name", "Completed", "Size", "Progress",
                "Elapsed", "Left", "Down Speed", "Up Speed", "Ratio", "Retry", "Start Date",
                "End Date", "Result"), MainWindow.downloadColumnLabels());
        assertEquals(5, window.mainMenuTopLevelCount());
        assertTrue(window.mainMenuSubmenuContainsAction(
                "_Download", "win.download-subtitles"));
        for (String key : List.of("notify", "desktop-notify", "antivirus", "subtitles",
                "suspend", "shutdown", "custom")) {
            assertNull(window.menuActionParameterType("completion-" + key));
            assertTrue(window.menuActionEnabled("completion-" + key));
        }
        assertEquals("s", window.menuActionParameterType("schedule"));
        assertTrue(window.menuActionEnabled("schedule"));
        assertTrue(window.menuActionEnabled("select-all"));
        assertFalse(window.menuActionEnabled("open-file"));
        assertFalse(window.menuActionEnabled("open-folder"));
        assertFalse(window.menuActionEnabled("download-subtitles"));
        window.dispose();
    }

    @Test
    @DisplayName("Tor menu and status icon follow service state, including while hidden")
    void torBootstrapProgressUpdatesMenuAndStatusBar() throws Exception {
        AtomicBoolean running = new AtomicBoolean();
        AtomicBoolean starting = new AtomicBoolean(true);
        AtomicInteger progress = new AtomicInteger();
        var listeners = new java.util.concurrent.CopyOnWriteArrayList<org.tor.TorService.TorServiceListener>();
        org.tor.TorService service = org.mockito.Mockito.mock(org.tor.TorService.class);
        org.mockito.Mockito.when(service.isRunning()).thenAnswer(ignored -> running.get());
        org.mockito.Mockito.when(service.isStarting()).thenAnswer(ignored -> starting.get());
        org.mockito.Mockito.when(service.getBootstrapProgress())
                .thenAnswer(ignored -> progress.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            listeners.add(invocation.getArgument(0));
            return null;
        }).when(service).addListener(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doAnswer(invocation -> {
            listeners.remove(invocation.getArgument(0));
            return null;
        }).when(service).removeListener(org.mockito.ArgumentMatchers.any());

        org.manager.download.DownloadManager stub = emptyManager();
        MainWindow window = new MainWindow(null, stub, service,
                new org.manager.schedule.ScheduleManager(stub));
        window.present();
        try {
            awaitGtk(() -> listeners.size() == 2,
                    "the main window did not subscribe to Tor lifecycle events");
            assertTrue(window.torEnabledActionState());
            assertFalse(window.torStatusIconVisible());
            assertFalse(window.menuActionEnabled("tor-new-identity"));

            progress.set(37);
            listeners.forEach(listener -> listener.onServiceEvent(org.tor.TorService.TorServiceEvent.BOOTSTRAP_PROGRESS));
            awaitGtk(() -> "Tor bootstrap: 37%".equals(window.statusMessage()),
                    "Tor bootstrap progress was not displayed in the status label");
            assertFalse(window.torStatusIconVisible());
            assertFalse(window.menuActionEnabled("tor-new-identity"));

            progress.set(100);
            starting.set(false);
            running.set(true);
            listeners.forEach(listener -> listener.onServiceEvent(org.tor.TorService.TorServiceEvent.BOOTSTRAP_COMPLETE));
            awaitGtk(() -> "Tor bootstrap: 100%".equals(window.statusMessage()),
                    "Tor bootstrap completion was not displayed");
            assertTrue(window.torStatusIconVisible());
            assertTrue(window.menuActionEnabled("tor-new-identity"));

            listeners.forEach(listener -> listener.onServiceEvent(org.tor.TorService.TorServiceEvent.STARTED));
            awaitGtk(() -> "Tor service started".equals(window.statusMessage()),
                    "Tor running state was not displayed");
            var nativeWindowField = MainWindow.class.getDeclaredField("window");
            nativeWindowField.setAccessible(true);
            var builderField = MainWindow.class.getDeclaredField("uiBuilder");
            builderField.setAccessible(true);
            Label ipLabel = Widgets.require((GtkBuilder) builderField.get(window), "tor_ip_label", Label.class);
            ipLabel.setLabel("🇳🇱 192.0.2.1");
            ipLabel.setVisible(true);
            ((org.gnome.gtk.Window) nativeWindowField.get(window)).setVisible(false);
            drainGtkEvents();
            assertEquals(2, listeners.size(), "tray hiding must retain Tor monitoring");
            running.set(false);
            listeners.forEach(listener -> listener.onServiceEvent(org.tor.TorService.TorServiceEvent.STOPPED));
            awaitGtk(() -> "Tor service stopped".equals(window.statusMessage()),
                    "Tor stopped state was not displayed");
            assertFalse(window.torEnabledActionState());
            assertFalse(window.torStatusIconVisible());
            assertFalse(ipLabel.getVisible());
            assertEquals("", ipLabel.getLabel());
            assertFalse(window.menuActionEnabled("tor-new-identity"));
        } finally {
            window.dispose();
            drainGtkEvents();
        }
        awaitGtk(() -> listeners.isEmpty(),
                "the main window did not release its Tor lifecycle listener");
    }

    @Test
    @DisplayName("Tor verification updates the icon's IP label and leaves the main status available")
    void torStatusIconOnlyVerifiesConnection() throws Exception {
        org.tor.TorService service = org.mockito.Mockito.mock(org.tor.TorService.class);
        org.mockito.Mockito.when(service.isRunning()).thenReturn(true);
        var checks = new java.util.ArrayList<
                java.util.concurrent.CompletableFuture<org.tor.TorCircuitMonitor.Result>>();
        var lastNotification = new java.util.concurrent.atomic.AtomicReference<String>();
        var application = new org.gnome.gtk.Application("org.odm.TorMonitorTest",
                org.gnome.gio.ApplicationFlags.NON_UNIQUE);
        assertTrue(application.register(null));
        try (var notifications = org.mockito.Mockito.mockStatic(TorFailureNotification.class);
                var monitors = org.mockito.Mockito.mockConstruction(org.tor.TorCircuitMonitor.class,
                (monitor, context) -> {
                    @SuppressWarnings("unchecked")
                    var onStarted = (java.util.function.Consumer<java.util.concurrent.CompletableFuture<
                            org.tor.TorCircuitMonitor.Result>>) context.arguments().get(3);
                    org.mockito.Mockito.when(monitor.checkNow()).thenAnswer(invocation -> {
                        var check = new java.util.concurrent.CompletableFuture<org.tor.TorCircuitMonitor.Result>();
                        checks.add(check);
                        onStarted.accept(check);
                        return check;
                    });
                    org.mockito.Mockito.when(monitor.isCurrent(
                            org.mockito.ArgumentMatchers.any(org.tor.TorCircuitMonitor.Result.class)))
                            .thenReturn(true);
                })) {
            notifications.when(() -> TorFailureNotification.send(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(call -> {
                        lastNotification.set(call.getArgument(1));
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    });
            org.manager.download.DownloadManager stub = emptyManager();
            MainWindow window = new MainWindow(application, stub, service,
                    new org.manager.schedule.ScheduleManager(stub));
            try {
                var builderField = MainWindow.class.getDeclaredField("uiBuilder");
                builderField.setAccessible(true);
                GtkBuilder builder = (GtkBuilder) builderField.get(window);
                Button button = Widgets.require(builder, "tor_check_button", Button.class);
                Label ipLabel = Widgets.require(builder, "tor_ip_label", Label.class);
                ApplicationWindow nativeWindow = Widgets.require(builder, "main_window", ApplicationWindow.class);
                assertTrue(button.getVisible());
                assertTrue(button.getSensitive());
                assertFalse(ipLabel.getVisible());
                awaitGtk(() -> "0 downloads".equals(window.statusMessage()),
                        "the initial download status was not displayed");
                for (int click = 0; click < 2; click++) {
                    button.emitClicked();
                    assertEquals(click + 1, checks.size());
                    awaitGtk(() -> "Verifying Tor connection…".equals(window.statusMessage()),
                            "clicking the status icon did not start verification");
                    assertTrue(window.activitySpinning());
                    assertFalse("Verifying Tor connection…".equals(button.getTooltipText()));
                    assertTrue(nativeWindow.activateActionVariant("win.select-all", null));
                    assertEquals("Verifying Tor connection…", window.statusMessage(),
                            "selection changes must preserve verification progress until the check finishes");
                    var result = new org.tor.TorCircuitMonitor.Result(true,
                            "Tor circuit verified", "192.0.2." + (click + 1), "NL", 1, false);
                    checks.get(click).complete(result);
                    awaitGtk(() -> ("🇳🇱 " + result.ip()).equals(ipLabel.getLabel()),
                            "the new verification result was not displayed beside the Tor icon");
                    assertTrue(ipLabel.getVisible());
                    assertFalse(window.activitySpinning());
                    assertEquals("0 downloads", window.statusMessage());
                    assertTrue(nativeWindow.activateActionVariant("win.select-all", null));
                    assertEquals("0 downloads", window.statusMessage(),
                            "verification must not hold the main status label during selection changes");
                }
                button.emitClicked();
                var failure = new org.tor.TorCircuitMonitor.Result(false,
                        "Unable to verify the Tor circuit", null, null, 1, false);
                checks.getLast().complete(failure);
                awaitGtk(() -> MainWindow.torCheckStatus(failure).equals(button.getTooltipText()),
                        "verification failure was not displayed on the Tor control");
                assertFalse(ipLabel.getVisible(), "a failed check must clear the previous verified IP");
                assertEquals("", ipLabel.getLabel());
                assertFalse(window.statusMessage().contains("Offline Mode"));
                assertFalse(stub.getGlobalSettings().getBooleanProperty("ui.offline", false));
                notifications.verifyNoInteractions();
                assertTrue(nativeWindow.activateActionVariant("win.select-all", null));
                assertEquals("0 downloads", window.statusMessage());
                button.emitClicked();
                awaitGtk(() -> "Verifying Tor connection…".equals(window.statusMessage()),
                        "verification progress was not displayed");
                checks.getLast().complete(null);
                awaitGtk(() -> "0 downloads".equals(window.statusMessage()),
                        "a canceled check must release the status label");
                org.mockito.Mockito.verify(monitors.constructed().getFirst(), org.mockito.Mockito.times(4))
                        .checkNow();
                org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
                        .createController(org.mockito.ArgumentMatchers.anyInt());

                var monitorCheck = new java.util.concurrent.CompletableFuture<org.tor.TorCircuitMonitor.Result>();
                var onStarted = MainWindow.class.getDeclaredMethod(
                        "onTorCheckStarted", java.util.concurrent.CompletableFuture.class);
                onStarted.setAccessible(true);
                onStarted.invoke(window, monitorCheck);
                var automaticFailure = new org.tor.TorCircuitMonitor.Result(false,
                        "Unable to verify the Tor circuit", null, null, 1, true);
                stub.getGlobalSettings().setProperty("ui.offline", "true");
                monitorCheck.complete(automaticFailure);
                awaitGtk(() -> MainWindow.torCheckStatus(automaticFailure).equals(lastNotification.get()),
                        "an automatic failure must notify even when there are no downloads");
                notifications.verify(() -> TorFailureNotification.send(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(MainWindow.torCheckStatus(automaticFailure))));
            } finally {
                window.dispose();
                drainGtkEvents();
                application.quit();
            }
        }
    }

    @Test
    @DisplayName("New Tor Identity verifies the connection after an authenticated successful request")
    void newTorIdentityUsesManagedServiceController() throws Exception {
        org.tor.TorService service = org.mockito.Mockito.mock(org.tor.TorService.class);
        org.tor.TorController controller = org.mockito.Mockito.mock(org.tor.TorController.class);
        org.mockito.Mockito.when(service.isRunning()).thenReturn(true);
        org.mockito.Mockito.when(service.createController(5000)).thenReturn(controller);
        org.mockito.Mockito.when(controller.connect())
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
        var identityChange = new java.util.concurrent.CompletableFuture<Boolean>();
        org.mockito.Mockito.when(controller.changeIp()).thenReturn(identityChange);
        var verification = new java.util.concurrent.CompletableFuture<org.tor.TorCircuitMonitor.Result>();
        try (var monitors = org.mockito.Mockito.mockConstruction(org.tor.TorCircuitMonitor.class,
                (monitor, context) -> {
                    @SuppressWarnings("unchecked")
                    var onStarted = (java.util.function.Consumer<java.util.concurrent.CompletableFuture<
                            org.tor.TorCircuitMonitor.Result>>) context.arguments().get(3);
                    org.mockito.Mockito.when(monitor.checkNow()).thenAnswer(invocation -> {
                        onStarted.accept(verification);
                        return verification;
                    });
                    org.mockito.Mockito.when(monitor.isCurrent(
                            org.mockito.ArgumentMatchers.any(org.tor.TorCircuitMonitor.Result.class)))
                            .thenReturn(true);
                })) {
            org.manager.download.DownloadManager stub = emptyManager();
            MainWindow window = new MainWindow(null, stub, service,
                    new org.manager.schedule.ScheduleManager(stub));
            try {
                var builderField = MainWindow.class.getDeclaredField("uiBuilder");
                builderField.setAccessible(true);
                GtkBuilder builder = (GtkBuilder) builderField.get(window);
                ApplicationWindow nativeWindow = Widgets.require(builder, "main_window", ApplicationWindow.class);
                Label ipLabel = Widgets.require(builder, "tor_ip_label", Label.class);
                var monitor = monitors.constructed().getFirst();
                assertTrue(nativeWindow.activateActionVariant("win.tor-new-identity", null));
                awaitGtk(window::activitySpinning,
                        "the identity request did not display activity");
                assertEquals("Requesting new Tor identity…", window.statusMessage());
                assertFalse(window.menuActionEnabled("tor-new-identity"));
                org.mockito.Mockito.verify(monitor, org.mockito.Mockito.never()).checkNow();

                identityChange.complete(true);
                awaitGtk(() -> "Verifying Tor connection…".equals(window.statusMessage()),
                        "a successful identity request did not start verification");
                assertTrue(window.activitySpinning());
                assertTrue(window.menuActionEnabled("tor-new-identity"));
                var order = org.mockito.Mockito.inOrder(monitor);
                order.verify(monitor).suspend();
                order.verify(monitor).resume();
                order.verify(monitor).checkNow();
                org.mockito.Mockito.verify(service).createController(5000);
                org.mockito.Mockito.verify(controller).connect();
                org.mockito.Mockito.verify(controller).changeIp();
                org.mockito.Mockito.verify(controller).shutdown();

                verification.complete(new org.tor.TorCircuitMonitor.Result(true,
                        "Tor circuit verified", "192.0.2.1", "NL", 1, false));
                awaitGtk(() -> "🇳🇱 192.0.2.1".equals(ipLabel.getLabel()),
                        "verification did not refresh the flag and IP beside the Tor icon");
                assertTrue(ipLabel.getVisible());
                assertEquals("0 downloads", window.statusMessage());
                assertFalse(window.activitySpinning());

                org.mockito.Mockito.when(controller.changeIp())
                        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(false));
                assertTrue(nativeWindow.activateActionVariant("win.tor-new-identity", null));
                awaitGtk(() -> "New Tor identity unavailable (Tor control request failed)"
                        .equals(window.statusMessage()), "the failed identity request was not displayed");
                org.mockito.Mockito.verify(monitor, org.mockito.Mockito.times(1)).checkNow();
                assertFalse(window.activitySpinning());
            } finally {
                window.dispose();
                drainGtkEvents();
            }
        }
    }

    @Test
    @DisplayName("File Exit asks before running the one-shot shutdown delegate")
    void fileExitRequiresConfirmation() {
        org.manager.download.DownloadManager stub = emptyManager();
        MainWindow window = new MainWindow(null, stub, new org.tor.TorService("tor"),
                new org.manager.schedule.ScheduleManager(stub));
        java.util.concurrent.atomic.AtomicInteger exits =
                new java.util.concurrent.atomic.AtomicInteger();
        window.setFinalCloseDelegate(exits::incrementAndGet);
        try {
            window.requestExitFromMenu();
            org.gnome.gtk.MessageDialog first = window.exitConfirmationDialog();
            assertNotNull(first);
            assertFalse(first.getModal());
            assertTrue(first.getDestroyWithParent());
            assertNotNull(first.getTransientFor());
            assertEquals(0, exits.get());

            window.requestExitFromMenu();
            assertSame(first, window.exitConfirmationDialog(),
                    "repeated menu activation must focus one confirmation");
            first.response(org.gnome.gtk.ResponseType.CANCEL.getValue());
            assertNull(window.exitConfirmationDialog());
            assertFalse(window.hasStartedFinalExit());
            assertEquals(0, exits.get());

            window.requestExitFromMenu();
            org.gnome.gtk.MessageDialog accepted = window.exitConfirmationDialog();
            assertNotNull(accepted);
            accepted.response(org.gnome.gtk.ResponseType.ACCEPT.getValue());
            assertNull(window.exitConfirmationDialog());
            assertTrue(window.hasStartedFinalExit());
            assertEquals(1, exits.get());
        } finally {
            window.dispose();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("download tooltips describe only the hovered Name or Type cell")
    void downloadTooltipsDescribeHoveredCell() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        TreeIter row = new TreeIter();
        store.append(row);
        String fullName = "A very long download record name that is visually ellipsized.mkv";
        ListStoreCells.setString(store, row, 1, fullName);
        TreePath path = TreePath.first();
        TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
        TreeViewColumn size = Widgets.require(builder, "size_column", TreeViewColumn.class);
        TreeViewColumn result = Widgets.require(builder, "tor_icon_column", TreeViewColumn.class);

        assertEquals(fullName,
                MainWindow.downloadNameTooltip(store, path, name, name));
        assertNull(MainWindow.downloadNameTooltip(store, path, size, name),
                "other columns must not display the download-name tooltip");
        org.manager.download.Download pending = new org.manager.download.Download(
                URI.create("https://example.com/pending.bin"));
        assertNull(MainWindow.downloadResultTooltip(pending, result, result),
                "records without action results must not expose an engine tooltip");
        org.manager.download.Download completed = new org.manager.download.Download(
                URI.create("https://example.com/completed.bin"));
        completed.setCompletionActionResults(List.of(new
                org.manager.download.action.CompletionActionResult(
                        "completed-action",
                        org.manager.download.action.AfterCompletionAction.ActionType.PLAY_SOUND,
                        "Notification",
                        org.manager.download.action.CompletionActionResult.Status.SUCCEEDED,
                        "Played", org.manager.download.action.AfterCompletionAction.Severity.LOW,
                        java.time.Instant.now(), java.time.Instant.now())));
        assertEquals("All after-completion actions succeeded (1/1)",
                MainWindow.downloadResultTooltip(completed, result, result));
        assertNull(MainWindow.downloadResultTooltip(completed, name, result));
    }

    @Test
    @DisplayName("Name stays capped without an empty column after Result")
    void nameColumnKeepsMaximumWidth() throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ApplicationWindow window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
        TreeViewColumn endDate = Widgets.require(builder, "end_date_column", TreeViewColumn.class);
        TreeViewColumn result = Widgets.require(builder, "tor_icon_column", TreeViewColumn.class);
        Widgets.require(builder, "download_store", ListStore.class).append(new TreeIter());
        window.setDefaultSize(1000, 640);
        window.present();
        try {
            awaitGtk(() -> tree.getWidth() > 600 && result.getWidth() > 0,
                    "GTK did not allocate the wide download table");
            assertEquals(MainWindow.downloadColumnLabels().size(), tree.getColumns().size(),
                    "the download table must contain only its selectable data columns");
            assertSame(result, tree.getColumns().getLast(),
                    "no empty column should appear after Result");
            assertEquals(420, name.getWidth());
            assertEquals(40, result.getWidth(), "Result must not fill unused space at the right edge");
            for (TreeViewColumn column : tree.getColumns()) {
                if (!column.handle().equals(name.handle())
                        && !column.handle().equals(endDate.handle())
                        && !column.handle().equals(result.handle())) {
                    column.setVisible(false);
                }
            }
            // Force another layout after the visibility updates have settled.
            for (int i = 0; i < 10; i++) {
                drainGtkEvents();
                Thread.sleep(10);
            }
            assertEquals(40, result.getWidth());
            assertEquals(420, name.getWidth(),
                    "Name must keep its cap when other columns are hidden");
            assertEquals(tree.getWidth(), name.getWidth() + endDate.getWidth() + result.getWidth(),
                    "the visible data columns must fill the table");
            endDate.setVisible(false);
            awaitGtk(() -> name.getWidth() + result.getWidth() == tree.getWidth(),
                    "GTK did not redistribute space after hiding the date columns");
            assertEquals(420, name.getWidth(),
                    "Name must keep its cap even without an expandable date column");
            assertEquals("Result", MainWindow.downloadColumnLabels().get(14),
                    "the visibility selector must keep the full label");
        } finally {
            window.destroy();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("the first download row resolves from right-click widget coordinates")
    void firstDownloadRowContextHitTesting() throws Exception {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ApplicationWindow window = Widgets.require(builder, "main_window", ApplicationWindow.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        store.append(new TreeIter());

        window.present();
        try {
            awaitGtk(() -> tree.getWidth() > 0 && tree.getHeight() > 0,
                    "GTK did not allocate the download tree");
            TreePath first = TreePath.fromString("0");
            TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
            Rectangle cell = new Rectangle();
            tree.getCellArea(first, name, cell);
            Out<Integer> widgetX = new Out<>();
            Out<Integer> widgetY = new Out<>();
            tree.convertBinWindowToWidgetCoords(
                    cell.readX() + Math.max(1, cell.readWidth() / 2),
                    cell.readY() + Math.max(1, cell.readHeight() / 2), widgetX, widgetY);

            TreePath resolved = MainWindow.pathAtWidgetPosition(
                    tree, widgetX.get(), widgetY.get(), new Out<>());

            assertNotNull(resolved, "row zero must not be lost behind the header offset");
            assertArrayEquals(new int[]{0}, resolved.getIndices());
        } finally {
            window.close();
            drainGtkEvents();
        }
    }

    @Test
    @DisplayName("sorting by raw size keeps visible rows mapped to their Downloads")
    void typedDownloadSortingPreservesRowIdentity() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore statusStore = Widgets.require(builder, "status_store", ListStore.class);
        ListStore categoryStore = Widgets.require(builder, "category_store", ListStore.class);
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        ListStore globalProgressStore = Widgets.require(builder,
                "global_progress_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(statusStore, categoryStore,
                downloadStore, globalProgressStore,
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download small = new org.manager.download.Download(
                URI.create("https://example.com/small.bin"));
        small.setName("small.bin");
        small.setSize(100);
        small.setStatus(org.manager.download.Download.Status.QUEUED);
        org.manager.download.Download large = new org.manager.download.Download(
                URI.create("https://example.com/large.bin"));
        large.setName("large.bin");
        large.setSize(200);
        large.setStatus(org.manager.download.Download.Status.QUEUED);

        presenter.refresh(List.of(small, large));
        ((TreeSortable) downloadStore).setSortColumnId(18, SortType.DESCENDING);
        assertSame(large, presenter.rowAt(0));

        small.setSize(300);
        presenter.refresh(List.of(small, large));
        assertSame(small, presenter.rowAt(0),
                "an in-place update may reorder the GTK model without corrupting identity");
        small.setRetryCount(2);
        large.setRetryCount(12);
        small.setErrorMessage("Z error");
        large.setErrorMessage("A error");
        presenter.refresh(List.of(small, large));
        ((TreeSortable) downloadStore).setSortColumnId(8, SortType.ASCENDING);
        assertSame(small, presenter.rowAt(0), "Retries must sort numerically, independently of errors");
        ((TreeSortable) downloadStore).setSortColumnId(8, SortType.DESCENDING);
        assertSame(large, presenter.rowAt(0));
    }

    @Test
    void tableHeadersProvideDragAndDropWithoutReorderingRows() {
        for (String resource : List.of("main-window", "new-download", "new-media",
                "search-torrents", "import-list", "import-sequence", "jackett-settings", "sources")) {
            GtkBuilder builder = UiLoader.load("/ui/" + resource + ".ui");
            try {
                for (var object : UiLoader.objects(builder)) {
                    if (!(object instanceof TreeView tree)) {
                        continue;
                    }
                    var columns = tree.getColumns();
                    for (var column : columns) {
                        var controllers = column.getButton().observeControllers();
                        boolean source = false;
                        boolean target = false;
                        for (int i = 0; i < controllers.getNItems(); i++) {
                            source |= controllers.getItem(i) instanceof org.gnome.gtk.DragSource;
                            target |= controllers.getItem(i) instanceof org.gnome.gtk.DropTarget;
                        }
                        assertEquals(tree.getHeadersVisible(), source, resource);
                        assertEquals(tree.getHeadersVisible(), target, resource);
                    }
                    if (tree.getHeadersVisible() && columns.size() > 1) {
                        var model = tree.getModel();
                        var last = columns.getLast();
                        tree.moveColumnAfter(last, null);
                        assertSame(last, tree.getColumns().getFirst(), resource);
                        assertSame(model, tree.getModel(), "moving columns must preserve the row model");
                    }
                }
            } finally {
                for (var object : UiLoader.objects(builder)) {
                    if (object instanceof Window window) {
                        window.destroy();
                    }
                }
            }
        }
    }

    @Test
    void ratioVisibilityActionFollowsTheColumnAfterMovingIt() throws Exception {
        var manager = emptyManager();
        MainWindow window = new MainWindow(null, manager, new org.tor.TorService("tor"),
                new org.manager.schedule.ScheduleManager(manager));
        try {
            var field = MainWindow.class.getDeclaredField("uiBuilder");
            field.setAccessible(true);
            GtkBuilder builder = (GtkBuilder) field.get(window);
            ApplicationWindow nativeWindow = Widgets.require(builder, "main_window", ApplicationWindow.class);
            TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
            TreeViewColumn ratio = Widgets.require(builder, "ratio_column", TreeViewColumn.class);
            TreeViewColumn name = Widgets.require(builder, "name_column", TreeViewColumn.class);
            int ratioIndex = MainWindow.downloadColumnLabels().indexOf("Ratio");
            var action = nativeWindow.lookupAction("col-" + ratioIndex);
            assertTrue(window.mainMenuSubmenuContainsAction("_View", "win.col-" + ratioIndex));
            assertFalse(ratio.getVisible());
            assertFalse(action.getState().getBoolean());
            assertTrue(nativeWindow.activateActionVariant("win.col-" + ratioIndex, null));
            assertTrue(ratio.getVisible());
            tree.moveColumnAfter(ratio, null);
            assertSame(ratio, tree.getColumns().getFirst());
            assertTrue(nativeWindow.activateActionVariant("win.col-" + ratioIndex, null));
            assertFalse(ratio.getVisible());
            assertFalse(action.getState().getBoolean());
            assertTrue(name.getVisible());
            assertTrue(nativeWindow.activateActionVariant("win.col-" + ratioIndex, null));
            assertTrue(ratio.getVisible());
            assertTrue(action.getState().getBoolean());
            assertSame(ratio, tree.getColumns().getFirst());
        } finally {
            window.dispose();
        }
    }

    @Test
    void ratiosSortNumericallyAndUpdateWhileSeeding() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        try {
            ListStore store = Widgets.require(builder, "download_store", ListStore.class);
            var presenter = new DownloadListPresenter(
                    Widgets.require(builder, "status_store", ListStore.class),
                    Widgets.require(builder, "category_store", ListStore.class), store,
                    Widgets.require(builder, "global_progress_store", ListStore.class),
                    Widgets.require(builder, "status_treeview", TreeView.class),
                    Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
            var small = new org.manager.download.Download(URI.create("https://example.test/small.torrent"));
            var large = new org.manager.download.Download(URI.create("https://example.test/large.torrent"));
            var direct = new org.manager.download.Download(URI.create("https://example.test/normal.bin"));
            small.setDownloaded(1_000);
            small.setUploaded(2_000);
            small.setStatus(org.manager.download.Download.Status.SEEDING);
            large.setDownloaded(1_000);
            large.setUploaded(10_000);
            presenter.refresh(List.of(small, large, direct));
            TreeViewColumn ratio = Widgets.require(builder, "ratio_column", TreeViewColumn.class);
            ((TreeSortable) store).setSortColumnId(ratio.getSortColumnId(), SortType.DESCENDING);
            assertSame(large, presenter.rowAt(0));
            TreeIter row = new TreeIter();
            assertTrue(store.getIterFirst(row));
            ratio.cellSetCellData(store, row, false, false);
            CellRendererText renderer = assertInstanceOf(CellRendererText.class, ratio.getCells().getFirst());
            assertEquals("10.00", renderer.getProperty("text"));
            small.setUploaded(12_500);
            presenter.refresh(List.of(small, large, direct));
            assertSame(small, presenter.rowAt(0));
            assertTrue(store.getIterFirst(row));
            ratio.cellSetCellData(store, row, false, false);
            assertEquals("12.50", renderer.getProperty("text"));
            ((TreeSortable) store).setSortColumnId(ratio.getSortColumnId(), SortType.ASCENDING);
            assertSame(direct, presenter.rowAt(0));
            assertTrue(store.getIterFirst(row));
            ratio.cellSetCellData(store, row, false, false);
            assertEquals("—", renderer.getProperty("text"));
        } finally {
            Widgets.require(builder, "main_window", Window.class).destroy();
        }
    }

    @Test
    @DisplayName("upload rate is visible in download rows and aggregate totals")
    void uploadRatePopulatesRowAndSummary() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                downloadStore,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download download = new org.manager.download.Download(
                URI.create("magnet:?xt=urn:btih:abababababababababababababababababababab"));
        download.setStatus(org.manager.download.Download.Status.DOWNLOADING);
        download.setUploadSpeed(2_048);
        download.setRetryCount(12);
        download.setErrorMessage("HTTP 503: unavailable");

        DownloadListPresenter.RefreshSummary summary = presenter.refresh(List.of(download));

        TreeIter row = new TreeIter();
        assertTrue(downloadStore.getIterFirst(row));
        assertEquals("2 KB/s", ListStoreCells.getString(downloadStore, row, 7));
        assertEquals(12, ListStoreCells.getInt(downloadStore, row, 8));
        assertEquals(2_048, summary.upBytesPerSec());
        download.setUploadSpeed(0);
        presenter.refresh(List.of(download));
        assertEquals("0 B/s", ListStoreCells.getString(downloadStore, row, 7),
                "zero upload must remain visible instead of becoming an ambiguous dash");
    }

    @Test
    void sourcesTabDropsStaleResultsAndShowsLiveState() throws Exception {
        var manager = org.mockito.Mockito.mock(org.manager.download.DownloadManager.class);
        var selected = new java.util.concurrent.atomic.AtomicReference<org.manager.download.Download>();
        var first = new org.manager.download.Download(URI.create("https://example.test/first"));
        var second = new org.manager.download.Download(URI.create("https://example.test/second"));
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.when(manager.getDownloadSources(first)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return List.of(new org.manager.download.DownloadSourceFile("old-gid", 1, "", "old", List.of()));
        });
        org.mockito.Mockito.when(manager.getDownloadSources(second)).thenReturn(List.of(
                new org.manager.download.DownloadSourceFile("new-gid", 1, "", "new", List.of(
                        new org.manager.download.DownloadSourceFile.Source("https://mirror.test/file", "Active", 2048,
                                "https://cdn.test/file")))));
        var sources = new SourcesPresenter(manager, selected::get);
        try {
            selected.set(first);
            sources.load();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            selected.set(second);
            sources.load();
            release.countDown();
            awaitGtk(() -> sources.store.iterNChildren(null) == 1, "New source selection did not load");
            TreeIter row = new TreeIter();
            assertTrue(sources.store.getIterFirst(row));
            assertEquals("https://mirror.test/file", ListStoreCells.getString(sources.store, row, 0));
            assertEquals("Active", ListStoreCells.getString(sources.store, row, 1));
            assertEquals("2 KB/s", ListStoreCells.getString(sources.store, row, 2));
            sources.entry.setText("https://another.test/file");
            assertTrue(sources.add.getSensitive());
            selected.set(null);
            sources.load();
            assertEquals(0, sources.store.iterNChildren(null));
            assertFalse(sources.add.getSensitive());
        } finally {
            release.countDown();
            sources.shutdown();
        }
    }

    @Test
    @DisplayName("running completion actions pulse a completed row at 100 percent")
    void completionActionUsesIndeterminateRowProgress() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore downloadStore = Widgets.require(builder, "download_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                downloadStore,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download download = new org.manager.download.Download(
                URI.create("https://example.com/finished.bin"));
        download.setSize(100);
        download.setDownloaded(100);
        download.setStatus(org.manager.download.Download.Status.COMPLETED);
        var running = new org.manager.download.action.CompletionActionResult(
                "running-action",
                org.manager.download.action.AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                "Antivirus check",
                org.manager.download.action.CompletionActionResult.Status.RUNNING,
                "Running…",
                org.manager.download.action.AfterCompletionAction.Severity.HIGH,
                java.time.Instant.now(), null);
        download.setCompletionActionResults(List.of(running));

        presenter.refresh(List.of(download));
        TreeIter iter = new TreeIter();
        assertTrue(downloadStore.getIterFirst(iter));
        assertNull(themedIconName(downloadStore, iter, 11),
                "a running action must not expose an engine icon");
        assertEquals(CompletionActionPresentation.Outcome.RUNNING.ordinal(),
                ListStoreCells.getInt(downloadStore, iter, 26),
                "the Result column sort key must describe action outcome, not engine type");
        assertEquals(100, ListStoreCells.getInt(downloadStore, iter, 12));
        assertEquals("100% · Finalizing…", ListStoreCells.getString(downloadStore, iter, 14));
        presenter.pulseCompletionRows();
        assertTrue(ListStoreCells.getInt(downloadStore, iter, 27) > 0);

        download.finishCompletionAction("running-action",
                org.manager.download.action.CompletionActionResult.Status.SUCCEEDED,
                "No threats detected");
        presenter.refresh(List.of(download));
        assertEquals("checkbox-checked-symbolic", themedIconName(downloadStore, iter, 11));
        assertEquals(CompletionActionPresentation.Outcome.SUCCEEDED.ordinal(),
                ListStoreCells.getInt(downloadStore, iter, 26));
        assertEquals(-1, ListStoreCells.getInt(downloadStore, iter, 27));
        assertEquals("100.00%", ListStoreCells.getString(downloadStore, iter, 14));

        var failed = new org.manager.download.action.CompletionActionResult(
                "failed-action",
                org.manager.download.action.AfterCompletionAction.ActionType.EXECUTE_COMMAND,
                "Custom command",
                org.manager.download.action.CompletionActionResult.Status.FAILED,
                "Command failed",
                org.manager.download.action.AfterCompletionAction.Severity.MEDIUM,
                java.time.Instant.now(), java.time.Instant.now());
        download.setCompletionActionResults(List.of(
                download.getCompletionActionResults().getFirst(), failed));
        presenter.refresh(List.of(download));
        assertEquals("dialog-warning-symbolic", themedIconName(downloadStore, iter, 11));
        assertEquals(CompletionActionPresentation.Outcome.PARTIAL.ordinal(),
                ListStoreCells.getInt(downloadStore, iter, 26));

        var interrupted = new org.manager.download.action.CompletionActionResult(
                "interrupted-action",
                org.manager.download.action.AfterCompletionAction.ActionType.DOWNLOAD_SUBTITLES,
                "Download subtitles",
                org.manager.download.action.CompletionActionResult.Status.INTERRUPTED,
                "Application exited",
                org.manager.download.action.AfterCompletionAction.Severity.LOW,
                java.time.Instant.now(), java.time.Instant.now());
        download.setCompletionActionResults(List.of(failed, interrupted));
        presenter.refresh(List.of(download));
        assertEquals("dialog-error-symbolic", themedIconName(downloadStore, iter, 11));
        assertEquals(CompletionActionPresentation.Outcome.FAILED.ordinal(),
                ListStoreCells.getInt(downloadStore, iter, 26));

        var sound = new org.manager.download.action.CompletionActionResult(
                "running-sound",
                org.manager.download.action.AfterCompletionAction.ActionType.PLAY_SOUND,
                "Play notification",
                org.manager.download.action.CompletionActionResult.Status.RUNNING,
                "Running…",
                org.manager.download.action.AfterCompletionAction.Severity.LOW,
                java.time.Instant.now(), null);
        download.setCompletionActionResults(List.of(sound));
        presenter.refresh(List.of(download));
        assertNull(themedIconName(downloadStore, iter, 11),
                "a non-terminal status must clear the previous outcome icon");
        assertEquals(-1, ListStoreCells.getInt(downloadStore, iter, 27));
        assertEquals("100.00%", ListStoreCells.getString(downloadStore, iter, 14),
                "sound and power actions must not show file-finalization progress");
    }

    @Test
    @DisplayName("completion result icons exist in the active GTK icon theme")
    void completionResultIconsExistInGtkTheme() {
        IconTheme theme = IconTheme.getForDisplay(org.gnome.gdk.Display.getDefault());
        for (CompletionActionPresentation.Outcome outcome : List.of(
                CompletionActionPresentation.Outcome.SUCCEEDED,
                CompletionActionPresentation.Outcome.PARTIAL,
                CompletionActionPresentation.Outcome.FAILED)) {
            org.gnome.gio.Icon icon = CompletionActionPresentation.outcomeIcon(outcome);
            assertNotNull(icon);
            assertTrue(theme.hasGicon(icon),
                    () -> CompletionActionPresentation.outcomeIconName(outcome)
                            + " and its fallbacks are unavailable in GTK icon theme "
                            + theme.getThemeName());
        }
    }

    @Test
    @DisplayName("import extensions and New Download files use their declared sort keys")
    void auxiliaryFileTablesSortByRawValues() {
        GtkBuilder importBuilder = UiLoader.load("/ui/import-list.ui");
        ListStore importStore = Widgets.require(importBuilder, "url_liststore", ListStore.class);
        TreeIter zip = new TreeIter();
        importStore.append(zip);
        ListStoreCells.setBoolean(importStore, zip, 0, true);
        ListStoreCells.setString(importStore, zip, 1, "https://example.com/archive.zip");
        ListStoreCells.setString(importStore, zip, 2, "zip");
        TreeIter mp4 = new TreeIter();
        importStore.append(mp4);
        ListStoreCells.setBoolean(importStore, mp4, 0, true);
        ListStoreCells.setString(importStore, mp4, 1, "https://example.com/video.mp4");
        ListStoreCells.setString(importStore, mp4, 2, "mp4");

        ((TreeSortable) importStore).setSortColumnId(2, SortType.ASCENDING);
        TreeIter first = new TreeIter();
        assertTrue(importStore.getIterFirst(first));
        assertEquals("mp4", ListStoreCells.getString(importStore, first, 2));

        GtkBuilder downloadBuilder = UiLoader.load("/ui/new-download.ui");
        TreeStore filesStore = Widgets.require(downloadBuilder,
                "files_liststore", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        FileTreeSupport.reconcile(filesStore, rows, List.of(
                new FileTreeSupport.Entry(true, "ten-kib.bin", 10 * 1024L, 0, 1, "Normal"),
                new FileTreeSupport.Entry(true, "nine-kib.bin", 9 * 1024L, 0, 2, "High")), null);

        ((TreeSortable) filesStore).setSortColumnId(7, SortType.ASCENDING);
        assertTrue(filesStore.getIterFirst(first));
        assertEquals("nine-kib.bin", TreeStoreCells.getString(filesStore, first, 1),
                "numeric size sorting must not use the rendered size text");

        ((TreeSortable) filesStore).setSortColumnId(12, SortType.DESCENDING);
        assertTrue(filesStore.getIterFirst(first));
        assertEquals("nine-kib.bin", TreeStoreCells.getString(filesStore, first, 1),
                "High priority must sort above Normal priority");
        FileTreeSupport.freeReferences(rows);
    }

    @Test
    @DisplayName("shared file trees build folders and propagate selection and priority")
    void hierarchicalFileTreeInteractions() {
        GtkBuilder builder = UiLoader.load("/ui/new-download.ui");
        TreeStore store = Widgets.require(builder, "files_liststore", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        try {
            FileTreeSupport.reconcile(store, rows, List.of(
                    new FileTreeSupport.Entry(true,
                            "/downloads/Show/Season 1/one.mkv", 100, 25, 1, "Normal"),
                    new FileTreeSupport.Entry(false,
                            "/downloads/Show/Season 1/two.mkv", 200, 50, 2, "Low"),
                    new FileTreeSupport.Entry(true,
                            "/downloads/Show/readme.txt", 10, 10, 3, "Normal")),
                    java.nio.file.Path.of("/downloads"));

            TreeIter root = new TreeIter();
            assertTrue(store.getIterFirst(root));
            assertEquals("Show", TreeStoreCells.getString(store, root,
                    FileTreeSupport.NAME_COLUMN));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.FOLDER_COLUMN));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));
            assertEquals(310L, TreeStoreCells.getLong(store, root,
                    FileTreeSupport.SIZE_SORT_COLUMN));

            assertTrue(FileTreeSupport.toggleSelection(store, "0:0"));
            assertEquals(List.of(1, 2, 3), FileTreeSupport.selectedIndexes(store));
            assertFalse(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));

            FileTreeSupport.setPriority(store, "0", "High");
            assertEquals(Map.of(1, "High", 2, "High", 3, "High"),
                    FileTreeSupport.priorities(store));

            FileTreeSupport.selectAll(store, false);
            assertEquals(List.of(), FileTreeSupport.selectedIndexes(store));
            assertTrue(FileTreeSupport.toggleSelection(store, "0:0:0"));
            assertEquals(List.of(1), FileTreeSupport.selectedIndexes(store));
            assertTrue(TreeStoreCells.getBoolean(store, root,
                    FileTreeSupport.INCONSISTENT_COLUMN));

            assertEquals("2,3,7", NewDownloadDialog.encodeFileSelection(
                    List.of(7, 3, 2, 3)));
        } finally {
            FileTreeSupport.freeReferences(rows);
        }
    }

    @Test
    @DisplayName("download-record file priority edits update persisted aria2 metadata")
    void downloadRecordFilePriorityIsEditable() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        TreeStore store = Widgets.require(builder, "files_store", TreeStore.class);
        java.util.Map<String, org.gnome.gtk.TreeRowReference> rows = new java.util.HashMap<>();
        try {
            FileTreeSupport.reconcile(store, rows, List.of(
                    new FileTreeSupport.Entry(true, "Show/one.mkv", 100, 50, 1, "Normal"),
                    new FileTreeSupport.Entry(true, "Show/two.mkv", 100, 25, 2, "Low")), null);
            org.manager.download.Download download = new org.manager.download.Download(
                    URI.create("magnet:?xt=urn:btih:cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd"));

            assertTrue(MainWindow.applyFilePriority(store, download, "0", "High"));

            org.aria2.Aria2Settings settings = assertInstanceOf(
                    org.aria2.Aria2Settings.class, download.getSettings());
            assertEquals(Map.of(1, "High", 2, "High"), settings.getFilePriorities());
            assertFalse(MainWindow.applyFilePriority(store, download, "9", "Low"));
        } finally {
            FileTreeSupport.freeReferences(rows);
        }
    }

    @Test
    @DisplayName("right-click targets the clicked row without discarding an existing group")
    void rightClickSelectionTargeting() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        for (int row = 0; row < 3; row++) {
            store.append(new TreeIter());
        }
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        TreePath first = TreePath.fromString("0");
        TreePath second = TreePath.fromString("1");
        TreePath third = TreePath.fromString("2");
        tree.getSelection().selectPath(first);
        tree.getSelection().selectPath(second);

        MainWindow.selectContextTarget(tree, second,
                Widgets.require(builder, "name_column", TreeViewColumn.class));
        assertEquals(2, tree.getSelection().countSelectedRows(),
                "right-clicking inside the selected group must preserve it");

        MainWindow.selectContextTarget(tree, third,
                Widgets.require(builder, "name_column", TreeViewColumn.class));
        assertEquals(1, tree.getSelection().countSelectedRows());
        assertFalse(tree.getSelection().pathIsSelected(first));
        assertTrue(tree.getSelection().pathIsSelected(third),
                "an unselected clicked row must become the context target");
    }

    @Test
    @DisplayName("deferred context popup restores the press-time multi-selection")
    void deferredContextPopupRestoresMultiSelection() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                store,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download first = new org.manager.download.Download(
                URI.create("https://example.com/first.bin"));
        org.manager.download.Download second = new org.manager.download.Download(
                URI.create("https://example.com/second.bin"));
        presenter.refresh(List.of(first, second));
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        tree.getSelection().selectPath(TreePath.fromString("0"));
        tree.getSelection().selectPath(TreePath.fromString("1"));
        List<String> pressSelection = List.of(first.getId(), second.getId());

        // Reproduce GtkTreeView's late selection change between the captured
        // press and the idle callback that presents the popover.
        tree.getSelection().unselectAll();
        tree.getSelection().selectPath(TreePath.fromString("1"));

        assertTrue(MainWindow.restoreContextSelection(
                presenter, tree.getSelection(), pressSelection));
        assertEquals(2, tree.getSelection().countSelectedRows());
        assertTrue(tree.getSelection().pathIsSelected(TreePath.fromString("0")));
        assertTrue(tree.getSelection().pathIsSelected(TreePath.fromString("1")));
    }

    @Test
    @DisplayName("appending a history page preserves the current multi-selection")
    void paginationAppendPreservesMultiSelection() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                store,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download first = new org.manager.download.Download(
                URI.create("https://example.com/first.bin"));
        org.manager.download.Download second = new org.manager.download.Download(
                URI.create("https://example.com/second.bin"));
        org.manager.download.Download third = new org.manager.download.Download(
                URI.create("https://example.com/third.bin"));
        first.setStatus(org.manager.download.Download.Status.COMPLETED);
        second.setStatus(org.manager.download.Download.Status.COMPLETED);
        third.setStatus(org.manager.download.Download.Status.COMPLETED);

        presenter.refresh(List.of(first, second));
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        TreePath firstPath = TreePath.fromString("0");
        TreePath secondPath = TreePath.fromString("1");
        tree.getSelection().selectPath(firstPath);
        tree.getSelection().selectPath(secondPath);

        DownloadListPresenter.RefreshSummary summary = presenter.refresh(
                List.of(first, second, third));

        assertFalse(summary.modelRebuilt());
        assertEquals(2, tree.getSelection().countSelectedRows());
        assertTrue(tree.getSelection().pathIsSelected(firstPath));
        assertTrue(tree.getSelection().pathIsSelected(secondPath));
        assertEquals(3, store.iterNChildren(null));
    }

    @Test
    @DisplayName("queue reorder rebuild restores the moved record selection")
    void queueReorderRebuildRestoresSelection() {
        GtkBuilder builder = UiLoader.load("/ui/main-window.ui");
        ListStore store = Widgets.require(builder, "download_store", ListStore.class);
        TreeView tree = Widgets.require(builder, "download_treeview", TreeView.class);
        DownloadListPresenter presenter = new DownloadListPresenter(
                Widgets.require(builder, "status_store", ListStore.class),
                Widgets.require(builder, "category_store", ListStore.class),
                store,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        org.manager.download.Download first = new org.manager.download.Download(
                URI.create("https://example.com/first.bin"));
        org.manager.download.Download second = new org.manager.download.Download(
                URI.create("https://example.com/second.bin"));
        first.setStatus(org.manager.download.Download.Status.QUEUED);
        second.setStatus(org.manager.download.Download.Status.QUEUED);
        first.setQueuePosition(1);
        second.setQueuePosition(2);

        presenter.refresh(List.of(first, second));
        tree.getSelection().setMode(org.gnome.gtk.SelectionMode.MULTIPLE);
        tree.getSelection().selectPath(TreePath.fromString("1"));
        ((TreeSortable) store).setSortColumnId(1, SortType.ASCENDING);
        assertSame(first, presenter.rowAt(0));
        presenter.useQueueOrder();

        first.setQueuePosition(2);
        second.setQueuePosition(1);
        DownloadListPresenter.RefreshSummary summary =
                presenter.refresh(List.of(first, second));

        assertTrue(summary.modelRebuilt());
        assertEquals(0, tree.getSelection().countSelectedRows(),
                "GtkListStore.clear drops the former TreePath selection");
        assertEquals(1, presenter.restoreSelection(
                tree.getSelection(), List.of(second.getId())));
        assertEquals(1, tree.getSelection().countSelectedRows());
        assertTrue(tree.getSelection().pathIsSelected(TreePath.fromString("0")));
        assertSame(second, presenter.rowAt(0));
    }

    private static String themedIconName(ListStore store, TreeIter iter, int column) {
        org.gnome.gobject.Value value = new org.gnome.gobject.Value();
        store.getValue(iter, column, value);
        try {
            if (value.getObject() == null) {
                return null;
            }
            org.gnome.gio.ThemedIcon icon = assertInstanceOf(
                    org.gnome.gio.ThemedIcon.class, value.getObject());
            return icon.getNames()[0];
        } finally {
            value.unset();
        }
    }

    private static org.manager.download.DownloadManager emptyManager() {
        return (org.manager.download.DownloadManager) java.lang.reflect.Proxy.newProxyInstance(
                org.manager.download.DownloadManager.class.getClassLoader(),
                new Class<?>[]{org.manager.download.DownloadManager.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getGlobalSettings" -> new org.manager.GlobalSettings();
                    case "getAllDownloads", "getDownloads", "getDownloadsByStatus" ->
                            java.util.List.of();
                    case "isClipboardMonitoringEnabled", "isTorrentFolderMonitoringEnabled",
                            "isMetaLinkFolderMonitoringEnabled" -> false;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static void assertChildrenOrdered(Widget parent, Widget... expected) {
        int previous = -1;
        for (Widget child : expected) {
            int index = childIndex(parent, child);
            assertTrue(index > previous, "status-bar child order is incorrect");
            previous = index;
        }
    }

    private static int childIndex(Widget parent, Widget expected) {
        int index = 0;
        for (Widget child = parent.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (child == expected) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static <T extends Widget> T firstDescendant(Widget parent, Class<T> type) {
        for (Widget child = parent.getFirstChild(); child != null;
                child = child.getNextSibling()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }
            T nested = firstDescendant(child, type);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static int treeColumnIndex(TreeView tree, TreeViewColumn expected) {
        int index = 0;
        for (TreeViewColumn column : tree.getColumns()) {
            if (column == expected) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static int gridColumn(Grid grid, Widget child) {
        Out<Integer> column = new Out<>();
        grid.queryChild(child, column, new Out<>(), new Out<>(), new Out<>());
        return column.get();
    }

    private static int gridRow(Grid grid, Widget child) {
        Out<Integer> row = new Out<>();
        grid.queryChild(child, new Out<>(), row, new Out<>(), new Out<>());
        return row.get();
    }

    private static void drainGtkEvents() {
        MainContext context = MainContext.default_();
        while (context.pending()) {
            context.iteration(false);
        }
    }

    private static void awaitGtk(BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        for (int attempt = 0; attempt < 300; attempt++) {
            drainGtkEvents();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError(failureMessage);
    }

    private static void assertFieldGrid(Grid grid) {
        assertTrue(grid.getHexpand(), "field grids must consume the available page width");
        assertTrue(grid.getRowSpacing() >= 8,
                "field rows need consistent vertical separation");
        assertTrue(grid.getColumnSpacing() >= 12,
                "labels and controls need consistent horizontal separation");
    }

    private static void assertFlatFieldGrid(Grid grid) {
        assertFieldGrid(grid);
        assertFalse(grid.getParent() instanceof Frame,
                "reference layouts use flat forms without framed section borders");
    }

    private static void assertDownloadOptionsLayout(GtkBuilder builder) {
        for (String id : new String[]{"settings_grid", "http_connection_grid",
                "proxy_settings_grid"}) {
            assertFlatFieldGrid(Widgets.require(builder, id, Grid.class));
        }
        Grid columns = Widgets.require(builder, "options_columns_grid", Grid.class);
        assertOptionsMargins(columns);
        Box left = Widgets.require(builder, "options_left_column", Box.class);
        Box right = Widgets.require(builder, "options_right_column", Box.class);
        assertTrue(columns.getHexpand());
        assertTrue(columns.getColumnSpacing() >= 16);
        assertEquals(Orientation.VERTICAL, left.getOrientation());
        assertEquals(Orientation.VERTICAL, right.getOrientation());
        assertEquals(0, gridColumn(columns, left));
        assertEquals(1, gridColumn(columns, right));
        assertEquals(1, gridColumn(Widgets.require(builder, "settings_grid", Grid.class),
                Widgets.require(builder, "retry_limit_spin", SpinButton.class)));
        assertEquals(1, gridColumn(Widgets.require(builder, "proxy_settings_grid", Grid.class),
                Widgets.require(builder, "proxy_host_entry", Entry.class)));
        assertBoldLabels(builder, "download_settings_heading", "http_connection_heading",
                "proxy_settings_heading", "tor_settings_heading");
    }

    private static void assertNoEmbeddedNetworkOptions(GtkBuilder builder) {
        for (String id : new String[]{"max_connections_spin", "retry_limit_spin",
                "max_download_speed_spin", "max_upload_speed_spin", "retry_after",
                "referrer", "cookie", "user_agent", "proxy_type_combo",
                "proxy_host_entry", "proxy_port_spin", "proxy_username_entry",
                "proxy_password_entry", "tor_switch"}) {
            assertNull(builder.getObject(id),
                    id + " must come from the shared network-options.ui resource");
        }
    }

    private static void assertNotebookInset(Notebook notebook) {
        assertEquals(12, notebook.getMarginStart());
        assertEquals(12, notebook.getMarginEnd());
        assertEquals(12, notebook.getMarginTop());
    }

    private static void assertPrimaryTabMargins(Widget content) {
        assertMargins(content, 12, 12);
    }

    private static void assertListTabMargins(Widget content) {
        assertMargins(content, 8, 8);
    }

    private static void assertOptionsMargins(Widget content) {
        assertMargins(content, 12, 14);
    }

    private static void assertMargins(Widget content, int horizontal, int vertical) {
        assertEquals(horizontal, content.getMarginStart());
        assertEquals(horizontal, content.getMarginEnd());
        assertEquals(vertical, content.getMarginTop());
        assertEquals(vertical, content.getMarginBottom());
    }

    private static void assertBoldLabels(GtkBuilder builder, String... labelIds) {
        for (String labelId : labelIds) {
            Label title = Widgets.require(builder, labelId, Label.class);
            assertTrue(title.getUseMarkup(), labelId + " must enable markup");
            assertTrue(title.getLabel().startsWith("<b>")
                            && title.getLabel().endsWith("</b>"),
                    labelId + " must be bold");
        }
    }

    private static void assertBoldFrameTitles(GtkBuilder builder, String... frameIds) {
        for (String frameId : frameIds) {
            Frame frame = Widgets.require(builder, frameId, Frame.class);
            assertTrue(frame.getLabelWidget() instanceof Label,
                    frameId + " must use an explicit label widget");
            Label title = (Label) frame.getLabelWidget();
            assertTrue(title.getUseMarkup(), frameId + " title must enable markup");
            assertTrue(title.getLabel().startsWith("<b>")
                            && title.getLabel().endsWith("</b>"),
                    frameId + " title must be bold");
        }
    }

    private static void assertDiskLabelBelowChooser(GtkBuilder builder, String chooserId,
            String labelId) {
        MenuButton chooser = Widgets.require(builder, chooserId, MenuButton.class);
        Label label = Widgets.require(builder, labelId, Label.class);
        Box stack = (Box) chooser.getParent();
        assertSame(stack, label.getParent());
        assertEquals(Orientation.VERTICAL, stack.getOrientation());
        assertSame(label, chooser.getNextSibling());
        assertEquals(Align.END, label.getHalign());
    }
}
