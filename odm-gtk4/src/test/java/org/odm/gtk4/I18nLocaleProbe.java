package org.odm.gtk4;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.gnome.gtk.*;
import org.manager.download.Download;
import org.manager.util.SizeUnits;
import org.ytdlp.YtDlpSettings;

/** Executed in a fresh JVM by I18nTest with a real Linux locale environment. */
public final class I18nLocaleProbe {
    public static void main(String[] args) {
        try {
            verify(args[0].equals("fr"));
            System.out.println("LOCALE CHECK PASSED");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(boolean french) throws Exception {
        I18n.initialize();
        equal(french ? "Préférences" : "Preferences", I18n.tr("Preferences"));
        equal("A missing catalog message", I18n.tr("A missing catalog message"));
        equal("A missing catalog message", I18n.context("missing", "A missing catalog message"));
        equal(french ? "Actif" : "Active", I18n.context("mirror-state", "Active"));
        equal("", I18n.tr(""));
        equal(french ? "0 élément" : "0 items", I18n.plural("%d item", "%d items", 0));
        equal(french ? "1 élément" : "1 item", I18n.plural("%d item", "%d items", 1));
        equal(french ? "2 éléments" : "2 items", I18n.plural("%d item", "%d items", 2));
        equal(french ? "1 sur 2 éléments sélectionnés" : "1 of 2 items selected",
                I18n.plural("%2$d of %1$d item selected", "%2$d of %1$d items selected", 2, 1));
        // The total selects the plural form, so both languages describe two items.
        equal(french ? "Mo" : "MB", SizeUnits.current().get(2));
        equal(french ? "Média" : "Media", ImportEngine.YT_DLP.label());
        equal(Download.Type.YOUTUBE, ImportEngine.YT_DLP.type());
        equal("mp4-compatible", YtDlpSettings.ContainerProfile.MP4_COMPATIBLE.settingValue());
        equal(french ? "Conserver les formats natifs" : "Preserve native formats",
                MediaPresentation.container(YtDlpSettings.ContainerProfile.PRESERVE_NATIVE));
        equal("High", FileTreeSupport.normalizePriority(french ? "Haute" : "High"));
        var copy = new DownloadLinkCopy(List.of("https://example.test/1", "https://example.test/2"));
        equal(french ? "Copier les URL" : "Copy URLs", copy.label());
        equal(french ? "2 URL copiées" : "2 URLs copied", copy.confirmation());

        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        // This probe has no GtkApplication.run() loop. Own its context until
        // process exit so Java-GI cleanup is queued to the GTK thread instead
        // of invoking native widget destruction directly on a Cleaner thread.
        if (!org.gnome.glib.MainContext.default_().acquire()) {
            throw new AssertionError("Could not own the GTK main context");
        }
        Gtk.init();
        GtkBuilder main = null;
        GtkBuilder newDownload = null;
        var builders = new java.util.ArrayList<GtkBuilder>();
        var windows = new java.util.ArrayList<Window>();
        for (String ui : List.of("about", "action-output", "completion-command", "import-list",
                "import-remote", "import-sequence", "jackett-settings", "main-window", "network-options",
                "new-download", "new-media", "new-website", "property", "search-torrents", "settings",
                "sources", "start-shutdown")) {
            GtkBuilder builder = UiLoader.load("/ui/" + ui + ".ui");
            builders.add(builder);
            for (var object : UiLoader.objects(builder)) {
                if (object instanceof Window window) { windows.add(window); }
            }
            if (ui.equals("settings")) {
                equal(french ? "Open Download Manager - Paramètres" : "Open Download Manager - Settings",
                        Widgets.require(builder, "settings_dialog", Window.class).getTitle());
            }
            if (ui.equals("main-window")) { main = builder; }
            if (ui.equals("new-download")) { newDownload = builder; }
        }
        try {
            verifyFilters(main, french);
            Notebook tabs = Widgets.require(newDownload, "options_notebook", Notebook.class);
            equal(french ? "Téléchargement" : "Download",
                    ((Label) tabs.getTabLabel(tabs.getNthPage(0))).getLabel());
            TreeStore files = Widgets.require(newDownload, "files_liststore", TreeStore.class);
            FileTreeSupport.reconcile(files, new LinkedHashMap<>(), List.of(
                    new FileTreeSupport.Entry(true, "file.bin", 1024, 0, 1, "High")), null);
            TreeIter row = new TreeIter();
            if (!files.getIterFirst(row)) { throw new AssertionError("Missing file row"); }
            equal(french ? "Haute" : "High", TreeStoreCells.getString(files, row, FileTreeSupport.PRIORITY_TEXT_COLUMN));
            equal("High", FileTreeSupport.priorities(files).get(1));
            DownloadProgressGraph graph = new DownloadProgressGraph(main);
            try {
                equal(french ? "Vitesse : —" : "Speed: —",
                        Widgets.require(main, "info_speed_value", Label.class).getLabel());
                Download download = new Download(URI.create("https://example.test/file"));
                download.setStatus(Download.Status.COMPLETED);
                graph.update(download);
                equal(french ? "Vitesse maximale : —" : "Max speed: —",
                        Widgets.require(main, "info_speed_value", Label.class).getLabel());
            } finally { graph.dispose(); }
            if (Locale.getDefault(Locale.Category.FORMAT).getLanguage().equals("fr")) {
                equal(french ? "1,5 Mo" : "1,5 MB", DownloadFormats.size(1572864));
                equal("12,50%", ProgressPresentation.percentage(12.5));
            }
        } finally {
            windows.forEach(Window::destroy);
            while (org.gnome.glib.MainContext.default_().iteration(false)) { }
            java.lang.ref.Reference.reachabilityFence(builders);
        }
    }

    private static void verifyFilters(GtkBuilder builder, boolean french) {
        ListStore statuses = Widgets.require(builder, "status_store", ListStore.class);
        ListStore downloads = Widgets.require(builder, "download_store", ListStore.class);
        DownloadListPresenter presenter = new DownloadListPresenter(statuses,
                Widgets.require(builder, "category_store", ListStore.class), downloads,
                Widgets.require(builder, "global_progress_store", ListStore.class),
                Widgets.require(builder, "status_treeview", TreeView.class),
                Widgets.require(builder, "category_treeview", TreeView.class), () -> { });
        Download queued = new Download(URI.create("https://example.test/queued.bin"));
        queued.setStatus(Download.Status.QUEUED);
        Download finished = new Download(URI.create("https://example.test/finished.bin"));
        finished.setStatus(Download.Status.COMPLETED);
        presenter.refresh(List.of(queued, finished));
        TreeIter status = new TreeIter();
        if (!statuses.getIterFirst(status)) { throw new AssertionError("Missing status filters"); }
        equal(french ? "Tous les états" : "All Status", ListStoreCells.getString(statuses, status, 2));
        int completedFilter = java.util.Arrays.asList(DownloadListPresenter.STATUS_FILTERS).indexOf("Finished");
        equal(true, presenter.selectStatusFilterAt(completedFilter));
        presenter.refresh(List.of(queued, finished));
        equal(1, downloads.iterNChildren(null));
        equal(finished, presenter.rowAt(0));
        presenter.refresh(List.of());
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected [" + expected + "] but was [" + actual + "]");
        }
    }
}
