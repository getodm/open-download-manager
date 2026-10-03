package org.manager.download;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only repository queries: lookups by id, status, and creation time
 * range, counts, offset-slice pagination, and memory-usage statistics.
 */
public interface DownloadQueries {

    /**
     * Gets a download by its ID.
     *
     * @param id The download ID
     * @return The download, or null if not found
     */
    Download getDownload(String id);

    /**
     * Gets all downloads.
     *
     * @return A list of all downloads
     */
    List<Download> getAllDownloads();

    /**
     * Gets downloads with pagination support.
     *
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads
     */
    List<Download> getDownloads(int offset, int limit);

    /**
     * Gets the total count of downloads.
     *
     * @return The total number of downloads
     */
    int getDownloadCount();

    /**
     * Counts the entire repository by a read-only classifier, independently of
     * pagination. A null key excludes a download. The classifier must not modify
     * the repository; implementations may invoke it under a read lock.
     */
    <K> Map<K, Integer> getDownloadCounts(Function<Download, K> classifier);

    /**
     * Gets downloads with the specified status.
     *
     * @param status The status to filter by
     * @return A list of downloads with the specified status
     */
    List<Download> getDownloadsByStatus(Download.Status status);

    /**
     * Gets downloads with the specified status with pagination support.
     *
     * @param status The status to filter by
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads with the specified status
     */
    List<Download> getDownloadsByStatus(Download.Status status, int offset, int limit);

    /**
     * Gets the count of downloads with the specified status.
     *
     * @param status The status to filter by
     * @return The number of downloads with the specified status
     */
    int getDownloadCountByStatus(Download.Status status);

    /**
     * Gets downloads created within the specified time range.
     *
     * @param from The start time (inclusive)
     * @param to The end time (exclusive)
     * @return A list of downloads created within the time range
     */
    List<Download> getDownloadsByTimeRange(Instant from, Instant to);

    /**
     * Gets downloads created within the specified time range with pagination.
     *
     * @param from The start time (inclusive)
     * @param to The end time (exclusive)
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads created within the time range
     */
    List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit);

    /**
     * Gets the current memory usage statistics.
     *
     * @return A map containing memory usage information
     */
    Map<String, Object> getMemoryUsageStats();
}
