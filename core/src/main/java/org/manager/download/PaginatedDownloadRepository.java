package org.manager.download;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import org.manager.GlobalSettings;

/**
 * A repository for managing downloads with efficient pagination and filtering
 * capabilities. This class provides thread-safe access to downloads with
 * optimized memory usage and fast query performance for large datasets.
 */
public class PaginatedDownloadRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(PaginatedDownloadRepository.class);

    private final Map<String, Download> downloads;
    private final Map<Download.Status, Set<String>> statusIndex;
    private final NavigableSet<Download> creationIndex;
    private final Map<Download.Status, NavigableSet<Download>> statusOrderIndex;
    private final ReadWriteLock lock;
    private final GlobalSettings globalSettings;

    // Cache for frequently accessed queries
    private final Map<String, CachedQueryResult> queryCache;
    /**
     * Guards every queryCache access. The repository read lock permits
     * concurrent readers, but the cache is an access-order LinkedHashMap
     * whose get() relinks entries and whose put() evicts — unsynchronized
     * concurrent access corrupts the internal list (lost entries, cycles
     * that hang later iterations).
     */
    private final Object cacheLock = new Object();
    private static final int MAX_CACHE_SIZE = 100;
    private static final long CACHE_TTL_MS = 30000; // 30 seconds
    private static final Comparator<Download> DOWNLOAD_ORDER =
            Comparator.comparing(Download::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(Download::getId);

    // OPTIMIZATION: Cache performance metrics for selective invalidation
    private volatile long cacheHits = 0;
    private volatile long cacheMisses = 0;
    private volatile long selectiveInvalidations = 0;
    private volatile long fullInvalidations = 0;

    /**
     * Represents a cached query result with expiration time.
     */
    private static class CachedQueryResult {

        final List<Download> results;
        final long timestamp;
        final int totalCount;

        CachedQueryResult(List<Download> results, int totalCount) {
            this.results = new ArrayList<>(results);
            this.totalCount = totalCount;
            this.timestamp = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }

    /**
     * Represents a page of download results.
     */
    public static class DownloadPage {

        private final List<Download> downloads;
        private final int totalCount;
        private final int pageNumber;
        private final int pageSize;
        private final boolean hasNext;
        private final boolean hasPrevious;

        public DownloadPage(List<Download> downloads, int totalCount, int pageNumber, int pageSize) {
            this.downloads = new ArrayList<>(downloads);
            this.totalCount = totalCount;
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.hasNext = (pageNumber + 1) * pageSize < totalCount;
            this.hasPrevious = pageNumber > 0;
        }

        public List<Download> getDownloads() {
            return new ArrayList<>(downloads);
        }

        public int getTotalCount() {
            return totalCount;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public int getPageSize() {
            return pageSize;
        }

        public boolean hasNext() {
            return hasNext;
        }

        public boolean hasPrevious() {
            return hasPrevious;
        }

        public int getTotalPages() {
            return (int) Math.ceil((double) totalCount / pageSize);
        }
    }

    /**
     * Creates a new PaginatedDownloadRepository.
     *
     * @param globalSettings The global settings for configuration
     */
    public PaginatedDownloadRepository(GlobalSettings globalSettings) {
        this.downloads = new ConcurrentHashMap<>();
        this.statusIndex = new ConcurrentHashMap<>();
        this.creationIndex = new TreeSet<>(DOWNLOAD_ORDER);
        this.statusOrderIndex = new ConcurrentHashMap<>();
        this.lock = new ReentrantReadWriteLock();
        this.globalSettings = globalSettings;
        this.queryCache = new LinkedHashMap<String, CachedQueryResult>(MAX_CACHE_SIZE + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedQueryResult> eldest) {
                return size() > MAX_CACHE_SIZE;
            }
        };

        // Initialize status index
        for (Download.Status status : Download.Status.values()) {
            statusIndex.put(status, ConcurrentHashMap.newKeySet());
            statusOrderIndex.put(status, new TreeSet<>(DOWNLOAD_ORDER));
        }
    }

    /**
     * Adds a download to the repository.
     *
     * @param download The download to add
     */
    public void addDownload(Download download) {
        lock.writeLock().lock();
        try {
            Download replaced = downloads.put(download.getId(), download);
            if (replaced != null) {
                removeFromIndices(replaced);
            }
            updateIndices(download);
            invalidateCacheForAdd(download);
            LOGGER.debug("Added download: " + download.getId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a download from the repository.
     *
     * @param downloadId The ID of the download to remove
     * @return The removed download, or null if not found
     */
    public Download removeDownload(String downloadId) {
        lock.writeLock().lock();
        try {
            Download removed = downloads.remove(downloadId);
            if (removed != null) {
                removeFromIndices(removed);
                invalidateCacheForRemove(removed);
                LOGGER.debug("Removed download: " + downloadId);
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates a download's status and maintains indices.
     *
     * <p>The transition is membership-based and idempotent: the download id
     * is removed from <em>every</em> status index and then added to the index
     * of the target status. Handlers may call {@code Download.setStatus}
     * directly before the manager reindexes, so deriving the move from
     * {@code download.getStatus()} (which then equals the new status) would
     * leave stale entries behind and break status queries and queue
     * progression.</p>
     *
     * @param download  The download to update
     * @param newStatus The new status
     */
    public void updateDownloadStatus(Download download, Download.Status newStatus) {
        lock.writeLock().lock();
        try {
            Download.Status oldStatus = download.getStatus();

            // Remove from all status indexes (self-healing: also clears any
            // stale entry left by a handler-first status write)
            for (Set<String> statusSet : statusIndex.values()) {
                statusSet.remove(download.getId());
            }
            for (NavigableSet<Download> ordered : statusOrderIndex.values()) {
                ordered.remove(download);
            }

            if (oldStatus != newStatus) {
                download.setStatus(newStatus);
            }

            // Add to new status index
            Set<String> newStatusSet = statusIndex.get(newStatus);
            if (newStatusSet != null) {
                newStatusSet.add(download.getId());
            }
            NavigableSet<Download> newStatusOrder = statusOrderIndex.get(newStatus);
            if (newStatusOrder != null) {
                newStatusOrder.add(download);
            }

            invalidateCacheForStatusChange(oldStatus, newStatus);
            LOGGER.debug("Updated download status: " + download.getId() + " " + oldStatus + " -> " + newStatus);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates a download's status when the caller knows the TRUE prior
     * status, captured before any handler-side mutation of
     * {@code Download.status}. Both the source and the target status cache
     * buckets are invalidated, so prewarmed queries for the old status
     * stop returning the download immediately instead of after the cache
     * TTL.
     *
     * @param download    the download to reindex
     * @param fromStatus  the download's status before the handler mutated it
     * @param toStatus    the status the download has now
     */
    public void transitionDownloadStatus(Download download, Download.Status fromStatus,
            Download.Status toStatus) {
        lock.writeLock().lock();
        try {
            for (Set<String> statusSet : statusIndex.values()) {
                statusSet.remove(download.getId());
            }
            for (NavigableSet<Download> ordered : statusOrderIndex.values()) {
                ordered.remove(download);
            }

            if (download.getStatus() != toStatus) {
                download.setStatus(toStatus);
            }

            Set<String> newStatusSet = statusIndex.get(toStatus);
            if (newStatusSet != null) {
                newStatusSet.add(download.getId());
            }
            NavigableSet<Download> newStatusOrder = statusOrderIndex.get(toStatus);
            if (newStatusOrder != null) {
                newStatusOrder.add(download);
            }

            invalidateCacheForStatusChange(fromStatus, toStatus);
            LOGGER.debug("Transitioned download status: " + download.getId() + " " + fromStatus + " -> " + toStatus);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets a download by ID.
     *
     * @param downloadId The download ID
     * @return The download, or null if not found
     */
    public Download getDownload(String downloadId) {
        lock.readLock().lock();
        try {
            return downloads.get(downloadId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets all downloads with pagination.
     *
     * @param pageNumber The page number (0-based)
     * @param pageSize   The number of downloads per page
     * @return A page of downloads
     */
    public DownloadPage getAllDownloads(int pageNumber, int pageSize) {
        lock.readLock().lock();
        try {
            String cacheKey = "all_" + pageNumber + "_" + pageSize;
            CachedQueryResult cached = cacheLookup(cacheKey);

            if (cached != null && !cached.isExpired()) {
                cacheHits++;
                return new DownloadPage(cached.results, cached.totalCount, pageNumber, pageSize);
            }
            cacheMisses++;

            List<Download> allDownloads = new ArrayList<>(creationIndex);

            int totalCount = allDownloads.size();
            int fromIndex = pageNumber * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, totalCount);

            List<Download> pageDownloads = fromIndex < totalCount
                    ? allDownloads.subList(fromIndex, toIndex)
                    : new ArrayList<>();

            // Cache the result
            cacheStore(cacheKey, new CachedQueryResult(pageDownloads, totalCount));

            return new DownloadPage(pageDownloads, totalCount, pageNumber, pageSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets downloads with cursor-style pagination: the window is exactly
     * [offset, offset+limit) in the standard newest-first ordering. Unlike
     * page-number pagination this never duplicates or drops rows for
     * offsets that are not multiples of the limit.
     *
     * @param offset zero-based index of the first row
     * @param limit  maximum number of rows
     * @return the requested window
     */
    public DownloadPage getAllDownloadsByOffset(int offset, int limit) {
        if (limit <= 0) {
            return new DownloadPage(new ArrayList<>(), getTotalCount(), 0, 0);
        }
        lock.readLock().lock();
        try {
            int totalCount = creationIndex.size();
            int fromIndex = Math.max(0, offset);
            List<Download> pageDownloads = fromIndex < totalCount
                    ? creationIndex.stream().skip(fromIndex).limit(limit).toList()
                    : List.of();
            return new DownloadPage(pageDownloads, totalCount, 0, totalCount);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets downloads by status with pagination.
     *
     * @param status     The status to filter by
     * @param pageNumber The page number (0-based)
     * @param pageSize   The number of downloads per page
     * @return A page of downloads with the specified status
     */
    public DownloadPage getDownloadsByStatus(Download.Status status, int pageNumber, int pageSize) {
        lock.readLock().lock();
        try {
            String cacheKey = "status_" + status + "_" + pageNumber + "_" + pageSize;
            CachedQueryResult cached = cacheLookup(cacheKey);

            if (cached != null && !cached.isExpired()) {
                cacheHits++;
                return new DownloadPage(cached.results, cached.totalCount, pageNumber, pageSize);
            }
            cacheMisses++;

            NavigableSet<Download> statusDownloads = statusOrderIndex.get(status);
            if (statusDownloads == null || statusDownloads.isEmpty()) {
                return new DownloadPage(new ArrayList<>(), 0, pageNumber, pageSize);
            }

            int totalCount = statusDownloads.size();
            int fromIndex = pageNumber * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, totalCount);

            List<Download> pageDownloads = fromIndex < totalCount
                    ? statusDownloads.stream().skip(fromIndex)
                            .limit(toIndex - fromIndex).toList()
                    : List.of();

            // Cache the result
            cacheStore(cacheKey, new CachedQueryResult(pageDownloads, totalCount));

            return new DownloadPage(pageDownloads, totalCount, pageNumber, pageSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Cursor-style status window used by UI pagination. */
    public DownloadPage getDownloadsByStatusByOffset(Download.Status status, int offset,
            int limit) {
        if (limit <= 0) {
            return new DownloadPage(List.of(), getCountByStatus(status), 0, 0);
        }
        lock.readLock().lock();
        try {
            NavigableSet<Download> ordered = statusOrderIndex.get(status);
            int totalCount = ordered == null ? 0 : ordered.size();
            int fromIndex = Math.max(0, offset);
            List<Download> window = ordered != null && fromIndex < totalCount
                    ? ordered.stream().skip(fromIndex).limit(limit).toList()
                    : List.of();
            return new DownloadPage(window, totalCount, 0, totalCount);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets downloads within a time range with pagination.
     *
     * @param from       The start time (inclusive)
     * @param to         The end time (exclusive)
     * @param pageNumber The page number (0-based)
     * @param pageSize   The number of downloads per page
     * @return A page of downloads within the time range
     */
    public DownloadPage getDownloadsByTimeRange(Instant from, Instant to, int pageNumber, int pageSize) {
        lock.readLock().lock();
        try {
            String cacheKey = "time_" + from + "_" + to + "_" + pageNumber + "_" + pageSize;
            CachedQueryResult cached = cacheLookup(cacheKey);

            if (cached != null && !cached.isExpired()) {
                cacheHits++;
                return new DownloadPage(cached.results, cached.totalCount, pageNumber, pageSize);
            }
            cacheMisses++;

            List<Download> timeRangeDownloads = downloads.values().stream()
                    .filter(d -> {
                        Instant createdAt = d.getCreatedAt();
                        return createdAt != null
                                && !createdAt.isBefore(from)
                                && createdAt.isBefore(to);
                    })
                    .sorted(Comparator.comparing(Download::getCreatedAt).reversed())
                    .collect(Collectors.toList());

            int totalCount = timeRangeDownloads.size();
            int fromIndex = pageNumber * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, totalCount);

            List<Download> pageDownloads = fromIndex < totalCount
                    ? timeRangeDownloads.subList(fromIndex, toIndex)
                    : new ArrayList<>();

            // Cache the result
            cacheStore(cacheKey, new CachedQueryResult(pageDownloads, totalCount));

            return new DownloadPage(pageDownloads, totalCount, pageNumber, pageSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets downloads matching a custom predicate with pagination.
     *
     * @param predicate  The predicate to filter downloads
     * @param pageNumber The page number (0-based)
     * @param pageSize   The number of downloads per page
     * @return A page of downloads matching the predicate
     */
    public DownloadPage getDownloadsByPredicate(Predicate<Download> predicate, int pageNumber, int pageSize) {
        lock.readLock().lock();
        try {
            List<Download> filteredDownloads = downloads.values().stream()
                    .filter(predicate)
                    .sorted(Comparator.comparing(Download::getCreatedAt).reversed())
                    .collect(Collectors.toList());

            int totalCount = filteredDownloads.size();
            int fromIndex = pageNumber * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, totalCount);

            List<Download> pageDownloads = fromIndex < totalCount
                    ? filteredDownloads.subList(fromIndex, toIndex)
                    : new ArrayList<>();

            return new DownloadPage(pageDownloads, totalCount, pageNumber, pageSize);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the total count of downloads.
     *
     * @return The total number of downloads
     */
    public int getTotalCount() {
        lock.readLock().lock();
        try {
            return downloads.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Counts groups without materializing or caching an unbounded result list. */
    public <K> Map<K, Integer> getDownloadCounts(java.util.function.Function<Download, K> classifier) {
        Objects.requireNonNull(classifier, "classifier");
        lock.readLock().lock();
        try {
            Map<K, Integer> counts = new HashMap<>();
            for (Download download : downloads.values()) {
                K key = classifier.apply(download);
                if (key != null) {
                    counts.merge(key, 1, Integer::sum);
                }
            }
            return Map.copyOf(counts);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets the count of downloads with a specific status.
     *
     * @param status The status to count
     * @return The number of downloads with the specified status
     */
    public int getCountByStatus(Download.Status status) {
        lock.readLock().lock();
        try {
            Set<String> statusDownloads = statusIndex.get(status);
            return statusDownloads != null ? statusDownloads.size() : 0;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Gets a breakdown of download counts by status.
     *
     * @return A map of status to count
     */
    public Map<Download.Status, Integer> getStatusBreakdown() {
        lock.readLock().lock();
        try {
            Map<Download.Status, Integer> breakdown = new HashMap<>();
            for (Map.Entry<Download.Status, Set<String>> entry : statusIndex.entrySet()) {
                breakdown.put(entry.getKey(), entry.getValue().size());
            }
            return breakdown;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Removes downloads matching a predicate.
     *
     * @param predicate The predicate to match downloads for removal
     * @return The number of removed downloads
     */
    public int removeDownloadsMatching(Predicate<Download> predicate) {
        return removeDownloadsMatching(predicate, ignored -> { });
    }

    /** Runs cleanup for exactly the removed rows, after releasing the repository lock. */
    public int removeDownloadsMatching(Predicate<Download> predicate,
            java.util.function.Consumer<Download> afterRemoval) {
        List<Download> removedDownloads = new java.util.ArrayList<>();
        lock.writeLock().lock();
        try {
            List<Download> downloadsToRemove = downloads.values().stream()
                    .filter(predicate)
                    .collect(Collectors.toList());

            int removedCount = 0;
            for (Download download : downloadsToRemove) {
                Download removed = downloads.remove(download.getId());
                if (removed != null) {
                    removeFromIndices(removed);
                    removedCount++;
                    removedDownloads.add(removed);
                }
            }

            if (removedCount > 0) {
                invalidateCacheForBulkRemove(downloadsToRemove);
                LOGGER.info("Removed " + removedCount + " downloads matching predicate");
            }

        } finally {
            lock.writeLock().unlock();
        }
        removedDownloads.forEach(afterRemoval);
        return removedDownloads.size();
    }

    /**
     * Updates the indices for a download.
     *
     * @param download The download to index
     */
    private void updateIndices(Download download) {
        // Update status index
        Set<String> statusSet = statusIndex.get(download.getStatus());
        if (statusSet != null) {
            statusSet.add(download.getId());
        }
        creationIndex.add(download);
        NavigableSet<Download> statusOrder = statusOrderIndex.get(download.getStatus());
        if (statusOrder != null) {
            statusOrder.add(download);
        }
    }

    /**
     * Removes a download from all indices.
     *
     * @param download The download to remove from indices
     */
    private void removeFromIndices(Download download) {
        // Remove from status index
        for (Set<String> statusSet : statusIndex.values()) {
            statusSet.remove(download.getId());
        }
        creationIndex.remove(download);
        for (NavigableSet<Download> statusOrder : statusOrderIndex.values()) {
            statusOrder.remove(download);
        }
    }

    /**
     * OPTIMIZATION: Selective cache invalidation to preserve unaffected cache
     * entries. Only invalidates cache entries that could be affected by the
     * operation.
     */
    private void invalidateCache() {
        synchronized (cacheLock) {
            queryCache.clear();
        }
        fullInvalidations++;
    }

    /**
     * Looks up a cached query result under the cache lock. get() on an
     * access-order LinkedHashMap relinks entries, so lookups must serialize
     * with stores and invalidations even though the caller holds only the
     * (shared) repository read lock.
     */
    private CachedQueryResult cacheLookup(String cacheKey) {
        synchronized (cacheLock) {
            return queryCache.get(cacheKey);
        }
    }

    /**
     * Stores a query result under the cache lock. put() inserts and may
     * evict the eldest entry; it must serialize with every other cache
     * access.
     */
    private void cacheStore(String cacheKey, CachedQueryResult result) {
        synchronized (cacheLock) {
            queryCache.put(cacheKey, result);
        }
    }

    /**
     * OPTIMIZATION: Invalidates only cache entries affected by adding a
     * download. This preserves most cache entries and only removes those that
     * would be stale.
     *
     * @param download The download being added
     */
    private void invalidateCacheForAdd(Download download) {
        Set<String> keysToRemove = new HashSet<>();

        synchronized (cacheLock) {
            for (String cacheKey : queryCache.keySet()) {
                // Invalidate "all" queries (they include all downloads)
                if (cacheKey.startsWith("all_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate status queries for the download's status
                else if (cacheKey.startsWith("status_" + download.getStatus() + "_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate time range queries that include the download's creation time
                else if (cacheKey.startsWith("time_")) {
                    if (timeRangeIncludesDownload(cacheKey, download)) {
                        keysToRemove.add(cacheKey);
                    }
                }
            }

            keysToRemove.forEach(queryCache::remove);
        }
        selectiveInvalidations++;
        LOGGER.debug("Selectively invalidated " + keysToRemove.size() + " cache entries for add operation");
    }

    /**
     * OPTIMIZATION: Invalidates only cache entries affected by removing a
     * download.
     *
     * @param download The download being removed
     */
    private void invalidateCacheForRemove(Download download) {
        Set<String> keysToRemove = new HashSet<>();

        synchronized (cacheLock) {
            for (String cacheKey : queryCache.keySet()) {
                // Invalidate "all" queries (they include all downloads)
                if (cacheKey.startsWith("all_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate status queries for the download's status
                else if (cacheKey.startsWith("status_" + download.getStatus() + "_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate time range queries that include the download's creation time
                else if (cacheKey.startsWith("time_")) {
                    if (timeRangeIncludesDownload(cacheKey, download)) {
                        keysToRemove.add(cacheKey);
                    }
                }
            }

            keysToRemove.forEach(queryCache::remove);
        }
        selectiveInvalidations++;
        LOGGER.debug("Selectively invalidated " + keysToRemove.size() + " cache entries for remove operation");
    }

    /**
     * OPTIMIZATION: Invalidates only cache entries affected by status change.
     * This is the most complex case as it affects both old and new status
     * queries.
     *
     * @param oldStatus The previous status
     * @param newStatus The new status
     */
    private void invalidateCacheForStatusChange(Download.Status oldStatus, Download.Status newStatus) {
        Set<String> keysToRemove = new HashSet<>();

        synchronized (cacheLock) {
            for (String cacheKey : queryCache.keySet()) {
                // Invalidate "all" queries (ordering might change)
                if (cacheKey.startsWith("all_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate status queries for both old and new status
                else if (cacheKey.startsWith("status_" + oldStatus + "_")
                        || cacheKey.startsWith("status_" + newStatus + "_")) {
                    keysToRemove.add(cacheKey);
                }
                // Time range queries are not affected by status changes alone
            }

            keysToRemove.forEach(queryCache::remove);
        }
        selectiveInvalidations++;
        LOGGER.debug("Selectively invalidated " + keysToRemove.size() + " cache entries for status change: " + oldStatus
                + " -> " + newStatus);
    }

    /**
     * Helper method to check if a time range cache key includes a specific
     * download. Parses the cache key to extract time bounds and checks if
     * download falls within range.
     *
     * @param cacheKey The time range cache key (format:
     *                 "time_from_to_pageNumber_pageSize")
     * @param download The download to check
     * @return true if the download's creation time falls within the cached time
     *         range
     */
    private boolean timeRangeIncludesDownload(String cacheKey, Download download) {
        try {
            // Parse cache key format: "time_from_to_pageNumber_pageSize"
            String[] parts = cacheKey.split("_");
            if (parts.length >= 3 && "time".equals(parts[0])) {
                Instant from = Instant.parse(parts[1]);
                Instant to = Instant.parse(parts[2]);
                Instant downloadTime = download.getCreatedAt();

                return !downloadTime.isBefore(from) && downloadTime.isBefore(to);
            }
        } catch (Exception e) {
            // If parsing fails, invalidate to be safe
            LOGGER.debug("Failed to parse time range cache key: " + cacheKey + ", invalidating to be safe");
            return true;
        }
        return false;
    }

    /**
     * OPTIMIZATION: Invalidates cache entries affected by bulk removal of
     * downloads. More efficient than individual removals when removing multiple
     * downloads.
     *
     * @param removedDownloads The list of downloads being removed
     */
    private void invalidateCacheForBulkRemove(List<Download> removedDownloads) {
        if (removedDownloads.isEmpty()) {
            return;
        }

        Set<String> keysToRemove = new HashSet<>();
        Set<Download.Status> affectedStatuses = removedDownloads.stream()
                .map(Download::getStatus)
                .collect(Collectors.toSet());

        synchronized (cacheLock) {
            for (String cacheKey : queryCache.keySet()) {
                // Invalidate "all" queries (they include all downloads)
                if (cacheKey.startsWith("all_")) {
                    keysToRemove.add(cacheKey);
                } // Invalidate status queries for affected statuses
                else if (cacheKey.startsWith("status_")) {
                    String statusPart = cacheKey.substring(7); // Remove "status_" prefix
                    String statusName = statusPart.split("_")[0];
                    try {
                        Download.Status status = Download.Status.valueOf(statusName);
                        if (affectedStatuses.contains(status)) {
                            keysToRemove.add(cacheKey);
                        }
                    } catch (IllegalArgumentException e) {
                        // Invalid status in cache key, remove to be safe
                        keysToRemove.add(cacheKey);
                    }
                } // Invalidate time range queries that include any of the removed downloads
                else if (cacheKey.startsWith("time_")) {
                    boolean shouldInvalidate = removedDownloads.stream()
                            .anyMatch(download -> timeRangeIncludesDownload(cacheKey, download));
                    if (shouldInvalidate) {
                        keysToRemove.add(cacheKey);
                    }
                }
            }

            keysToRemove.forEach(queryCache::remove);
        }
        selectiveInvalidations++;
        LOGGER.debug("Selectively invalidated " + keysToRemove.size() + " cache entries for bulk remove of "
                + removedDownloads.size() + " downloads");
    }

    /**
     * Clears all downloads from the repository.
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            downloads.clear();
            statusIndex.values().forEach(Set::clear);
            creationIndex.clear();
            statusOrderIndex.values().forEach(Set::clear);
            invalidateCache(); // Full invalidation is appropriate when clearing all
            LOGGER.info("Cleared all downloads from repository");
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gets cache statistics.
     *
     * @return A map containing cache statistics
     */
    public Map<String, Object> getCacheStats() {
        lock.readLock().lock();
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("cacheSize", queryCache.size());
            stats.put("maxCacheSize", MAX_CACHE_SIZE);
            stats.put("cacheTtlMs", CACHE_TTL_MS);

            // Count expired entries
            long expiredCount = queryCache.values().stream()
                    .mapToLong(result -> result.isExpired() ? 1 : 0)
                    .sum();
            stats.put("expiredEntries", expiredCount);

            // OPTIMIZATION: Add selective invalidation performance metrics
            stats.put("cacheHits", cacheHits);
            stats.put("cacheMisses", cacheMisses);
            stats.put("selectiveInvalidations", selectiveInvalidations);
            stats.put("fullInvalidations", fullInvalidations);

            // Calculate hit rate
            long totalRequests = cacheHits + cacheMisses;
            double hitRate = totalRequests > 0 ? (double) cacheHits / totalRequests : 0.0;
            stats.put("hitRate", hitRate);

            // Calculate selective vs full invalidation ratio
            long totalInvalidations = selectiveInvalidations + fullInvalidations;
            double selectiveRatio = totalInvalidations > 0 ? (double) selectiveInvalidations / totalInvalidations : 0.0;
            stats.put("selectiveInvalidationRatio", selectiveRatio);

            return stats;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Performs cleanup of expired cache entries.
     */
    public void cleanupCache() {
        lock.writeLock().lock();
        try {
            queryCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * OPTIMIZATION: Resets cache performance metrics for monitoring purposes.
     * Useful for performance testing and baseline measurements.
     */
    public void resetCacheMetrics() {
        cacheHits = 0;
        cacheMisses = 0;
        selectiveInvalidations = 0;
        fullInvalidations = 0;
        LOGGER.info("Cache performance metrics reset");
    }
}
