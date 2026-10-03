package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

@DisplayName("PaginatedDownloadRepository status paging and cache statistics")
class PaginatedDownloadRepositoryExtrasTest {

    private final GlobalSettings settings = new GlobalSettings();
    private final PaginatedDownloadRepository repository = new PaginatedDownloadRepository(settings);

    private Download add(String name, Download.Status status) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setName(name);
        repository.addDownload(download);
        repository.updateDownloadStatus(download, status);
        return download;
    }

    @Test
    @DisplayName("status queries page by offset with correct totals")
    void statusByOffsetPaging() throws Exception {
        for (int i = 0; i < 5; i++) {
            add("p" + i, Download.Status.COMPLETED);
        }
        add("active", Download.Status.DOWNLOADING);

        PaginatedDownloadRepository.DownloadPage page0 =
                repository.getDownloadsByStatusByOffset(Download.Status.COMPLETED, 0, 2);
        assertEquals(5, page0.getTotalCount(), "totalCount counts all completed downloads");
        assertEquals(2, page0.getDownloads().size());

        PaginatedDownloadRepository.DownloadPage page4 =
                repository.getDownloadsByStatusByOffset(Download.Status.COMPLETED, 4, 2);
        assertEquals(1, page4.getDownloads().size(), "the tail page holds the remainder");

        PaginatedDownloadRepository.DownloadPage beyond =
                repository.getDownloadsByStatusByOffset(Download.Status.COMPLETED, 10, 2);
        assertEquals(0, beyond.getDownloads().size(), "an offset past the end yields an empty page");
        assertEquals(5, beyond.getTotalCount(), "totalCount stays known past the end");

        PaginatedDownloadRepository.DownloadPage downloading =
                repository.getDownloadsByStatusByOffset(Download.Status.DOWNLOADING, 0, 10);
        assertEquals(1, downloading.getTotalCount());
    }

    @Test
    void groupedCountsCoverUnloadedRowsAndFollowChanges() throws Exception {
        add("movie-one", Download.Status.COMPLETED);
        add("movie-two", Download.Status.COMPLETED);
        Download queued = add("movie-three", Download.Status.QUEUED);
        add("audio", Download.Status.QUEUED);
        assertEquals(2, repository.getAllDownloadsByOffset(0, 2).getDownloads().size());

        java.util.function.Function<Download, Download.Status> movies = download ->
                download.getName().startsWith("movie") ? download.getStatus() : null;
        assertEquals(Map.of(Download.Status.COMPLETED, 2, Download.Status.QUEUED, 1),
                repository.getDownloadCounts(movies));

        repository.updateDownloadStatus(queued, Download.Status.COMPLETED);
        assertEquals(Map.of(Download.Status.COMPLETED, 3), repository.getDownloadCounts(movies));
        queued.setName("audio-renamed");
        assertEquals(Map.of(Download.Status.COMPLETED, 2), repository.getDownloadCounts(movies));
        repository.removeDownload(queued.getId());
        assertEquals(Map.of(Download.Status.COMPLETED, 2, Download.Status.QUEUED, 1),
                repository.getDownloadCounts(Download::getStatus));
        assertEquals(Map.of(), repository.getDownloadCounts(download -> null));
    }

    @Test
    @DisplayName("cache statistics count hits, misses and invalidations")
    void cacheStatistics() throws Exception {
        Download download = add("cached", Download.Status.COMPLETED);

        repository.getAllDownloads(0, 10);
        repository.getAllDownloads(0, 10); // same query served from cache

        Map<String, Object> stats = repository.getCacheStats();
        assertNotNull(stats);
        long hits = ((Number) stats.get("cacheHits")).longValue();
        long misses = ((Number) stats.get("cacheMisses")).longValue();
        assertTrue(hits >= 1, "the repeated query must be a cache hit");
        assertTrue(misses >= 1, "the first query must be a cache miss");
        double hitRate = ((Number) stats.get("hitRate")).doubleValue();
        assertTrue(hitRate > 0.0 && hitRate <= 1.0);

        // a mutation must invalidate the cached result
        Download another = add("cached2", Download.Status.COMPLETED);
        PaginatedDownloadRepository.DownloadPage refreshed =
                repository.getAllDownloads(0, 10);
        assertEquals(2, refreshed.getTotalCount(), "the page must reflect the new download");
        assertNotNull(another);
        assertNotNull(download);
    }

    @Test
    @DisplayName("cleanupCache drops expired entries without losing data")
    void cacheCleanupKeepsData() throws Exception {
        add("keep", Download.Status.COMPLETED);
        repository.getAllDownloads(0, 10);
        repository.cleanupCache();

        assertEquals(1, repository.getAllDownloads(0, 10).getTotalCount(),
                "cache cleanup must not remove downloads");
        List<Download> all = repository.getAllDownloads(0, 10).getDownloads();
        assertEquals("keep", all.get(0).getName());
    }
}
