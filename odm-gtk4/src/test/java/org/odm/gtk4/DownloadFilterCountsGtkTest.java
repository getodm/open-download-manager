package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.gnome.glib.MainContext;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.GtkBuilder;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListStore;
import org.gnome.gtk.SearchEntry;
import org.gnome.gtk.TreeIter;
import org.gnome.gtk.TreePath;
import org.gnome.gtk.TreeView;
import org.javagi.interop.MemoryCleaner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.manager.download.PaginatedDownloadRepository;

/** Native sidebar selections and count labels backed by the real repository. */
@Timeout(30)
class DownloadFilterCountsGtkTest {
    @BeforeAll
    static void initGtk() throws Exception {
        Class.forName("org.gnome.glib.GLib");
        Class.forName("org.gnome.glib.MainContext");
        assertTrue(MainContext.default_().acquire());
        Gtk.init();
    }

    @Test
    void sidebarSelectionsShowContextualAndGlobalCounts() throws Exception {
        try (Fixture fixture = new Fixture(0)) {
            fixture.awaitCounts("status_store", "51", "3", "0", "19", "0", "20", "9", "0");
            assertArrayEquals(new String[]{"51", "27", "0", "0", "0", "24"}, fixture.counts("category_store"));
            assertEquals("51 downloads", fixture.widget("info_label", Label.class).getLabel());

            fixture.select("status_treeview", 3); // Queued
            fixture.awaitCounts("category_store", "19/51", "7/27", "0", "0", "0", "12/24");
            fixture.select("category_treeview", 5); // Others
            fixture.awaitCounts("status_store", "24/51", "3", "0", "12/19", "0", "0/20", "9", "0");
            assertArrayEquals(new String[]{"19/51", "7/27", "0", "0", "0", "12/24"}, fixture.counts("category_store"));
            assertEquals(12, fixture.rows());

            fixture.select("download_treeview", 0);
            assertEquals("1 download selected", fixture.widget("info_label", Label.class).getLabel());
            assertEquals("12/19", fixture.counts("status_store")[3]);
            fixture.widget("download_treeview", TreeView.class).getSelection().unselectAll();

            Download completed = fixture.repository.getDownloadsByStatus(Download.Status.QUEUED, 0, 100)
                    .getDownloads().stream().filter(download -> download.getName().endsWith(".bin"))
                    .findFirst().orElseThrow();
            fixture.repository.updateDownloadStatus(completed, Download.Status.COMPLETED);
            fixture.listener.onDownloadComplete(completed);
            fixture.awaitCounts("status_store", "24/51", "3", "0", "11/18", "0", "1/21", "9", "0");
            assertArrayEquals(new String[]{"18/51", "7/27", "0", "0", "0", "11/24"}, fixture.counts("category_store"));
            assertEquals(11, fixture.rows());

            fixture.select("category_treeview", 2); // Zero-count Audio stays selectable.
            fixture.awaitCounts("status_store", "0/51", "0/3", "0", "0/18", "0", "0/21", "0/9", "0");
            assertEquals(0, fixture.rows());
            assertArrayEquals(new String[]{"18/51", "7/27", "0", "0", "0", "11/24"}, fixture.counts("category_store"));
            assertEquals("Audios", fixture.filterLabel("category_store", 2));
            fixture.select("category_treeview", 0);
            fixture.awaitCounts("status_store", "51", "3", "0", "18", "0", "21", "9", "0");
            fixture.select("status_treeview", 0);
            fixture.awaitCounts("category_store", "51", "27", "0", "0", "0", "24");
        }
    }

