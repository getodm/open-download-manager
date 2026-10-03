package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

/**
 * Plain unit tests for the GTK-free list logic: filter matching, status
 * and category counts, and the row-structure comparison that decides
 * in-place updates vs full rebuilds.
 */
class DownloadListPresenterTest {

    private static Download download(String name, Download.Status status) {
        try {
            Download d = new Download(new URI("https://example.com/" + name));
            d.setName(name);
            d.setStatus(status);
            return d;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void countsByStatusFilterClass() {
        List<Download> downloads = List.of(
                download("new.zip", Download.Status.CREATED),
                download("starting.zip", Download.Status.STARTING),
                download("a.zip", Download.Status.DOWNLOADING),
                download("b.zip", Download.Status.CONNECTING),
                download("seed.iso", Download.Status.SEEDING),
                download("c.zip", Download.Status.QUEUED),
                download("d.zip", Download.Status.PAUSED),
                download("e.zip", Download.Status.COMPLETED),
                download("f.zip", Download.Status.ERROR),
                download("g.zip", Download.Status.CANCELED));

        // index-aligned with STATUS_FILTERS[1..]: active, seeding, queued, paused,
        // finished, error, canceled
        assertArrayEquals(new int[]{4, 1, 2, 1, 1, 1, 1},
                DownloadListPresenter.computeCounts(downloads));
        assertArrayEquals(new String[]{"All Status", "Active", "Seeding", "Queued", "Paused",
                "Finished", "Error", "Canceled"}, DownloadListPresenter.STATUS_FILTERS);
        assertArrayEquals(new int[]{4, 1, 2, 1, 1, 1, 1},
                DownloadListPresenter.computeCounts(java.util.Map.of(
                        Download.Status.STARTING, 1,
                        Download.Status.DOWNLOADING, 1,
                        Download.Status.CONNECTING, 1,
                        Download.Status.SEEDING, 1,
                        Download.Status.CREATED, 1,
                        Download.Status.QUEUED, 1,
                        Download.Status.PAUSED, 1,
                        Download.Status.COMPLETED, 1,
                        Download.Status.ERROR, 1,
                        Download.Status.CANCELED, 1)));
    }

    @Test
    void categoryOfMapsExtensionsLikeTheOriginalUi() {
        assertEquals("Videos", DownloadListPresenter.categoryOf(download("movie.mp4", Download.Status.QUEUED)));
        assertEquals("Audios", DownloadListPresenter.categoryOf(download("song.flac", Download.Status.QUEUED)));
        assertEquals("Photos", DownloadListPresenter.categoryOf(download("pic.PNG", Download.Status.QUEUED)));
        assertEquals("Programs", DownloadListPresenter.categoryOf(download("app.AppImage", Download.Status.QUEUED)));
        assertEquals("Others", DownloadListPresenter.categoryOf(download("archive.tar.gz", Download.Status.QUEUED)));
        assertEquals("Others", DownloadListPresenter.categoryOf(download("noextension", Download.Status.QUEUED)));
        Download unnamed = download("x", Download.Status.QUEUED);
        unnamed.setName(null);
        assertEquals("Others", DownloadListPresenter.categoryOf(unnamed));
    }

    @Test
    void categoryCountsIncludeTheAllBucket() {
        List<Download> downloads = List.of(
                download("movie.mp4", Download.Status.COMPLETED),
                download("song.mp3", Download.Status.COMPLETED),
                download("data.bin", Download.Status.COMPLETED));

        assertArrayEquals(new int[]{3, 1, 1, 0, 0, 1},
                DownloadListPresenter.computeCategoryCounts(downloads));
    }

    @Test
    void contextualCountsExcludeTheirOwnFilterAndRespectSearch() {
        List<Download> downloads = List.of(
                download("movie-one.mp4", Download.Status.QUEUED),
                download("movie-two.mkv", Download.Status.CREATED),
                download("song.mp3", Download.Status.QUEUED),
                download("movie-done.mp4", Download.Status.COMPLETED),
                download("movie-seed.mp4", Download.Status.SEEDING),
                download("failed.zip", Download.Status.ERROR));

        var buckets = DownloadListPresenter.computeFilterBuckets(downloads, "");
        var counts = DownloadListPresenter.computeFilterCounts(buckets, "Videos", "Queued");
        assertArrayEquals(new String[]{"4/6", "1", "1", "2/3", "0", "1", "0/1", "0"},
                counts.statuses());
        assertArrayEquals(new String[]{"3/6", "2/4", "1", "0", "0", "0/1"}, counts.categories());

        var searchBuckets = DownloadListPresenter.computeFilterBuckets(downloads, "movie");
        var empty = DownloadListPresenter.computeFilterCounts(searchBuckets, "Audios", "Queued");
        assertArrayEquals(new String[]{"0/6", "0/1", "0/1", "0/3", "0", "0/1", "0/1", "0"},
                empty.statuses());
        assertArrayEquals(new String[]{"2/6", "2/4", "0/1", "0", "0", "0/1"}, empty.categories());

        var cleared = DownloadListPresenter.computeFilterCounts(buckets, "All", "All Status");
        assertEquals("6", cleared.statuses()[0], "Active and Seeding overlap without inflating All");
        assertArrayEquals(new String[]{"6", "4", "1", "0", "0", "1"}, cleared.categories());
    }

    @Test
    void matchesFiltersCombinesSearchCategoryAndStatus() {
        Download movie = download("Holiday-Movie.mp4", Download.Status.DOWNLOADING);

        assertTrue(DownloadListPresenter.matchesFilters(movie, "", "All", "All Status"));
        assertTrue(DownloadListPresenter.matchesFilters(movie, "holiday", "All", "Active"));
        // search is case-insensitive substring over the name
        assertFalse(DownloadListPresenter.matchesFilters(movie, "nomatch", "All", "All Status"));
        // wrong category excludes even when search matches
        assertFalse(DownloadListPresenter.matchesFilters(movie, "", "Audios", "All Status"));
        // Queued covers CREATED/QUEUED; paused has its own visible filter.
        assertFalse(DownloadListPresenter.matchesFilters(movie, "", "All", "Queued"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.PAUSED), "", "All", "Paused"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.CONNECTING), "", "All", "Active"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.iso", Download.Status.SEEDING), "", "All", "Active"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.iso", Download.Status.SEEDING), "", "All", "Seeding"));
        // Error and canceled are distinct user-visible outcomes.
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.ERROR), "", "All", "Error"));
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.CANCELED), "", "All", "Canceled"));
        // Finished covers COMPLETED only
        assertTrue(DownloadListPresenter.matchesFilters(
                download("x.mp4", Download.Status.COMPLETED), "", "All", "Finished"));
        // a download without a name never matches a search
        Download unnamed = download("y", Download.Status.DOWNLOADING);
        unnamed.setName(null);
        assertFalse(DownloadListPresenter.matchesFilters(unnamed, "anything", "All", "All Status"));
    }

    @Test
    void rowStructureMatchesComparesIdSequencesNullSafe() {
        Download a = download("a.zip", Download.Status.QUEUED);
        Download b = download("b.zip", Download.Status.QUEUED);

        assertTrue(DownloadListPresenter.rowStructureMatches(List.of(a, b), List.of(a, b)));
        assertFalse(DownloadListPresenter.rowStructureMatches(List.of(a, b), List.of(b, a)));
        assertFalse(DownloadListPresenter.rowStructureMatches(List.of(a), List.of(a, b)));
        assertFalse(DownloadListPresenter.rowStructureMatches(null, List.of(a)));
        assertTrue(DownloadListPresenter.rowStructureMatches(List.of(), List.of()));
    }

    @Test
    void rowStructurePrefixRecognizesPaginationAppendOnly() {
        Download a = download("a.zip", Download.Status.COMPLETED);
        Download b = download("b.zip", Download.Status.COMPLETED);
        Download c = download("c.zip", Download.Status.COMPLETED);

        assertTrue(DownloadListPresenter.rowStructureIsPrefix(
                List.of(a, b), List.of(a, b, c)));
        assertFalse(DownloadListPresenter.rowStructureIsPrefix(
                List.of(a, b), List.of(a, c, b)));
        assertFalse(DownloadListPresenter.rowStructureIsPrefix(
                List.of(a, b), List.of(a, b)));
        assertFalse(DownloadListPresenter.rowStructureIsPrefix(null, List.of(a)));
    }

    @Test
    void rowsAtReturnsTheDistinctValidSelectionsInTreeOrder() {
        Download a = download("a.zip", Download.Status.QUEUED);
        Download b = download("b.zip", Download.Status.QUEUED);
        Download c = download("c.zip", Download.Status.QUEUED);

        assertEquals(List.of(a, b, c), DownloadListPresenter.rowsAt(
                List.of(a, b, c), List.of(2, -1, 0, 2, 99, 1)));
        assertEquals(List.of(), DownloadListPresenter.rowsAt(List.of(a), null));
    }

    @Test
    void queuedRowsFollowQueuePositionWithoutMovingHistoryRows() {
        Download history = download("finished.zip", Download.Status.COMPLETED);
        Download later = download("later.zip", Download.Status.QUEUED);
        later.setQueuePosition(2);
        Download active = download("active.zip", Download.Status.DOWNLOADING);
        Download first = download("first.zip", Download.Status.QUEUED);
        first.setQueuePosition(1);

        List<Download> ordered = DownloadListPresenter.orderQueuedRows(
                List.of(history, later, active, first));

        assertEquals(List.of(history, first, active, later), ordered);
    }

    @Test
    void statusIconsDistinguishEveryDownloadLifecycleState() {
        assertEquals("document-new-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.CREATED));
        assertEquals("media-playback-start-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.STARTING));
        assertEquals("media-playback-start-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.DOWNLOADING));
        assertEquals("network-transmit-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.SEEDING));
        assertEquals("view-grid-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.QUEUED));
        assertEquals("media-playback-pause-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.PAUSED));
        assertEquals("dialog-error-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.ERROR));
        assertEquals("object-select-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.COMPLETED));
        assertEquals("object-select-symbolic",
                DownloadListPresenter.iconForFilterRow("Finished"));
        assertEquals("network-transmit-symbolic",
                DownloadListPresenter.iconForFilterRow("Seeding"));
        assertEquals("network-transmit-receive-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.CONNECTING));
        assertEquals("process-stop-symbolic",
                DownloadListPresenter.statusIconName(Download.Status.CANCELED));
    }
}