    @Test
    void countsAndSearchCoverHistoryBeyondTheLoadedPage() throws Exception {
        try (Fixture fixture = new Fixture(550)) {
            fixture.awaitCounts("category_store", "601", "27", "550", "0", "0", "24");
            assertTrue(fixture.groupedQueries.get() > 0, "counts must query the full repository");
            assertTrue(fixture.rows() < 601, "the first view should retain bounded history loading");
            fixture.select("status_treeview", 3);
            fixture.select("category_treeview", 5);
            fixture.awaitCounts("status_store", "24/601", "3", "0", "12/19", "0", "0/570", "9", "0");
            assertArrayEquals(new String[]{"19/601", "7/27", "0/550", "0", "0", "12/24"}, fixture.counts("category_store"));
            var width = new org.javagi.base.Out<Integer>();
            fixture.widget("category_treeview", TreeView.class).createPangoLayout("19/601")
                    .getPixelSize(width, new org.javagi.base.Out<>());
            await(() -> fixture.widget("category_count_column", org.gnome.gtk.TreeViewColumn.class)
                    .getWidth() >= width.get(), "The sidebar count must fit without clipping");

            fixture.widget("search_entry", SearchEntry.class).setText("VIDEO");
            fixture.awaitCounts("status_store", "0/601", "0/3", "0", "0/19", "0", "0/570", "0/9", "0");
            assertArrayEquals(new String[]{"7/601", "7/27", "0/550", "0", "0", "0/24"}, fixture.counts("category_store"));
            fixture.select("category_treeview", 1);
            fixture.awaitCounts("status_store", "27/601", "0/3", "0", "7/19", "0", "20/570", "0/9", "0");
            assertEquals(7, fixture.rows());
            fixture.select("status_treeview", 0);
            fixture.awaitCounts("category_store", "27/601", "27", "0/550", "0", "0", "0/24");
            await(() -> fixture.rows() == 27, "Search matches beyond the first page were not loaded");
            fixture.widget("search_entry", SearchEntry.class).setText("no-match");
            fixture.awaitCounts("status_store", "0/601", "0/3", "0", "0/19", "0", "0/570", "0/9", "0");
            assertArrayEquals(new String[]{"0/601", "0/27", "0/550", "0", "0", "0/24"}, fixture.counts("category_store"));
            fixture.widget("search_entry", SearchEntry.class).setText("");
            fixture.awaitCounts("status_store", "27/601", "0/3", "0", "7/19", "0", "20/570", "0/9", "0");
            fixture.select("category_treeview", 0);
            fixture.awaitCounts("status_store", "601", "3", "0", "19", "0", "570", "9", "0");
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final PaginatedDownloadRepository repository =
                new PaginatedDownloadRepository(new GlobalSettings());
        private final AtomicInteger groupedQueries = new AtomicInteger();
        private final MainWindow main;
        private final GtkBuilder builder;
        private DownloadListener listener;

        Fixture(int extraHistory) throws Exception {
            add(12, "queued-other", ".bin", Download.Status.QUEUED);
            add(7, "queued-video", ".mp4", Download.Status.QUEUED);
            add(20, "finished-video", ".mp4", Download.Status.COMPLETED);
            add(3, "active-other", ".bin", Download.Status.DOWNLOADING);
            add(9, "error-other", ".bin", Download.Status.ERROR);
            add(extraHistory, "recent-audio", ".mp3", Download.Status.COMPLETED);
            DownloadManager manager = mock(DownloadManager.class);
            when(manager.getGlobalSettings()).thenReturn(new GlobalSettings());
            when(manager.getClipboardService()).thenReturn(mock(org.manager.clipboard.ClipboardService.class));
            when(manager.getDownloads(anyInt(), anyInt())).thenAnswer(call ->
                    repository.getAllDownloadsByOffset(call.getArgument(0), call.getArgument(1)).getDownloads());
            when(manager.getDownloadsByStatus(any(), anyInt(), anyInt())).thenAnswer(call ->
                    repository.getDownloadsByStatusByOffset(call.getArgument(0), call.getArgument(1),
                            call.getArgument(2)).getDownloads());
            when(manager.getDownload(anyString())).thenAnswer(call -> repository.getDownload(call.getArgument(0)));
            when(manager.getDownloadCount()).thenAnswer(call -> repository.getTotalCount());
            when(manager.getDownloadCountByStatus(any())).thenAnswer(call -> repository.getCountByStatus(call.getArgument(0)));
            when(manager.getDownloadCounts(any())).thenAnswer(call -> {
                groupedQueries.incrementAndGet();
                return repository.getDownloadCounts(call.getArgument(0));
            });
            doAnswer(call -> {
                listener = call.getArgument(0);
                return null;
            }).when(manager).addDownloadListener(any());
            main = new MainWindow(null, manager, mock(org.tor.TorService.class),
                    mock(org.manager.schedule.ScheduleManager.class));
            var field = MainWindow.class.getDeclaredField("uiBuilder");
            field.setAccessible(true);
            builder = (GtkBuilder) field.get(main);
            main.present();
        }

        private void add(int count, String prefix, String extension, Download.Status status) {
            for (int i = 0; i < count; i++) {
                String name = prefix + "-" + i + extension;
                Download download = new Download(URI.create("https://example.test/" + name));
                download.setName(name);
                download.setStatus(status);
                repository.addDownload(download);
            }
        }

        <T extends org.gnome.gobject.GObject> T widget(String id, Class<T> type) {
            return Widgets.require(builder, id, type);
        }

        int rows() {
            return widget("download_store", ListStore.class).iterNChildren(null);
        }

        void awaitCounts(String id, String... expected) throws Exception {
            try {
                await(() -> java.util.Arrays.equals(expected, counts(id)), "Unexpected counts in " + id);
            } catch (AssertionError error) {
                assertArrayEquals(expected, counts(id), "Unexpected counts in " + id);
                throw error;
            }
        }

        void select(String id, int index) {
            TreePath path = TreePath.fromIndicesv(new int[]{index});
            try {
                widget(id, TreeView.class).getSelection().selectPath(path);
            } finally {
                MemoryCleaner.free(path.handle());
            }
        }

        String[] counts(String id) {
            ListStore store = widget(id, ListStore.class);
            String[] result = new String[store.iterNChildren(null)];
            TreeIter iter = new TreeIter();
            for (int i = 0; i < result.length; i++) {
                assertTrue(store.iterNthChild(iter, null, i));
                result[i] = ListStoreCells.getString(store, iter, 1);
            }
            return result;
        }

        String filterLabel(String id, int index) {
            ListStore store = widget(id, ListStore.class);
            TreeIter iter = new TreeIter();
            assertTrue(store.iterNthChild(iter, null, index));
            return ListStoreCells.getString(store, iter, 2);
        }

        @Override
        public void close() {
            main.dispose();
            drainGtk();
        }
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        do {
            drainGtk();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertTrue(condition.getAsBoolean(), message);
    }

    private static void drainGtk() {
        MainContext context = MainContext.default_();
        while (context.pending()) {
            context.iteration(false);
        }
    }
}
