package org.manager.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

import org.aria2.Aria2ToolManager;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.ShutdownCoordinator;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.di.DependencyContainer;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;
import org.manager.download.action.AfterCompletionActionManager;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.download.handler.RetryEventInterceptor;
import org.manager.exception.ErrorHandler;
import org.manager.folder.FolderMonitorService;
import org.manager.folder.FolderMonitorSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;
import org.manager.tools.ToolManagerFactory;
import org.manager.util.DescriptorStaging;
import org.manager.util.ExecutorServiceManager;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.manager.url.DownloadUrlPolicy;

/**
 * Implementation of the DownloadManager interface using a modular handler
 * architecture. Different download types are handled by specialized handlers.
 */
public class DownloadManagerImpl implements DownloadManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadManagerImpl.class);
    private static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 5;
    private static final String STATE_FILE = "odm-state.json";
    private static final String STATE_DB_FILE = "odm-state.db";

    private final PaginatedDownloadRepository downloadRepository;
    private final Map<String, String> gidToIdMap; // Handler GID -> download ID
    private final Set<DownloadListener> listeners;
    private final ExecutorService eventExecutor;
    private final AtomicInteger runningDownloads;
    /** Suppresses queue advancement while one or more bulk-pause operations drain active work. */
    private final AtomicInteger bulkPauseOperations;
    /** Download IDs currently holding a concurrency slot; guards exactly-once release. */
    private final Set<String> runningDownloadIds;
    /** Download IDs currently undergoing a destination move. */
    private final Set<String> relocatingDownloadIds;
    /** Current start-generation per download id; late events of superseded generations are dropped. */
    private final ConcurrentHashMap<String, Long> attemptGenerations;
    /** Pause invalidates pending start/resume results without discarding resumable engine ownership. */
    private final ConcurrentHashMap<String, Long> pauseRevisions = new ConcurrentHashMap<>();
    /** Generation that already reached a terminal state per download id; first terminal wins. */
    private final ConcurrentHashMap<String, Long> terminalGenerations;
    /** Last generation that claimed the one-shot proxychains-to-Curl handoff. */
    private final ConcurrentHashMap<String, Long> proxychainsCurlFallbackGenerations;
    /** Serializes generation bumping against terminal-CAS bookkeeping. */
    private final Object generationLock = new Object();
    /** Serializes the check-and-add concurrency-slot claim (the admission decision). */
    private final Object admissionLock = new Object();
    private final Map<String, Integer> pendingRemovals = new java.util.HashMap<>();
    private final AtomicInteger bulkRemovalOperations = new AtomicInteger();
    private final Object networkSettingsLock = new Object();
    private volatile boolean networkRestartInProgress;

    private enum AdmissionResult {
        CLAIMED,
        DUPLICATE,
        FULL,
        INELIGIBLE
    }
    private final AtomicBoolean isShuttingDown;
    private final DependencyContainer container;
    private final ExecutorServiceManager executorManager;
    private final ObjectMapper objectMapper;
    private final SqliteDownloadStateStore stateStore;
    private final DownloadCleanupManager cleanupManager;
    private final ShutdownCoordinator shutdownCoordinator;
    private final ManagerClipboardService clipboard;
    private final FolderWatchingService folderWatching;
    private final ProxyRotationSupport proxyRotation;
    private final DownloadServicesScheduler servicesScheduler;

    // OPTIMIZATION: Efficient listener management
    private final Map<String, DownloadHandler> activeHandlers; // download ID -> handler
    private final ReusableDownloadListener reusableListener;

    // Backward compatibility fields
    private Path defaultDownloadDirectory;
    private final Set<String> activeDownloadsBeforeExit = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new DownloadManagerImpl instance.
     */
    public DownloadManagerImpl() {
        this(new DependencyContainer());
    }

    /**
     * Creates a new DownloadManagerImpl instance with dependency injection.
     *
     * @param container The dependency container to use
     */
    public DownloadManagerImpl(DependencyContainer container) {
        this.container = container;
        this.gidToIdMap = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArraySet<>();
        this.runningDownloads = new AtomicInteger(0);
        this.bulkPauseOperations = new AtomicInteger(0);
        this.runningDownloadIds = ConcurrentHashMap.newKeySet();
        this.relocatingDownloadIds = ConcurrentHashMap.newKeySet();
        this.attemptGenerations = new ConcurrentHashMap<>();
        this.terminalGenerations = new ConcurrentHashMap<>();
        this.proxychainsCurlFallbackGenerations = new ConcurrentHashMap<>();
        this.isShuttingDown = new AtomicBoolean(false);
        this.objectMapper = createStateObjectMapper();
        Path stateDirectory = prepareStateDirectory();
        this.stateStore = new SqliteDownloadStateStore(
                stateDirectory.resolve(STATE_DB_FILE),
                stateDirectory.resolve(STATE_FILE),
                this.objectMapper);

        // OPTIMIZATION: Initialize efficient listener management
        this.activeHandlers = new ConcurrentHashMap<>();
        this.reusableListener = new ReusableDownloadListener();

        // Manager-scoped executor lifecycle: this manager's shutdown hook
        // terminates exactly these pools. The process-wide singleton stays
        // alive for other managers and factory services; only the
        // whole-application path (ApplicationFactory.shutdown) may end it.
        this.executorManager = ExecutorServiceManager.create();
        this.eventExecutor = executorManager.getEventExecutor();

        // Initialize dependencies
        initializeDependencies();

        // Initialize enhanced components
        this.downloadRepository = new PaginatedDownloadRepository(getGlobalSettings());
        this.cleanupManager = new DownloadCleanupManager(this.downloadRepository, getGlobalSettings());
        this.shutdownCoordinator = new ShutdownCoordinator();
        // The clipboard service stays owned by THIS manager (its shutdown
        // hook drains it). The old constructor self-registered the service
        // into the global ApplicationContext — a domain object mutating the
        // application factory from its constructor, and a cross-generation
        // leak vector; explicit registrants use the factory API directly.
        this.clipboard = new ManagerClipboardService(this, this::getGlobalSettings);

        // Initialize folder monitoring services
        this.folderWatching = new FolderWatchingService(this, this::getGlobalSettings,
                defaultDownloadDirectory);
        this.proxyRotation = new ProxyRotationSupport(
                new org.manager.proxy.ProxyRotationManager(),
                this::getGlobalSettings,
                executorManager);
        this.servicesScheduler = new DownloadServicesScheduler(
                executorManager,
                this::getGlobalSettings,
                this::getHandlerFactory,
                isShuttingDown::get,
                () -> saveState().join());

        // Use the download directory from global settings if empty use default
        if (getGlobalSettings().getDefaultDownloadDirectory() == null
                || getGlobalSettings().getDefaultDownloadDirectory().toString().isEmpty()) {
            this.defaultDownloadDirectory = org.manager.util.OdmPaths.downloadDirectory();
        } else {
            this.defaultDownloadDirectory = Paths.get(getGlobalSettings().getDefaultDownloadDirectory().toString());
        }
        // Register shutdown hooks
        new ManagerShutdownHooks(
                shutdownCoordinator,
                isShuttingDown,
                activeDownloadsBeforeExit,
                () -> downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads().stream()
                        .filter(DownloadManagerImpl::isResumableActiveStatus)
                        .toList(),
                this::pauseAllDownloads,
                this::saveStateForShutdown,
                servicesScheduler,
                proxyRotation,
                cleanupManager,
                this::getHandlerFactory,
                this::getActionManager,
                clipboard,
                folderWatching,
                stateStore,
                container,
                executorManager).registerAll();
    }

    /**
     * Initializes all dependencies in the container.
     */
    private void initializeDependencies() {
        // Register GlobalSettings singleton (only if not already registered)
        if (!container.isRegistered(GlobalSettings.class)) {
            container.registerSingletonFactory(GlobalSettings.class, () -> {
                // Use ApplicationContext for default settings instead of creating new instance
                return ApplicationContext.getGlobalSettings();
            });
        }

        // Register ToolManagerFactory
        container.registerSingletonFactory(ToolManagerFactory.class,
                () -> ApplicationContext.getToolManagerFactory());

        // Register DownloadSettingsFactory
        container.registerSingletonFactory(DownloadSettingsFactory.class,
                () -> new DownloadSettingsFactory(container.getRequired(GlobalSettings.class)));

        // Register AfterCompletionActionManager
        container.registerSingletonFactory(AfterCompletionActionManager.class,
                AfterCompletionActionManager::new);

        // Register DownloadHandlerFactory
        container.registerSingletonFactory(DownloadHandlerFactory.class, () -> new DownloadHandlerFactory(
                container.getRequired(GlobalSettings.class),
                container.getRequired(DownloadSettingsFactory.class),
                executorManager.getGeneralExecutor(),
                container.getRequired(ToolManagerFactory.class), this::getAllDownloads));

        // Check dependencies asynchronously
        executorManager.submit(() -> {
            ToolManagerFactory toolFactory = container.getRequired(ToolManagerFactory.class);
            toolFactory.checkAllToolsAsync().thenAccept(result -> {
                LOGGER.info("Tool availability check completed: " + result);
            });
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                ErrorHandler.executeWithRetry(
                        () -> initializeComponents(),
                        ErrorHandler.RetryConfig.defaultConfig(),
                        "download manager initialization");

                // Start cleanup manager
                cleanupManager.start();

                LOGGER.info("Download manager initialized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize download manager", e);
                throw new CompletionException(e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("Initiating coordinated shutdown...");

            // OPTIMIZATION: Clean up all active handler listeners
            for (Map.Entry<String, DownloadHandler> entry : activeHandlers.entrySet()) {
                try {
                    entry.getValue().removeDownloadListener(reusableListener);
                } catch (Exception e) {
                    LOGGER.warn("Error removing listener during shutdown for download: " + entry.getKey(),
                            e);
                }
            }
            activeHandlers.clear();

            return shutdownCoordinator.initiateShutdown();
        } else {
            LOGGER.info("Download manager shutdown already in progress");
            return shutdownCoordinator.waitForShutdownCompletion();
        }
    }

    @Override
    public Download createDownload(URI uri, Path destination) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(uri);

        try {
            return ErrorHandler.executeWithRetry(
                    () -> createDownloadInternal(source, destination),
                    ErrorHandler.RetryConfig.noRetry(),
                    "create download");
        } catch (Exception e) {
            LOGGER.warn("Failed to create download", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Download createTorrentDownload(Path torrentFile, Path destination) {
        if (torrentFile == null) {
            throw new IllegalArgumentException("Torrent file path cannot be null");
        }
        if (DownloadUrlPolicy.require(torrentFile.toUri()).protocol() != Download.Protocol.TORRENT) {
            throw new IllegalArgumentException("A local torrent descriptor is required");
        }

        try {
            return ErrorHandler.executeWithRetry(
                    () -> createTorrentDownloadInternal(torrentFile, destination),
                    ErrorHandler.RetryConfig.noRetry(),
                    "create torrent download");
        } catch (Exception e) {
            LOGGER.warn("Failed to create torrent download", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Download createMagnetDownload(URI magnetUri, Path destination) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(magnetUri);
        if (source.protocol() != Download.Protocol.MAGNET) {
            throw new IllegalArgumentException("A magnet URI is required");
        }
        Download download = Download.fromSource(source);
        download.setType(Download.Type.ARIA2);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for magnet downloads
        download.setSettings(getSettingsFactory().createSettings(
                Download.Type.ARIA2, download.getProtocol()));

        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createMetaLinkDownload(URI metaLinkUri, Path destination) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(metaLinkUri);
        if (source.protocol() == Download.Protocol.MAGNET) {
            throw new IllegalArgumentException("A Metalink source is required");
        }
        Download download = Download.fromSource(source);
        download.setProtocol(Download.Protocol.METALINK);
        download.setType(Download.Type.ARIA2);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for metaLink downloads
        download.setSettings(getSettingsFactory().createSettings(
                Download.Type.ARIA2, download.getProtocol()));

        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createYoutubeDownload(URI videoUrl, Path destination, Map<String, String> options) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(videoUrl).requireWeb();
        Download download = Download.fromSource(source);
        download.setType(Download.Type.YOUTUBE);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for YouTube downloads
        download.setSettings(getSettingsFactory().createSettings(
                Download.Type.YOUTUBE, download.getProtocol()));

        // Add YouTube download options
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                download.getSettings().setOption(entry.getKey(), entry.getValue());
            }
        }

        download.initSettings(getSettingsFactory());
        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createWebsiteDownload(URI websiteUrl, Path destination, Map<String, String> options) {
        DownloadUrlPolicy.ValidatedSource source = DownloadUrlPolicy.require(websiteUrl).requireWeb();
        Download download = Download.fromSource(source);
        download.setType(Download.Type.WEBSITE_SCRAPING);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for website scraping
        download.setSettings(getSettingsFactory().createSettings(
                Download.Type.WEBSITE_SCRAPING, download.getProtocol()));

        // Add httrack options
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                download.getSettings().setOption(entry.getKey(), entry.getValue());
            }
        }

        download.initSettings(getSettingsFactory());
        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public CompletableFuture<Void> queueDownload(Download download) {
        return submitToQueue(download, false);
    }

    @Override
    public CompletableFuture<Void> queueDownloadFromBackgroundSource(Download download) {
        boolean manualStartRequired = !getGlobalSettings()
                .getBooleanProperty("ui.startAutomatically", true);
        return submitToQueue(download, manualStartRequired);
    }

    @Override
    public CompletableFuture<Void> queueDownloadForManualStart(Download download) {
        return submitToQueue(download, true);
    }

    private CompletableFuture<Void> submitToQueue(Download download, boolean manualStartRequired) {
        if (download == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Download cannot be null"));
            return future;
        }

        return CompletableFuture.runAsync(() -> {
            try {
                ErrorHandler.executeWithRetry(
                        () -> queueDownloadInternal(download, manualStartRequired),
                        ErrorHandler.RetryConfig.noRetry(),
                        "queue download: " + download.getId());
            } catch (Exception e) {
                LOGGER.warn("Failed to queue download: " + download.getId(), e);
                rejectBeforeStart(download, messageOf(e));
                throw new CompletionException("Failed to queue download: " + download.getId(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> startDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            // An explicit start releases any manual hold, including the hold
            // applied to a background-discovered item. If admission is
            // currently unavailable it remains a normal, auto-admissible
            // queued download.
            if (download != null) {
                download.setManualStartRequired(false);
            }
            startDownloadInternal(download);
        }, executorManager.getGeneralExecutor());
    }

    /**
     * OPTIMIZED: Start download with reusable listener to reduce memory
     * allocation. This version uses a single reusable listener instead of
     * creating new instances.
     */
    private void startDownloadInternal(Download download) {
        startDownloadInternal(download, false);
    }

    /**
     * Starts one logical operation. A caller that already claimed admission
     * (the recovered-resume path) may pass {@code admissionAlreadyClaimed};
     * every other caller must win the single-flight claim before a handler is
     * invoked.
     */
    private void startDownloadInternal(Download download, boolean admissionAlreadyClaimed) {
        startDownloadInternal(download, admissionAlreadyClaimed, false);
    }

    private void startDownloadInternal(Download download, boolean admissionAlreadyClaimed, boolean fromQueue) {
        long generation = 0;
        try {
            if (download == null) {
                throw new IllegalArgumentException("Download cannot be null");
            }
            download.validateSourcesForTransfer();
            new DownloadSettingsFactory(getGlobalSettings()).applyInheritedProxy(download.getSettings());
            if (holdForUnavailableTor(download)) {
                if (admissionAlreadyClaimed) {
                    releaseRunningSlot(download.getId());
                }
                return;
            }
            if (!isStartAllowedBySchedule(download)) {
                if (admissionAlreadyClaimed) {
                    releaseRunningSlot(download.getId());
                }
                requeueAfterDeniedAdmission(download);
                return;
            }

            // Admission is the atomic slot claim: it happens BEFORE any
            // start submission, so concurrent starts and direct
            // startDownload calls can never overshoot the limit
            if (!admissionAlreadyClaimed) {
                AdmissionResult admission = claimRunningSlot(download, fromQueue);
                if (admission == AdmissionResult.INELIGIBLE) { return; }
                if (admission == AdmissionResult.DUPLICATE) {
                    LOGGER.warn("Ignoring duplicate start for active download "
                            + download.getId() + " (status " + download.getStatus() + ")");
                    return;
                }
                if (admission == AdmissionResult.FULL) {
                    requeueAfterDeniedAdmission(download);
                    return;
                }
            }

            LOGGER.info("Starting download internally: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");

            // Every start supersedes the previous operation on this id: a
            // fresh generation token isolates late results of the old one
            final long pauseRevision;
            synchronized (generationLock) {
                generation = nextAttemptGeneration(download);
                pauseRevision = pauseRevisions.getOrDefault(download.getId(), 0L);
                if (download.getStatus() == Download.Status.ERROR) {
                    download.recordRetry();
                }
                downloadRepository.updateDownloadStatus(download, Download.Status.STARTING);
            }

            // Uniquify once before the first engine start. Retries, resumes,
            // recovered records and mirror updates reuse the stamped name:
            // restored records are never armed (the JSON ctor leaves the flag false). Mirror updates arrive
            // here via updateWebsiteMirror -> startDownload.
            // Media names are resolved asynchronously by yt-dlp before its transfer.
            if (download.getType() != Download.Type.YOUTUBE) {
                download.prepareUniquifiedOutput(() -> {
                    GlobalSettings settings = getGlobalSettings();
                    if (settings != null && settings.isUniquifyOutputName()) {
                        OutputNameUniquifier.applyTo(download, getAllDownloads());
                    }
                });
            }

            // Get the appropriate handler for this download type
            DownloadHandler handler = getHandlerFactory().getHandler(download);

            if (handler == null) {
                String noHandlerMessage = "No suitable handler found for download type: " + download.getType();
                failStart(download, generation, noHandlerMessage, null);
                return;
            }

            // Wrap with proxy rotation when enabled: retries rate-limited /
            // blocked downloads through different proxies from the proxy list
            handler = proxyRotation.maybeWrap(handler, download);

            // OPTIMIZATION: Store handler reference for cleanup and use reusable listener
            DownloadHandler previous = activeHandlers.put(download.getId(), handler);
            if (previous instanceof RetryEventInterceptor interceptor && previous != handler) {
                // This download was started again while a retry wrapper was
                // still operating on it: retire the old wrapper so its
                // scheduled retry never races the new operation
                interceptor.interceptReplaced(download.getId());
            }
            // Handlers are shared per download type and the listener set is a
            // CopyOnWriteArraySet, so re-adding the reusable listener is
            // idempotent. It stays attached for the handler's lifetime and is
            // only removed at manager shutdown; removing it here would detach
            // the manager from every other running download of the same type.
            handler.addDownloadListener(reusableListener);

            // Start the download with the handler
            final long startGeneration = generation;
            CompletableFuture<String> future = handler.startDownload(download);
            future.thenAccept(gid -> {
                synchronized (generationLock) {
                    if (!isCurrentAttempt(download.getId(), startGeneration)) {
                        LOGGER.debug("Dropping stale generation result for download: " + download.getName());
                        return;
                    }
                    if (isTerminalAttempt(download.getId(), startGeneration)) {
                        LOGGER.debug("Dropping start result after terminal event for download: "
                                + download.getName());
                        return;
                    }
                    if (pauseRevisions.getOrDefault(download.getId(), 0L) != pauseRevision
                            || download.getStatus() == Download.Status.PAUSED) {
                        // An RPC start may publish its GID after the initial pause.
                        // Stop that newly published job while retaining it for Resume.
                        pauseDownload(download, download.getPauseReason(), true);
                        return;
                    }
                    LOGGER.info("Download handler returned GID: " + gid + " for download: " + download.getName());
                    if (gid != null) {
                        download.setGid(gid);
                        gidToIdMap.put(gid, download.getId());
                        // Route the transition through the repository so status
                        // indexes stay consistent (bare setStatus leaves the id
                        // in the QUEUED index and breaks status queries).
                        downloadRepository.updateDownloadStatus(download,
                                reportedRunningStatus(download));
                    } else {
                        LOGGER.warn("Handler returned null GID for download: " + download.getName());
                        failStart(download, startGeneration, "Handler returned null GID", null);
                    }
                }
            }).exceptionally(e -> {
                if (!isCurrentAttempt(download.getId(), startGeneration)) {
                    LOGGER.debug("Dropping stale generation failure for download: " + download.getName());
                    return null;
                }
                if (pauseRevisions.getOrDefault(download.getId(), 0L) != pauseRevision) {
                    return null;
                }
                if (maybeFallbackProxychainsToCurl(download, e, startGeneration)) {
                    return null;
                }
                failStart(download, startGeneration, messageOf(e), e);
                return null;
            });

        } catch (Exception e) {
            if (download != null && generation != 0
                    && maybeFallbackProxychainsToCurl(download, e, generation)) {
                return;
            }
            if (download != null) {
                if (generation != 0) {
                    failStart(download, generation, messageOf(e), e);
                } else {
                    releaseRunningSlot(download.getId());
                    rejectBeforeStart(download, messageOf(e));
                    startNextQueuedDownload();
                }
            }
            LOGGER.error("Failed to start download", e);
        }
    }

    private static String messageOf(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /** Rejected drafts must not enter the repository's history or status indexes. */
    private void rejectBeforeStart(Download download, String message) {
        if (downloadRepository.getDownload(download.getId()) != null) {
            downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
        } else {
            download.setStatus(Download.Status.ERROR);
        }
        download.setErrorMessage(message);
        notifyDownloadError(download, message);
    }

    /** Completes a failed start exactly once and releases its admission slot. */
    private void failStart(Download download, long generation, String message, Throwable failure) {
        if (!isCurrentAttempt(download.getId(), generation)
                || download.getStatus() == Download.Status.PAUSED
                || !tryBeginTerminal(download.getId(), generation)) {
            return;
        }
        downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
        download.setErrorMessage(message);
        cleanupDownloadResources(download.getId(), generation);
        notifyDownloadError(download, message);
        if (failure != null) {
            LOGGER.error("Failed to start download: " + download.getName(), failure);
        }
        startNextQueuedDownload();
    }

    /** Stamps a fresh operation generation on the download and records it as current. */
    private long nextAttemptGeneration(Download download) {
        synchronized (generationLock) {
            long generation = attemptGenerations.merge(download.getId(), 1L, Long::sum);
            download.setAttemptGeneration(generation);
            // A restarted operation must be able to reach its own terminal
            // state: the previous generation's terminal marker is obsolete
            terminalGenerations.remove(download.getId());
            return generation;
        }
    }

    /** True when the given generation is still the download's current operation. */
    private boolean isCurrentAttempt(String downloadId, long generation) {
        Long current = attemptGenerations.get(downloadId);
        return current != null && current.longValue() == generation;
    }

    /**
     * Atomically claims a concurrency slot for the download. This single
     * lock-protected check-and-add IS the admission decision: when it
     * returns FULL the caller must not submit any start. A claim by the
     * current slot holder is DUPLICATE: one logical download is strictly
     * single-flight.
     */
    private AdmissionResult claimRunningSlot(Download download, boolean fromQueue) {
        synchronized (admissionLock) {
            String downloadId = download.getId();
            if (pendingRemovals.containsKey(downloadId)
                    || (fromQueue && (bulkRemovalOperations.get() > 0
                    || download.getStatus() != Download.Status.QUEUED
                    || downloadRepository.getDownload(downloadId) != download))) {
                return AdmissionResult.INELIGIBLE;
            }
            if (runningDownloadIds.contains(downloadId)) {
                return AdmissionResult.DUPLICATE;
            }
            if (networkRestartInProgress
                    || runningDownloads.get() >= getGlobalSettings().getMaxConcurrentDownloads()) {
                return AdmissionResult.FULL;
            }
            runningDownloadIds.add(downloadId);
            runningDownloads.incrementAndGet();
            return AdmissionResult.CLAIMED;
        }
    }

    /** Releases admission without discarding resumable handler state. */
    private void releaseRunningSlot(String downloadId) {
        synchronized (admissionLock) {
            if (runningDownloadIds.remove(downloadId)) {
                runningDownloads.decrementAndGet();
            }
        }
    }

    /** Leaves a non-admitted download queued, with a position and a queued event when new to the queue. */
    private void requeueAfterDeniedAdmission(Download download) {
        if (download.getStatus() == Download.Status.ERROR) {
            download.recordRetry();
        }
        boolean alreadyQueued = download.getStatus() == Download.Status.QUEUED;
        if (!alreadyQueued && download.getQueuePosition() == 0) {
            download.setQueuePosition(nextQueuePosition());
        }
        downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
        if (!alreadyQueued) {
            notifyDownloadQueued(download);
        }
        LOGGER.info("Download " + download.getName()
                + " queued: concurrent limit of " + getGlobalSettings().getMaxConcurrentDownloads()
                + " reached or schedule inactive");
    }

    /**
     * Compare-and-set on the download's terminal state: the first terminal
     * event of the current generation wins; later terminal events of the
     * same generation are logged no-ops (no reindex, no actions, no queue
     * advance, no status overwrite). A newer generation always wins.
     *
     * @return true when the caller is the first terminal handler
     */
    private boolean tryBeginTerminal(String downloadId, long generation) {
        synchronized (generationLock) {
            Long current = attemptGenerations.get(downloadId);
            if (current == null || current.longValue() != generation) {
                LOGGER.debug("Stale terminal event for download " + downloadId
                        + " (generation " + generation + ") ignored");
                return false;
            }
            Long terminated = terminalGenerations.get(downloadId);
            if (terminated != null && terminated.longValue() == generation) {
                LOGGER.debug("Duplicate terminal event for download " + downloadId
                        + " (generation " + generation + ") ignored");
                return false;
            }
            terminalGenerations.put(downloadId, generation);
            return true;
        }
    }

    /** Whether this generation has already reached any terminal outcome. */
    private boolean isTerminalAttempt(String downloadId, long generation) {
        synchronized (generationLock) {
            Long terminated = terminalGenerations.get(downloadId);
            return terminated != null && terminated.longValue() == generation;
        }
    }

    /**
     * Replaces a failed proxychains attempt with Curl exactly once for this
     * generation, preserving the same SOCKS proxy. Curl is deliberately
     * unavailable to torrent, magnet and Metalink inputs; the factory's
     * read-only eligibility check enforces that boundary before this method
     * consumes the failed generation.
     *
     * @return true when fallback was started, completed by another racing
     *         error callback, or terminally consumed after an unexpected
     *         preparation failure
     */
    private boolean maybeFallbackProxychainsToCurl(Download download, Throwable cause,
            long generation) {
        if (download == null || download.getType() != Download.Type.PROXYCHAINS
                || !isCurrentAttempt(download.getId(), generation)) {
            return false;
        }

        DownloadHandlerFactory factory = getHandlerFactory();
        if (!factory.canPrepareCurlProxyFallback(download)) {
            return false;
        }

        AtomicBoolean claimed = new AtomicBoolean(false);
        proxychainsCurlFallbackGenerations.compute(download.getId(), (id, previous) -> {
            if (previous == null || previous.longValue() != generation) {
                claimed.set(true);
                return generation;
            }
            return previous;
        });
        if (!claimed.get()) {
            // A start-future callback and a handler error event can report
            // the same proxychains failure. The winning callback owns the
            // handoff; the duplicate must not mark the replacement ERROR.
            return true;
        }

        if (!tryBeginTerminal(download.getId(), generation)) {
            proxychainsCurlFallbackGenerations.remove(download.getId(), generation);
            return false;
        }

        try {
            if (!factory.prepareCurlProxyFallback(download)) {
                String message = "Proxychains failed and the Curl fallback became unavailable: "
                        + messageOf(cause);
                download.setErrorMessage(message);
                downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                cleanupDownloadResources(download.getId(), generation);
                notifyDownloadError(download, message);
                startNextQueuedDownload();
                return true;
            }

            LOGGER.warn("Proxychains failed for " + download.getName()
                    + "; falling back to Curl with the same SOCKS proxy", cause);
            cleanupDownloadResources(download.getId(), generation);
            download.setGid(null);
            download.setErrorMessage(null);
            downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
            startDownloadInternal(download);
            return true;
        } catch (RuntimeException fallbackFailure) {
            String message = "Failed to prepare Curl fallback after proxychains error: "
                    + messageOf(fallbackFailure);
            download.setErrorMessage(message);
            downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
            cleanupDownloadResources(download.getId(), generation);
            notifyDownloadError(download, message);
            LOGGER.error(message, fallbackFailure);
            startNextQueuedDownload();
            return true;
        }
    }

    /** Schedule gate: consulted before starting a download (null = allow all). */
    private volatile java.util.function.Predicate<String> downloadGate;

    private volatile boolean torServiceAvailable = true;
    private volatile int torSocksPort = 9050;
    /** Ports that represented this managed service earlier in this process. */
    private final Set<Integer> managedTorPortAliases =
            new CopyOnWriteArraySet<>(Set.of(9050));
    private CompletableFuture<Void> torAvailabilityUpdate = CompletableFuture.completedFuture(null);

    @Override
    public synchronized CompletableFuture<Void> setTorServiceAvailable(boolean available, int socksPort) {
        if (socksPort < 1 || socksPort > 65535) {
            throw new IllegalArgumentException("Invalid Tor SOCKS port: " + socksPort);
        }
        rememberConfiguredTorPort();
        managedTorPortAliases.add(torSocksPort);
        managedTorPortAliases.add(9050);
        torSocksPort = socksPort;
        managedTorPortAliases.add(socksPort);
        synchronizeGlobalTorEndpoint(socksPort);
        // Close admission immediately, including recovery and queued work.
        torServiceAvailable = available;
        torAvailabilityUpdate = torAvailabilityUpdate.handleAsync((ignored, priorFailure) -> {
            for (Download download : getAllDownloads()) {
                if (available != torServiceAvailable || isShuttingDown.get()) {
                    break;
                }
                try {
                    migrateManagedTorEndpoint(download, socksPort);
                    if (available) {
                        if (download.getStatus() == Download.Status.PAUSED
                                && download.getPauseReason() == Download.PauseReason.TOR_SERVICE) {
                            resumeDownloadInternal(download);
                        }
                    } else if (usesManagedTor(download)
                            && (download.getStatus() == Download.Status.STARTING
                            || download.getStatus() == Download.Status.CONNECTING
                            || download.getStatus() == Download.Status.DOWNLOADING
                            || download.getStatus() == Download.Status.SEEDING
                            || download.getStatus() == Download.Status.QUEUED
                                && !download.isManualStartRequired())) {
                        pauseDownload(download, Download.PauseReason.TOR_SERVICE).join();
                    }
                } catch (RuntimeException failure) {
                    // A failing engine must not prevent other Tor records
                    // from being paused or resumed during this transition.
                    LOGGER.warn("Failed to update Tor service hold for " + download.getId(), failure);
                }
            }
            return null;
        }, executorManager.getGeneralExecutor());
        return torAvailabilityUpdate;
    }

    private boolean usesManagedTor(Download download) {
        String proxy = effectiveProxyAddress(download);
        if (!org.manager.download.handler.DownloadHandlerFactory.isSocksProxyAddress(proxy)) {
            return false;
        }
        try {
            URI endpoint = URI.create(proxy);
            return managedTorPortAliases.contains(endpoint.getPort())
                    && Set.of("127.0.0.1", "localhost", "[::1]", "::1")
                            .contains(endpoint.getHost());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }

    private void rememberConfiguredTorPort() {
        GlobalSettings settings = getGlobalSettings();
        if (!settings.getBooleanProperty("tor.enabled", false)) {
            return;
        }
        try {
            URI endpoint = URI.create(settings.getGlobalProxyAddress());
            if (org.manager.download.handler.DownloadHandlerFactory
                    .isSocksProxyAddress(endpoint.toString())
                    && Set.of("127.0.0.1", "localhost", "[::1]", "::1")
                            .contains(endpoint.getHost())
                    && endpoint.getPort() > 0) {
                managedTorPortAliases.add(endpoint.getPort());
            }
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // An invalid global address is handled by the normal settings path.
        }
    }

    private void synchronizeGlobalTorEndpoint(int socksPort) {
        GlobalSettings settings = getGlobalSettings();
        settings.setProperty(DownloadSettingsFactory.MANAGED_TOR_SOCKS_PORT,
                Integer.toString(socksPort));
        if (settings.getBooleanProperty("tor.enabled", false)) {
            settings.setGlobalProxyEnabled(true);
            settings.setGlobalProxyAddress("socks5h://127.0.0.1:" + socksPort);
        }
    }

    private void migrateManagedTorEndpoint(Download download, int socksPort) {
        if (download == null || !download.isUseProxy()) {
            return;
        }
        String address = download.getProxyAddress();
        try {
            URI endpoint = URI.create(address);
            boolean local = Set.of("127.0.0.1", "localhost", "[::1]", "::1")
                    .contains(endpoint.getHost());
            boolean managed = download.getType() == Download.Type.TOR
                    || managedTorPortAliases.contains(endpoint.getPort());
            if (!local || !managed
                    || !org.manager.download.handler.DownloadHandlerFactory
                            .isSocksProxyAddress(address)
                    || endpoint.getPort() == socksPort) {
                return;
            }
            boolean inherited = download.getSettings().isProxyInherited();
            download.getSettings().setProxyAddress(
                    "socks5h://127.0.0.1:" + socksPort);
            download.getSettings().setProxyInherited(inherited);
            LOGGER.info("Updated managed Tor route for {} to SOCKS port {}",
                    download.getId(), socksPort);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // Invalid proxy values remain the responsibility of route validation.
        }
    }

    private boolean holdForUnavailableTor(Download download) {
        if (torServiceAvailable || !usesManagedTor(download)) {
            return false;
        }
        boolean admitted;
        synchronized (admissionLock) {
            admitted = runningDownloadIds.contains(download.getId());
        }
        if (admitted) {
            pauseDownload(download, Download.PauseReason.TOR_SERVICE, true).join();
            return true;
        }
        download.setPauseReason(Download.PauseReason.TOR_SERVICE);
        downloadRepository.updateDownloadStatus(download, Download.Status.PAUSED);
        notifyDownloadPause(download);
        return true;
    }

    @Override
    public void setDownloadGate(java.util.function.Predicate<String> gate) {
        this.downloadGate = gate;
    }

    /**
     * Checks the schedule gate for a download.
     *
     * @return true when starting is allowed (or no gate is installed)
     */
    private boolean isStartAllowedBySchedule(Download download) {
        if (getGlobalSettings().getBooleanProperty("ui.offline", false)) {
            return false;
        }
        java.util.function.Predicate<String> gate = downloadGate;
        try {
            return gate == null || gate.test(download.getId());
        } catch (Exception e) {
            LOGGER.warn("Schedule gate check failed; refusing start", e);
            return false;
        }
    }

    /**
     * Returns the handler recorded for a running download — which may be a
     * retry wrapper whose state machine must see pause/resume/cancel —
     * falling back to the shared factory handler when no start is recorded.
     */
    private DownloadHandler handlerFor(Download download) {
        DownloadHandler active = activeHandlers.get(download.getId());
        return active != null ? active : getHandlerFactory().getHandler(download);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return pauseDownload(download, Download.PauseReason.USER);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download, Download.PauseReason reason) {
        return pauseDownload(download, reason, false);
    }

    private CompletableFuture<Void> pauseDownload(Download download, Download.PauseReason reason,
            boolean ensureEnginePaused) {
        final long revision;
        final long requestGeneration;
        synchronized (generationLock) {
            revision = pauseRevisions.merge(download.getId(), 1L, Long::sum);
            requestGeneration = download.getAttemptGeneration();
        }
        return CompletableFuture.runAsync(() -> {
            // Handlers mutate Download.status before returning, so the true
            // prior status must be captured BEFORE delegating
            Download.Status statusBefore = download.getStatus();
            if (download.getAttemptGeneration() != requestGeneration
                    || pauseRevisions.getOrDefault(download.getId(), 0L) != revision) {
                return;
            }
            if (isTerminalAttempt(download.getId(), download.getAttemptGeneration())) {
                return;
            }
            // Automatic controllers cannot take over a pre-existing user pause.
            if (!ensureEnginePaused && statusBefore == Download.Status.PAUSED
                    && reason != Download.PauseReason.USER) {
                return;
            }
            if (statusBefore == Download.Status.QUEUED) {
                if (pauseRevisions.getOrDefault(download.getId(), 0L) != revision) {
                    return;
                }
                downloadRepository.transitionDownloadStatus(download, statusBefore, Download.Status.PAUSED);
                download.setPauseReason(reason);
                releaseRunningSlot(download.getId());
                notifyDownloadPause(download);
                return;
            }
            try {
                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.pauseDownload(download).join();
                } else {
                    LOGGER.warn("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to cancel download: " + download.getName(), e);
                throw new CompletionException("Failed to pause download: " + download.getName(), e);
            }
            synchronized (generationLock) {
                if (isTerminalAttempt(download.getId(), download.getAttemptGeneration())
                        || download.getAttemptGeneration() != requestGeneration
                        || pauseRevisions.getOrDefault(download.getId(), 0L) != revision) {
                    return;
                }
                downloadRepository.transitionDownloadStatus(download, statusBefore, Download.Status.PAUSED);
                download.setPauseReason(reason);
            }
            releaseRunningSlot(download.getId());
            startNextQueuedDownload();
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> resumeDownloadInternal(download),
                executorManager.getGeneralExecutor());
    }

    private void resumeDownloadInternal(Download download) {
        resumeDownloadInternal(download, false);
    }

    private void resumeDownloadInternal(Download download, boolean fromQueue) {
        if (download == null) {
            throw new IllegalArgumentException("Download cannot be null");
        }
        try {
            download.validateSourcesForTransfer();
            new DownloadSettingsFactory(getGlobalSettings()).applyInheritedProxy(download.getSettings());
        } catch (IllegalArgumentException invalidSource) {
            rejectBeforeStart(download, invalidSource.getMessage());
            throw invalidSource;
        }
        if (holdForUnavailableTor(download)) {
            return;
        }
        // Resume is also an explicit user/startup action and therefore releases
        // a manual-start hold before schedule/concurrency admission is checked.
        Download.PauseReason pauseReason = download.getPauseReason();
        download.setManualStartRequired(false);
        download.setPauseReason(null);
        if (!isStartAllowedBySchedule(download)) {
            requeueAfterDeniedAdmission(download);
            return;
        }
        AdmissionResult admission = claimRunningSlot(download, fromQueue);
        if (admission == AdmissionResult.INELIGIBLE) { return; }
        if (admission == AdmissionResult.DUPLICATE) {
            LOGGER.warn("Ignoring duplicate resume for active download " + download.getId());
            return;
        }
        if (admission == AdmissionResult.FULL) {
            requeueAfterDeniedAdmission(download);
            return;
        }

        DownloadHandler active = activeHandlers.get(download.getId());
        String resumeRoute = effectiveProxyAddress(download);
        boolean wrappedResume = org.manager.download.handler.DownloadHandlerFactory
                .requiresProxychains(download, resumeRoute);
        boolean tlsResume = resumeRoute != null && resumeRoute.startsWith("https://");
        if (active != null && ((active.getSupportedType() == Download.Type.ARIA2
                && (wrappedResume || tlsResume || aria2RouteChanged(download)))
                || (active.getSupportedType() == Download.Type.PROXYCHAINS && !wrappedResume))) {
            // Retire the paused task before choosing an engine for the current
            // inherited route. Partial files keep their normal resume policy.
            restartActiveDownloadForProxyRoute(download, Download.Type.ARIA2,
                    "selected proxy route").join();
            startDownloadInternal(download);
            return;
        }
        if (active == null) {
            // Recovered process-backed handlers have no in-memory task. A
            // normal start rebuilds yt-dlp/HTTrack tasks and aria2 GID maps,
            // while retaining the already-claimed admission slot.
            if (download.getSettings() instanceof org.httrack.HttrackSettings httrack) {
                httrack.setRunMode(org.httrack.HttrackSettings.RunMode.CONTINUE);
            }
            download.setGid(null);
            startDownloadInternal(download, true);
            return;
        }

        Download.Status statusBefore = download.getStatus();
        final long generation;
        final long pauseRevision;
        synchronized (generationLock) {
            generation = nextAttemptGeneration(download);
            pauseRevision = pauseRevisions.getOrDefault(download.getId(), 0L);
            if (statusBefore == Download.Status.ERROR) {
                download.recordRetry();
            }
        }
        if (statusBefore == Download.Status.QUEUED) {
            downloadRepository.transitionDownloadStatus(download, statusBefore, Download.Status.PAUSED);
        }
        try {
            active.resumeDownload(download).join();
            synchronized (generationLock) {
                if (isCurrentAttempt(download.getId(), generation)
                        && !isTerminalAttempt(download.getId(), generation)
                        && pauseRevisions.getOrDefault(download.getId(), 0L) == pauseRevision) {
                    // The handler may set the model to DOWNLOADING and emit its
                    // resume event before returning. Preserve the true source
                    // state captured above so a prewarmed PAUSED query is also
                    // invalidated, rather than only reindexing DOWNLOADING.
                    downloadRepository.transitionDownloadStatus(
                            download, statusBefore, reportedRunningStatus(download));
                }
            }
        } catch (Exception e) {
            synchronized (generationLock) {
                if (isCurrentAttempt(download.getId(), generation)
                        && !isTerminalAttempt(download.getId(), generation)
                        && pauseRevisions.getOrDefault(download.getId(), 0L) == pauseRevision) {
                    releaseRunningSlot(download.getId());
                    downloadRepository.updateDownloadStatus(download, statusBefore);
                    download.setPauseReason(pauseReason);
                }
            }
            LOGGER.warn("Failed to resume download: " + download.getName(), e);
            throw new CompletionException("Failed to resume download: " + download.getName(), e);
        }
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        if (download == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Download cannot be null"));
        }
        return CompletableFuture.supplyAsync(() -> {
            DownloadHandler active = activeHandlers.get(download.getId());
            boolean socksRoute = org.manager.download.handler.DownloadHandlerFactory
                    .requiresProxychains(download, effectiveProxyAddress(download));

            if (active != null && active.getSupportedType() == Download.Type.ARIA2
                    && (socksRoute || aria2RouteChanged(download)
                            || java.util.Objects.toString(effectiveProxyAddress(download), "")
                            .startsWith("https://"))) {
                return restartActiveDownloadForProxyRoute(download, Download.Type.ARIA2,
                        "selected proxy route");
            }
            if (active != null && active.getSupportedType() == Download.Type.PROXYCHAINS
                    && !socksRoute) {
                return restartActiveDownloadForProxyRoute(download, Download.Type.ARIA2,
                        "native aria2 route");
            }

            // A paused/queued proxychains record has no active owner to drive
            // a handoff. Restore its native aria2 identity before its next start.
            if (active == null && download.getType() == Download.Type.PROXYCHAINS
                    && !socksRoute) {
                download.setType(Download.Type.ARIA2);
                download.setGid(null);
            }

            DownloadHandler handler = handlerFor(download);
            if (handler == null) {
                return CompletableFuture.<Void>failedFuture(new IllegalStateException(
                        "No handler found for download type: " + download.getType()));
            }
            CompletableFuture<Void> update = handler.changeSettings(download);
            return update != null ? update : CompletableFuture.<Void>completedFuture(null);
        }, executorManager.getGeneralExecutor()).thenCompose(update -> update)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        LOGGER.warn("Failed to change settings for download: "
                                + download.getName(), error);
                    }
                });
    }

    private String effectiveProxyAddress(Download download) {
        return new org.manager.download.DownloadSettingsFactory(getGlobalSettings())
                .effectiveProxy(download.getSettings());
    }

    @Override
    public CompletableFuture<Void> recheckData(Download download) {
        if (download == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Download cannot be null"));
        }
        String resultId = download.beginOperation(
                DownloadOperationResult.OperationType.RECHECK_DATA);
        CompletableFuture<Void> operation = CompletableFuture.runAsync(() -> {
            if (download.getType() != Download.Type.ARIA2) {
                throw new CompletionException(new UnsupportedOperationException(
                        "Data recheck is supported only for aria2 downloads"));
            }
            DownloadHandler handler = handlerFor(download);
            if (handler == null) {
                throw new CompletionException(new IllegalStateException(
                        "No handler found for download type: " + download.getType()));
            }
            try {
                handler.recheckData(download).join();
            } catch (Exception e) {
                throw new CompletionException("Failed to recheck data for "
                        + download.getName() + ": " + deepestFailureMessage(e), e);
            }
        }, executorManager.getGeneralExecutor());
        return operation.whenComplete((ignored, failure) -> {
            if (failure == null) {
                download.finishOperation(resultId,
                        DownloadOperationResult.Status.ACCEPTED,
                        "aria2 accepted the integrity recheck request; damaged data will be downloaded again");
            } else {
                download.finishOperation(resultId,
                        DownloadOperationResult.Status.FAILED,
                        deepestFailureMessage(failure));
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateWebsiteMirror(
            Download download, boolean purgeOldFiles) {
        if (download == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Download cannot be null"));
        }
        try {
            HttrackMirrorSupport.prepareUpdate(download, purgeOldFiles);
        } catch (IllegalStateException invalidMirror) {
            return CompletableFuture.failedFuture(invalidMirror);
        }
        return startDownload(download);
    }

    private static String deepestFailureMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName() : message;
    }

    @Override
    public CompletableFuture<Void> relocateDownload(Download download, Path destination) {
        if (download == null || destination == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Download and destination are required"));
        }
        if (!relocatingDownloadIds.add(download.getId())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("This download is already being moved"));
        }
        return CompletableFuture.runAsync(() -> relocateDownloadInternal(download, destination),
                executorManager.getGeneralExecutor())
                .whenComplete((ignored, failure) -> relocatingDownloadIds.remove(download.getId()));
    }

    private void relocateDownloadInternal(Download download, Path requestedDestination) {
        Path previousDestination = download.getDestination();
        if (previousDestination == null) {
            throw new CompletionException(new IllegalStateException(
                    "The download has no current destination"));
        }
        Path normalizedPrevious = previousDestination.toAbsolutePath().normalize();
        Path normalizedNew = requestedDestination.toAbsolutePath().normalize();
        if (normalizedPrevious.equals(normalizedNew)) {
            return;
        }

        Download.Status statusBefore = download.getStatus();
        if (!canRelocate(statusBefore)) {
            throw new CompletionException(new IllegalStateException(
                    "Destination cannot be changed while the download is " + statusBefore));
        }
        boolean resumeAfterMove = isResumableActiveStatus(statusBefore);
        DownloadHandler handler = handlerFor(download);

        if (resumeAfterMove) {
            // Keep the slot available for this same logical download while
            // pauseDownload releases it. Otherwise its own resume can lose a
            // race to an unrelated queued item.
            bulkPauseOperations.incrementAndGet();
        }
        try {
            DownloadRelocator.Relocation relocation = null;
            try {
                if (resumeAfterMove) {
                    pauseDownload(download).join();
                }
                relocation = DownloadRelocator.relocate(download, normalizedNew);
                if (!relocation.noOp()
                        && handler != null
                        && (resumeAfterMove || statusBefore == Download.Status.PAUSED)) {
                    handler.changeDestination(download, relocation.previousDestination(),
                            relocation.newDestination()).join();
                }
            } catch (Exception relocationFailure) {
                Throwable cause = unwrapCompletion(relocationFailure);
                boolean safeToResumeOldLocation = cause.getSuppressed().length == 0;
                if (relocation != null && !relocation.noOp()) {
                    try {
                        relocation.rollback(download);
                    } catch (Exception rollbackFailure) {
                        cause.addSuppressed(unwrapCompletion(rollbackFailure));
                        safeToResumeOldLocation = false;
                    }
                    if (handler != null
                            && (resumeAfterMove || statusBefore == Download.Status.PAUSED)) {
                        try {
                            handler.changeDestination(download, normalizedNew,
                                    normalizedPrevious).join();
                        } catch (Exception engineRollbackFailure) {
                            cause.addSuppressed(unwrapCompletion(engineRollbackFailure));
                            safeToResumeOldLocation = false;
                        }
                    }
                }
                if (resumeAfterMove && safeToResumeOldLocation
                        && download.getStatus() == Download.Status.PAUSED) {
                    try {
                        resumeDownload(download).join();
                    } catch (Exception resumeFailure) {
                        cause.addSuppressed(unwrapCompletion(resumeFailure));
                    }
                }
                throw new CompletionException("Could not move download to " + normalizedNew,
                        cause);
            }

            if (resumeAfterMove) {
                // A resume failure intentionally leaves the item paused in its
                // new, internally consistent location so the user can retry.
                resumeDownload(download).join();
            }
            LOGGER.info("Moved download " + download.getId() + " from "
                    + normalizedPrevious + " to " + normalizedNew);
        } finally {
            if (resumeAfterMove) {
                bulkPauseOperations.decrementAndGet();
                startNextQueuedDownload();
            }
        }
    }

    private static boolean canRelocate(Download.Status status) {
        return status != null && status != Download.Status.CANCELED;
    }

    private static Throwable unwrapCompletion(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public CompletableFuture<Void> cancelDownloads(List<Download> downloads, boolean deleteFiles) {
        List<Download> targets = List.copyOf(downloads).stream().distinct().toList();
        if (targets.isEmpty()) { return CompletableFuture.completedFuture(null); }
        // Register the entire selection synchronously, before any cancellation
        // can release a slot or another queue worker can claim a selected ID.
        synchronized (admissionLock) {
            bulkRemovalOperations.incrementAndGet();
            targets.forEach(download -> pendingRemovals.merge(download.getId(), 1, Integer::sum));
        }
        List<CompletableFuture<Void>> operations = new java.util.ArrayList<>();
        for (Download download : targets) {
            try { operations.add(cancelDownload(download, deleteFiles)); }
            catch (RuntimeException failure) { operations.add(CompletableFuture.failedFuture(failure)); }
        }
        CompletableFuture<Void> settled = CompletableFuture.allOf(operations.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> {
                    synchronized (admissionLock) {
                        targets.forEach(download -> pendingRemovals.computeIfPresent(download.getId(),
                                (id, count) -> count == 1 ? null : count - 1));
                        bulkRemovalOperations.decrementAndGet();
                    }
                    startNextQueuedDownload();
                });
        // Cancelling a caller's observation must not discard the cleanup stage.
        return settled.copy();
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            try {
                if (download == null) {
                    throw new IllegalArgumentException("Download cannot be null");
                }

                // A freshly-created or capacity-queued item has no process
                // for a handler to cancel. Several handlers only emit their
                // canceled callback when a GID/task exists, so terminate this
                // manager-owned state here and deliver the event exactly once.
                boolean neverStarted = download.getAttemptGeneration() == 0
                        && download.getGid() == null
                        && activeHandlers.get(download.getId()) == null
                        && (download.getStatus() == Download.Status.CREATED
                                || download.getStatus() == Download.Status.QUEUED);
                if (neverStarted) {
                    downloadRepository.updateDownloadStatus(download, Download.Status.CANCELED);
                    downloadRepository.removeDownload(download.getId());
                    deleteOwnedDescriptor(download);
                    notifyDownloadCanceled(download);
                    startNextQueuedDownload();
                    return;
                }

                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.cancelDownload(download, deleteFiles).join();
                    // Remove from our downloads map
                    downloadRepository.removeDownload(download.getId());
                    deleteOwnedDescriptor(download);
                    gidToIdMap.values().removeIf(id -> id.equals(download.getId()));
                    // runningDownloads is decremented by the handler's
                    // onDownloadCanceled notification (reusableListener);
                    // this idempotent call covers handlers that fail to
                    // notify on their cancel path. The canceled event itself
                    // is emitted by the handler — notifying here as well
                    // would deliver every cancel twice.
                    cleanupDownloadResources(download.getId(), download.getAttemptGeneration());
                    servicesScheduler.requestStateSnapshot();
                } else {
                    // A failed launch may have reached ERROR precisely because
                    // no handler exists. It no longer owns a process or slot,
                    // so removal is manager-owned just like a never-started
                    // queued item; otherwise the user can never clear it.
                    if (!runningDownloadIds.contains(download.getId())
                            && activeHandlers.get(download.getId()) == null) {
                        downloadRepository.updateDownloadStatus(download, Download.Status.CANCELED);
                        downloadRepository.removeDownload(download.getId());
                        deleteOwnedDescriptor(download);
                        gidToIdMap.values().removeIf(id -> id.equals(download.getId()));
                        notifyDownloadCanceled(download);
                    } else {
                        throw new IllegalStateException(
                                "No handler found for download type: " + download.getType());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to cancel download: " + download.getName(), e);
                throw new CompletionException("Failed to cancel download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    /** Removes only descriptor bytes copied beneath ODM's staging root. */
    private void deleteOwnedDescriptor(Download download) {
        URI uri = download.getUri();
        if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) {
            return;
        }
        Download.Protocol protocol = download.getProtocol();
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.METALINK) {
            return;
        }
        try {
            DescriptorStaging.deleteIfStaged(Paths.get(uri));
        } catch (RuntimeException invalidFileUri) {
            LOGGER.debug("Could not resolve staged descriptor URI for cleanup: " + uri,
                    invalidFileUri);
        }
    }

    @Override
    public List<Map<String, Object>> getDownloadPeers(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadPeers(download) : List.of();
    }

    @Override
    public List<Map<String, Object>> getDownloadFiles(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadFiles(download) : List.of();
    }

    public CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(URI source) {
        return previewDownloadFiles(source, null);
    }

    @Override
    public CompletableFuture<List<DownloadFileInfo>> previewDownloadFiles(
            URI source, String proxyAddress) {
        final DownloadUrlPolicy.ValidatedSource validated;
        try {
            validated = DownloadUrlPolicy.require(source);
        } catch (IllegalArgumentException invalidSource) {
            return CompletableFuture.failedFuture(invalidSource);
        }
        Download.Protocol protocol = validated.protocol();
        if (protocol != Download.Protocol.TORRENT
                && protocol != Download.Protocol.MAGNET
                && protocol != Download.Protocol.METALINK) {
            return CompletableFuture.completedFuture(List.of());
        }
        // Metadata inspection always belongs to the registered aria2 engine.
        // Resolving through getHandler(Download) would route a synthetic
        // probe through proxychains whenever the global proxy is SOCKS and
        // consequently hide the inspector even for local descriptors.
        DownloadHandler candidate = getHandlerFactory().getHandler(Download.Type.ARIA2);
        org.manager.download.handler.Aria2DownloadHandler handler =
                candidate instanceof org.manager.download.handler.Aria2DownloadHandler aria2
                        ? aria2 : null;
        if (handler == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("aria2 is unavailable for file metadata preview"));
        }
        return handler.previewDownloadFiles(validated.uri(), proxyAddress);
    }

    @Override
    public List<List<String>> getDownloadTrackers(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadTrackers(download) : List.of();
    }

    @Override
    public List<DownloadSourceFile> getDownloadSources(Download download) {
        var handler = aria2HandlerFor(download);
        return handler == null ? List.of() : handler.getDownloadSources(download);
    }

    @Override
    public CompletableFuture<Void> changeDownloadSource(Download download,
            DownloadSourceFile file, String remove, String add, boolean prefer) {
        var handler = aria2HandlerFor(download);
        if (handler == null) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("This engine cannot edit live mirrors"));
        }
        return handler.changeDownloadSource(download, file, remove, add, prefer)
                .whenComplete((unused, error) -> servicesScheduler.requestStateSnapshot());
    }

    /** Returns the aria2 handler if the given download is handled by it. */
    private org.manager.download.handler.Aria2DownloadHandler aria2HandlerFor(Download download) {
        if (download == null || download.getType() != Download.Type.ARIA2) {
            return null;
        }
        // Reading details must not re-route or mutate a persisted download.
        DownloadHandler handler = activeHandlers.get(download.getId());
        if (!(handler instanceof org.manager.download.handler.Aria2DownloadHandler)) {
            handler = getHandlerFactory().getHandler(Download.Type.ARIA2);
        }
        return handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2Handler
                ? aria2Handler
                : null;
    }

    /** Queued downloads ordered by queue position (then creation time). */
    private List<Download> queuedDownloadsByPosition() {
        // Status-indexed query touches only QUEUED ids instead of
        // materializing and sorting the full download list
        return downloadRepository.getDownloadsByStatus(Download.Status.QUEUED, 0, Integer.MAX_VALUE)
                .getDownloads()
                .stream()
                .sorted(java.util.Comparator.comparingInt(Download::getQueuePosition)
                        .thenComparing(Download::getCreatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** Next queue position for a newly queued download. */
    private int nextQueuePosition() {
        // Max over QUEUED positions only: queue positions of finished
        // history are irrelevant and scanning all downloads sorted the
        // entire repository on every queue operation
        return queuedDownloadsByPosition().stream()
                .mapToInt(Download::getQueuePosition)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public void moveDownloadUp(Download download) {
        moveInQueue(download, -1);
    }

    @Override
    public void moveDownloadDown(Download download) {
        moveInQueue(download, 1);
    }

    @Override
    public void moveDownloadToTop(Download download) {
        moveInQueue(download, Integer.MIN_VALUE);
    }

    @Override
    public void moveDownloadToBottom(Download download) {
        moveInQueue(download, Integer.MAX_VALUE);
    }

    /** Repositions a queued download and normalizes queue positions. */
    private void moveInQueue(Download download, int direction) {
        List<Download> queued = new java.util.ArrayList<>(queuedDownloadsByPosition());
        int index = queued.indexOf(download);
        if (index < 0) {
            return;
        }
        int target = switch (direction) {
            case -1 -> Math.max(0, index - 1);
            case 1 -> Math.min(queued.size() - 1, index + 1);
            case Integer.MIN_VALUE -> 0;
            case Integer.MAX_VALUE -> queued.size() - 1;
            default -> index;
        };
        if (target != index) {
            queued.remove(index);
            queued.add(target, download);
        }
        for (int i = 0; i < queued.size(); i++) {
            queued.get(i).setQueuePosition(i + 1);
        }
    }

    @Override
    public CompletableFuture<Void> reconsiderQueuedDownloads() {
        return CompletableFuture.runAsync(this::startNextQueuedDownload,
                executorManager.getGeneralExecutor());
    }

    private void startNextQueuedDownload() {
        if (isShuttingDown.get() || bulkPauseOperations.get() > 0 || bulkRemovalOperations.get() > 0) {
            return;
        }

        // Find the next queued download by queue position (reorderable)
        List<Download> queuedDownloads = queuedDownloadsByPosition();
        // Per-tick diagnostics only: this runs on every completion/queue event
        LOGGER.debug("Checking for queued downloads - Found: " + queuedDownloads.size()
                + ", Running: " + runningDownloads.get()
                + ", Max concurrent: " + getGlobalSettings().getMaxConcurrentDownloads());
        if (LOGGER.isDebugEnabled() && !queuedDownloads.isEmpty()) {
            for (Download d : queuedDownloads) {
                LOGGER.debug("Found queued download: " + d.getName()
                        + " (actual status: " + d.getStatus() + ", GID: " + d.getGid() + ")");
            }
        }

        // If there's a queued download, try to start it. Admission is the
        // atomic slot claim inside startDownloadInternal, so a concurrent
        // finisher or starter can never overshoot the limit.
        int available = Math.max(0, getGlobalSettings().getMaxConcurrentDownloads()
                - runningDownloads.get());
        List<Download> eligible = queuedDownloads.stream()
                .filter(download -> !download.isManualStartRequired())
                .filter(this::isStartAllowedBySchedule)
                .limit(available)
                .toList();
        if (!eligible.isEmpty()) {
            for (Download download : eligible) {

            // CRITICAL FIX: Don't start downloads that are already completed or in error
            // state
            if (download.getStatus() == Download.Status.COMPLETED) {
                LOGGER.error("Repository inconsistency: Download " + download.getName()
                        + " is COMPLETED but found in QUEUED status query - updating repository indices");
                // Fix the repository inconsistency by updating the status in the repository
                downloadRepository.updateDownloadStatus(download, Download.Status.COMPLETED);
                continue;
            }

            if (download.getStatus() == Download.Status.ERROR) {
                LOGGER.warn("Repository inconsistency: Download " + download.getName()
                        + " is ERROR but found in QUEUED status query - updating repository indices");
                // Fix the repository inconsistency by updating the status in the repository
                downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                continue;
            }

            // Check if this download is already running with a different GID
            // This helps prevent starting the same download multiple times
            if (download.getStatus() == Download.Status.DOWNLOADING && download.getGid() != null) {
                LOGGER.debug("Attempted to start download that's already DOWNLOADING: " + download.getName()
                        + " (GID: " + download.getGid() + ") - skipping to prevent duplicate start");
                continue;
            }

            LOGGER.info("Starting next queued download: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");
            if (activeHandlers.containsKey(download.getId())) {
                resumeDownloadInternal(download, true);
            } else {
                startDownloadInternal(download, false, true);
            }
            }
        } else {
            LOGGER.debug("No eligible queued downloads to start");
        }
    }

    @Override
    public Download getDownload(String id) {
        return downloadRepository.getDownload(id);
    }

    @Override
    public List<Download> getAllDownloads() {
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE);
        return page.getDownloads();
    }

    @Override
    public List<Download> getDownloads(int offset, int limit) {
        // Cursor slicing, NOT offset/limit page-number translation: division
        // returned whole pages and duplicated/dropped rows for non-aligned
        // offsets
        return downloadRepository.getAllDownloadsByOffset(offset, limit).getDownloads();
    }

    @Override
    public int getDownloadCount() {
        return downloadRepository.getTotalCount();
    }

    @Override
    public <K> Map<K, Integer> getDownloadCounts(java.util.function.Function<Download, K> classifier) {
        return downloadRepository.getDownloadCounts(classifier);
    }

    @Override
    public List<Download> getDownloadsByStatus(Download.Status status) {
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getDownloadsByStatus(status, 0,
                Integer.MAX_VALUE);
        return page.getDownloads();
    }

    @Override
    public List<Download> getDownloadsByStatus(Download.Status status, int offset, int limit) {
        return downloadRepository.getDownloadsByStatusByOffset(status, offset, limit)
                .getDownloads();
    }

    @Override
    public int getDownloadCountByStatus(Download.Status status) {
        return downloadRepository.getCountByStatus(status);
    }

    /**
     * Number of currently held concurrency slots. Each successful start
     * attempt claims exactly one slot; it is released exactly once when the
     * download reaches a terminal state. Exposed for lifecycle contract
     * tests.
     *
     * @return the current running-download count
     */
    int getRunningDownloadCount() {
        return runningDownloads.get();
    }

    @Override
    public CompletableFuture<Void> pauseAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            bulkPauseOperations.incrementAndGet();
            try {
                List<Download> activeDownloads = java.util.stream.Stream.of(
                                Download.Status.STARTING,
                                Download.Status.CONNECTING,
                                Download.Status.DOWNLOADING,
                                Download.Status.SEEDING)
                        .flatMap(status -> downloadRepository
                                .getDownloadsByStatus(status, 0, Integer.MAX_VALUE)
                                .getDownloads().stream())
                        .distinct()
                        .toList();

                if (activeDownloads.isEmpty()) {
                    return;
                }

                // Pause concurrently and join once: sequential joins made the
                // total wall time the SUM of every pause round trip, which could
                // exceed the shutdown phase budget with many active downloads.
                // Queue advancement remains suppressed until every pause has
                // released its admission slot, otherwise a queued download can
                // consume each newly freed slot during "Pause All".
                List<CompletableFuture<Void>> pauses = new java.util.ArrayList<>(activeDownloads.size());
                for (Download download : activeDownloads) {
                    pauses.add(pauseDownload(download).exceptionally(e -> {
                        LOGGER.warn("Failed to pause download: " + download.getName(), e);
                        return null;
                    }));
                }
                CompletableFuture.allOf(pauses.toArray(new CompletableFuture[0])).join();
            } finally {
                bulkPauseOperations.decrementAndGet();
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> resumeAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            // Get all paused downloads
            List<Download> pausedDownloads = getDownloadsByStatus(Download.Status.PAUSED);

            for (Download download : pausedDownloads) {
                try {
                    resumeDownloadInternal(download);
                } catch (Exception e) {
                    LOGGER.warn("Failed to resume download: " + download.getName(), e);
                }
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public void addDownloadListener(DownloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeDownloadListener(DownloadListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public GlobalSettings getGlobalSettings() {
        return container.getRequired(GlobalSettings.class);
    }

    @Override
    public void setGlobalSettings(GlobalSettings settings) {
        if (settings != null) {
            GlobalSettings currentSettings = getGlobalSettings();
            // Copy ALL settings (typed fields + generic property bag) so no
            // persisted value is silently dropped.
            currentSettings.copyFrom(settings);

            // Update internal state (for backward compatibility)
            defaultDownloadDirectory = currentSettings.getDefaultDownloadDirectory();

            // Tool managers cache path/availability: invalidate them so
            // changed tool paths apply without a restart
            ToolManagerFactory toolFactory = container.get(ToolManagerFactory.class);
            if (toolFactory != null) {
                toolFactory.invalidateToolCaches();
            }

            // Propagate runtime-relevant changes (proxy, speed limit) to the
            // engines so running downloads pick them up immediately
            applyGlobalSettingsToActiveDownloads();

            // Re-evaluate the tracker refresh schedule (interval may have
            // changed) and immediately apply a changed tracker list
            servicesScheduler.startTrackerRefreshJob();
            servicesScheduler.runTrackerRefresh();
            servicesScheduler.startStateSnapshotJob();
            cleanupManager.updateAutomaticCleanupConfig();
        }
    }

    @Override
    public void applyGlobalSettingsToActiveDownloads() {
        CompletableFuture.runAsync(this::applyGlobalSettingsToActiveDownloadsInternal,
                executorManager.getGeneralExecutor()).exceptionally(error -> {
                    LOGGER.warn(
                            "Failed to schedule global runtime settings update", error);
                    return null;
                });
    }

    private void applyGlobalSettingsToActiveDownloadsInternal() {
        synchronized (networkSettingsLock) {
            applyNetworkSettingsToActiveDownloads();
        }
    }

    private void applyNetworkSettingsToActiveDownloads() {
        try {
            boolean proxyEnabled = getGlobalSettings().isGlobalProxyEnabled();
            String proxyAddress = getGlobalSettings().getGlobalProxyAddress();
            boolean socksProxyEnabled = proxyEnabled
                    && org.manager.download.handler.DownloadHandlerFactory
                            .isSocksProxyAddress(proxyAddress);
            DownloadHandler handler = getHandlerFactory().getHandler(Download.Type.ARIA2);
            if (handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2
                    && aria2.needsPrivacyRestart()) {
                restartAria2ForNetworkPrivacy(aria2);
            }
            if (!socksProxyEnabled
                    && handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2Handler) {
                aria2Handler.applyGlobalRuntimeOptions();
            }
            // Snapshot ownership before applying changes. A successful route
            // handoff replaces activeHandlers; a weakly-consistent map
            // iterator could otherwise encounter that replacement and route
            // the same download twice in one settings pass.
            List<Map.Entry<String, DownloadHandler>> activeSnapshot = activeHandlers.entrySet()
                    .stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                    .toList();
            for (Map.Entry<String, DownloadHandler> entry : activeSnapshot) {
                Download download = downloadRepository.getDownload(entry.getKey());
                if (download == null || download.getSettings() == null) {
                    continue;
                }
                DownloadSettings settings = download.getSettings();
                boolean inherited = settings.isProxyInherited();
                boolean changed = false;
                if (proxyEnabled && proxyAddress != null && !proxyAddress.isBlank()) {
                    if (!DownloadNetworkCapabilities.supportsProxy(download, proxyAddress)) {
                        if (inherited) {
                            download.setErrorMessage("Selected global proxy is unsupported for this download");
                            pauseDownload(download).join();
                        }
                        continue;
                    }
                    if (inherited) {
                        changed = !settings.isUseProxy()
                                || !java.util.Objects.equals(settings.getProxyAddress(), proxyAddress);
                        settings.setUseProxy(true);
                        settings.setProxyAddress(proxyAddress);
                        settings.setProxyInherited(true);
                    }
                } else if (inherited) {
                    changed = settings.isUseProxy() || settings.getProxyAddress() != null;
                    settings.setUseProxy(false);
                    settings.setProxyAddress(null);
                    settings.setProxyInherited(true);
                }

                DownloadHandler active = entry.getValue();
                boolean wrappedRoute = org.manager.download.handler.DownloadHandlerFactory
                        .requiresProxychains(download, settings.isUseProxy() ? settings.getProxyAddress() : null);
                if (changed && download.getType() == Download.Type.ARIA2) {
                    restartActiveDownloadForProxyRoute(download, Download.Type.ARIA2,
                            "selected proxy route").join();
                    continue;
                }
                if (changed && inherited && download.getType() == Download.Type.PROXYCHAINS
                        && !wrappedRoute) {
                    restartActiveDownloadForProxyRoute(download, Download.Type.ARIA2,
                            "native aria2 route").join();
                    continue;
                }
                if (changed) {
                    active.changeSettings(download).exceptionallyCompose(error -> {
                        LOGGER.error("Failed to reroute active download "
                                + download.getId(), error);
                        // Privacy changes fail closed: stop a transfer whose
                        // process could not be restarted with the new route.
                        return pauseDownload(download);
                    }).join();
                }
            }
        } catch (Exception e) {
            // Handler factory may not be initialized (e.g. tests); not fatal
            LOGGER.warn("Failed to apply global settings to running downloads", e);
        }
    }

    /** Retires a daemon's discovery sockets while keeping records and partial files resumable. */
    private void restartAria2ForNetworkPrivacy(org.manager.download.handler.Aria2DownloadHandler handler)
            throws IOException {
        synchronized (admissionLock) { networkRestartInProgress = true; }
        try {
            List<Download> owned = activeHandlers.entrySet().stream()
                    .filter(entry -> entry.getValue().getSupportedType() == Download.Type.ARIA2)
                    .map(entry -> downloadRepository.getDownload(entry.getKey()))
                    .filter(java.util.Objects::nonNull).toList();
            Set<String> restart = owned.stream().filter(d -> isResumableActiveStatus(d.getStatus()))
                    .map(Download::getId).collect(java.util.stream.Collectors.toSet());
            for (Download download : owned) {
                downloadRepository.updateDownloadStatus(download, Download.Status.PAUSED);
                DownloadHandler previous = activeHandlers.remove(download.getId());
                if (previous instanceof RetryEventInterceptor retry) { retry.interceptReplaced(download.getId()); }
                gidToIdMap.entrySet().removeIf(entry -> download.getId().equals(entry.getValue()));
                download.setGid(null);
            }
            try {
                handler.restartForNetworkPrivacy();
            } catch (IOException | RuntimeException failure) {
                for (Download download : owned) {
                    releaseRunningSlot(download.getId());
                    download.setErrorMessage("Network policy change failed; download remains paused");
                    notifyDownloadPause(download);
                }
                throw failure;
            }
            for (Download download : owned) {
                if (restart.contains(download.getId())) {
                    startDownloadInternal(download, true);
                } else {
                    releaseRunningSlot(download.getId());
                }
            }
        } finally {
            synchronized (admissionLock) { networkRestartInProgress = false; }
            startNextQueuedDownload();
        }
    }

    private boolean aria2RouteChanged(Download download) {
        DownloadHandler handler = getHandlerFactory().getHandler(Download.Type.ARIA2);
        return handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2
                && aria2.needsRouteHandoff(download);
    }

    /**
     * Stops the previous task before changing its route. A failed stop is
     * surfaced and never followed by a start on the new route.
     */
    private CompletableFuture<Void> restartActiveDownloadForProxyRoute(
            Download download, Download.Type targetType,
            String routeDescription) {
        DownloadHandler sourceHandler = activeHandlers.get(download.getId());
        if (sourceHandler == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot switch " + download.getId() + " to " + routeDescription
                            + ": no active handler owns it"));
        }
        Download.Status statusBefore = download.getStatus();
        boolean restartAfterHandoff = isResumableActiveStatus(statusBefore);
        LOGGER.info("Switching download " + download.getId() + " from engine "
                + sourceHandler.getSupportedType() + " to " + routeDescription);

        // Mark the model paused before terminating a process-backed handler:
        // its worker interprets a non-zero exit as intentional only in a
        // paused/canceled state. The specialized handoff then stops/removes
        // the old task without publishing a terminal event.
        downloadRepository.updateDownloadStatus(download, Download.Status.PAUSED);
        return sourceHandler.stopForRouteChange(download)
                .thenRun(() -> {
                    activeHandlers.remove(download.getId(), sourceHandler);
                    gidToIdMap.entrySet().removeIf(
                            entry -> download.getId().equals(entry.getValue()));
                    download.setType(targetType);
                    download.setGid(null);
                    if (restartAfterHandoff) {
                        // The logical download already owns a concurrency
                        // slot. Start the replacement under that claim so a
                        // queued item cannot steal it between engines.
                        download.setManualStartRequired(false);
                        startDownloadInternal(download, true);
                    } else {
                        releaseRunningSlot(download.getId());
                    }
                })
                .thenRun(() -> LOGGER.info("Download " + download.getId()
                        + " handed off to " + routeDescription + " (engine "
                        + download.getType() + ")"))
                .whenComplete((ignored, error) -> {
                    if (error == null) {
                        return;
                    }
                    LOGGER.error("Failed to switch " + download.getId()
                            + " to " + routeDescription, error);
                    download.setErrorMessage("Failed to switch to " + routeDescription + ": "
                            + messageOf(error));
                    downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                    notifyDownloadError(download, download.getErrorMessage());
                });
    }

    @Override
    public CompletableFuture<Void> saveState() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Download> allDownloads = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();

                // Track currently active downloads for auto-resume
                Set<String> activeDownloads = allDownloads.stream()
                        .filter(d -> isResumableActiveStatus(d.getStatus()))
                        .map(Download::getId)
                        .collect(Collectors.toSet());

                persistState(allDownloads, activeDownloads);
            } catch (Exception e) {
                // Complete exceptionally so shutdown hooks and callers can
                // detect (and log) a failed persistence instead of assuming
                // success.
                throw new RuntimeException("Failed to save download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Shutdown persistence variant: the coordinated shutdown pauses active
     * downloads BEFORE the save phase runs, so recomputing "currently
     * downloading" at save time would persist none. The set captured
     * before the pause is the authoritative resumable set; live
     * DOWNLOADING ids are unioned in to cover a tracking failure.
     *
     * @param activeBeforeExit ids captured before the shutdown pause
     * @return a future completing when the state is persisted
     */
    CompletableFuture<Void> saveState(Set<String> activeBeforeExit) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Download> allDownloads = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();

                Set<String> activeDownloads = new java.util.HashSet<>(activeBeforeExit);
                allDownloads.stream()
                        .filter(d -> isResumableActiveStatus(d.getStatus()))
                        .map(Download::getId)
                        .forEach(activeDownloads::add);

                persistState(allDownloads, activeDownloads);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    private void persistState(List<Download> allDownloads, Set<String> activeDownloads) {
        List<Download> persistedDownloads = allDownloads;
        if (!getGlobalSettings().isRetainCompletedAndCanceledHistory()) {
            persistedDownloads = allDownloads.stream()
                    .filter(download -> download.getStatus() != Download.Status.COMPLETED
                            && download.getStatus() != Download.Status.CANCELED)
                    .toList();
        }
        Set<String> persistedIds = persistedDownloads.stream()
                .map(Download::getId).collect(java.util.stream.Collectors.toSet());
        Set<String> persistedActive = activeDownloads.stream()
                .filter(persistedIds::contains).collect(java.util.stream.Collectors.toSet());

        // Global settings are NOT part of the state:
        // they persist through GlobalSettings.save()/load() in
        // settings.json.
        stateStore.save(persistedDownloads, persistedActive);

        LOGGER.info("Saved " + persistedDownloads.size() + " downloads (including "
                + persistedActive.size() + " active) to state database");
    }

    @Override
    public CompletableFuture<Void> loadState() {
        return CompletableFuture.runAsync(() -> {
            try {
                SqliteDownloadStateStore.StateSnapshot snapshot = stateStore.load();

                Set<String> activeDownloadsIds = snapshot.activeIds();
                activeDownloadsBeforeExit.addAll(activeDownloadsIds);

                List<Download> savedDownloads = snapshot.downloads();
                if (!savedDownloads.isEmpty()) {
                    // Add loaded downloads to our map
                    for (Download download : savedDownloads) {
                        if (!getGlobalSettings().isRetainCompletedAndCanceledHistory()
                                && (download.getStatus() == Download.Status.COMPLETED
                                        || download.getStatus() == Download.Status.CANCELED)) {
                            continue;
                        }
                        // Make sure we have settings for this download
                        if (download.getSettings() == null) {
                            download.initSettings(getSettingsFactory());
                        }

                        download.interruptRunningCompletionActions(
                                "Interrupted when ODM previously stopped");
                        download.interruptRunningOperations(
                                "Interrupted when ODM previously stopped");

                        // Handler/process identifiers belong to the daemon or
                        // child-process instance that produced them. ODM does
                        // not reattach persisted jobs to a newly created
                        // handler, so exposing an old GID causes detail RPCs to
                        // query a different daemon session.
                        download.setGid(null);

                        downloadRepository.addDownload(download);

                        if (download.getChecksumAlgorithm() != null
                                && download.getExpectedChecksum() != null
                                && download.getStatus() != Download.Status.COMPLETED) {
                            try {
                                getActionManager().addAction(download,
                                        org.manager.download.action.ChecksumValidationAction.fromString(
                                                download.getChecksumAlgorithm() + ":"
                                                        + download.getExpectedChecksum()));
                            } catch (IllegalArgumentException invalidChecksum) {
                                LOGGER.warn("Ignoring invalid persisted checksum for "
                                        + download.getId(), invalidChecksum);
                            }
                        }

                        // Reset status for previously active downloads to allow proper auto-resume
                        if (activeDownloadsIds.contains(download.getId())
                                && isResumableActiveStatus(download.getStatus())) {
                            // Route through the repository: addDownload already
                            // indexed the download as DOWNLOADING
                            downloadRepository.updateDownloadStatus(download, Download.Status.PAUSED);
                        }
                    }
                }

                // ODM is the source of truth. Replaying aria2's input/session
                // file here creates daemon jobs without ODM id/GID mappings;
                // recovered downloads are restarted through admission below.

                // Auto-resume previously active downloads
                autoResumeActiveDownloads();

                if (!savedDownloads.isEmpty()) {
                    LOGGER.info("Loaded " + savedDownloads.size() + " downloads from saved state, "
                            + activeDownloadsIds.size() + " were active before exit");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to load download state", e);
                throw new CompletionException("Failed to load download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    private static boolean isResumableActiveStatus(Download download) {
        return download != null && isResumableActiveStatus(download.getStatus());
    }

    private static boolean isResumableActiveStatus(Download.Status status) {
        return status == Download.Status.STARTING
                || status == Download.Status.CONNECTING
                || status == Download.Status.DOWNLOADING
                || status == Download.Status.SEEDING;
    }

    /** Preserves a seeding report that raced a successful start/resume future. */
    private static Download.Status reportedRunningStatus(Download download) {
        return download.getStatus() == Download.Status.SEEDING
                ? Download.Status.SEEDING : Download.Status.DOWNLOADING;
    }

    /**
     * Builds the ObjectMapper used for state persistence. It handles
     * the Java time types used by {@link Download}, serializes {@link Path}s
     * as plain strings, and tolerates unknown properties so state written
     * by newer versions still loads. Package-private for testing.
     */
    static ObjectMapper createStateObjectMapper() {
        SimpleModule pathModule = new SimpleModule("PathAsString");
        pathModule.addSerializer(new StdSerializer<Path>(Path.class) {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        pathModule.addDeserializer(Path.class, new StdDeserializer<>(Path.class) {
            @Override
            public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String text = parser.getValueAsString();
                return text == null || text.isBlank() ? null : Paths.get(text);
            }
        });

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(pathModule);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    // After-completion action methods
    @Override
    public void addAfterCompletionAction(Download download, AfterCompletionAction action) {
        getActionManager().addAction(download, action);
    }

    @Override
    public void setGlobalAfterCompletionAction(AfterCompletionAction action) {
        getActionManager().setGlobalAction(action);
    }

    @Override
    public void setGlobalAfterCompletionActions(List<AfterCompletionAction> actions) {
        getActionManager().setGlobalActions(actions);
    }

    @Override
    public boolean removeAfterCompletionAction(Download download, AfterCompletionAction action) {
        return getActionManager().removeAction(download, action);
    }

    @Override
    public List<AfterCompletionAction> getAfterCompletionActions(Download download) {
        return getActionManager().getActions(download);
    }

    @Override
    public void addAfterCompletionActionListener(AfterCompletionActionListener listener) {
        getActionManager().addListener(listener);
    }

    @Override
    public void removeAfterCompletionActionListener(AfterCompletionActionListener listener) {
        getActionManager().removeListener(listener);
    }

    @Override
    public CompletableFuture<Void> executeAfterCompletionActions(Download download) {
        if (download.isArchiveOnlyCompletion()) {
            return CompletableFuture.completedFuture(null);
        }
        return getActionManager().executeActions(download);
    }

    @Override
    public CompletableFuture<Boolean> executeAfterCompletionAction(
            Download download, AfterCompletionAction action) {
        return getActionManager().executeAction(download, action);
    }

    /**
     * Starts global power actions only after every item is terminal, no
     * handler still owns a running slot, and all per-download actions have
     * settled. The action manager provides the once-per-active-cycle claim.
     */
    private void maybeExecuteGlobalCompletionActions(Download contextDownload) {
        if (isShuttingDown.get() || !runningDownloadIds.isEmpty()) {
            return;
        }
        List<Download> downloads = getAllDownloads();
        if (readyForGlobalCompletionActions(downloads)) {
            getActionManager().executeGlobalActions(contextDownload)
                    .exceptionally(error -> {
                        LOGGER.warn("Global completion actions failed", error);
                        return null;
                    });
        }
    }

    static boolean readyForGlobalCompletionActions(List<Download> downloads) {
        boolean allTerminal = downloads != null && !downloads.isEmpty() && downloads.stream()
                .allMatch(download -> switch (download.getStatus()) {
                    case COMPLETED, ERROR, CANCELED -> true;
                    default -> false;
                });
        boolean actionsSettled = downloads != null && downloads.stream()
                .noneMatch(Download::hasRunningCompletionActions);
        return allTerminal && actionsSettled;
    }

    @Override
    public CompletableFuture<Integer> pruneCompletedDownloads(Duration olderThan) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneCompletedDownloadsByAge(olderThan);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Integer> pruneCompletedDownloads(int keepCount) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneCompletedDownloadsByCount(keepCount);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Integer> pruneErrorDownloads(Duration olderThan) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneErrorDownloadsByAge(olderThan);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> performCleanup() {
        return cleanupManager.performFullCleanup();
    }

    @Override
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to) {
        return cleanupManager.getDownloadsByTimeRange(from, to);
    }

    @Override
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit) {
        return cleanupManager.getDownloadsByTimeRange(from, to, offset, limit);
    }

    @Override
    public void setAutomaticCleanup(boolean enabled, Duration cleanupInterval) {
        getGlobalSettings().setAutomaticCleanupEnabled(enabled);
        getGlobalSettings().setCleanupIntervalHours(cleanupInterval.toHours());
        cleanupManager.updateAutomaticCleanupConfig();
    }

    @Override
    public void setMaxDownloadsInMemory(int maxDownloads) {
        getGlobalSettings().setMaxDownloadsInMemory(maxDownloads);
    }

    @Override
    public Map<String, Object> getMemoryUsageStats() {
        Map<String, Object> stats = cleanupManager.getMemoryUsageStats();

        // Add repository stats
        Map<String, Object> repoStats = downloadRepository.getCacheStats();
        stats.put("repositoryCacheStats", repoStats);

        // Add breakdown
        Map<Download.Status, Integer> statusBreakdown = downloadRepository.getStatusBreakdown();
        stats.put("detailedStatusBreakdown", statusBreakdown);

        return stats;
    }

    // Notification helpers. All listener notifications are dispatched on the
    // dedicated single-threaded odm-events executor (see ExecutorServiceManager),
    // so consumers observe a serial, ordered event stream on one known thread.
    // Listeners that touch a UI toolkit MUST marshal to their UI thread.

    /**
     * Dispatches an event to all registered listeners on the odm-events
     * executor. Exceptions thrown by a listener are isolated and logged;
     * during shutdown, events are dropped silently.
     *
     * @param action the listener callback to invoke for each listener
     */
    void fireEvent(Consumer<DownloadListener> action) {
        for (DownloadListener listener : listeners) {
            try {
                eventExecutor.execute(() -> {
                    try {
                        action.accept(listener);
                    } catch (Exception e) {
                        LOGGER.warn("Error in download listener", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Executor already shut down (application exit) — drop event.
            }
        }
    }

    private void notifyDownloadStart(Download download) {
        fireEvent(l -> l.onDownloadStart(download));
    }

    private void notifyDownloadQueued(Download download) {
        fireEvent(l -> l.onDownloadQueued(download));
    }

    private void notifyDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
            float speed) {
        download.recordSpeedSample(downloadedBytes, speed);
        fireEvent(l -> l.onDownloadProgress(download, progress, downloadedBytes, totalBytes, speed));
    }

    private void notifyDownloadStatusChanged(Download download,
            Download.Status previousStatus, Download.Status currentStatus) {
        if (currentStatus == Download.Status.SEEDING || currentStatus == Download.Status.PAUSED) {
            servicesScheduler.requestStateSnapshot();
        }
        fireEvent(l -> l.onDownloadStatusChanged(download, previousStatus, currentStatus));
    }

    private void notifyDownloadPause(Download download) {
        servicesScheduler.requestStateSnapshot();
        fireEvent(l -> l.onDownloadPause(download));
    }

    private void notifyDownloadResume(Download download) {
        fireEvent(l -> l.onDownloadResume(download));
    }

    private void notifyDownloadComplete(Download download) {
        servicesScheduler.requestStateSnapshot();
        fireEvent(l -> l.onDownloadComplete(download));
    }

    private void notifyDownloadError(Download download, String errorMessage) {
        servicesScheduler.requestStateSnapshot();
        fireEvent(l -> l.onDownloadError(download, errorMessage));
    }

    private void notifyDownloadCanceled(Download download) {
        servicesScheduler.requestStateSnapshot();
        fireEvent(l -> l.onDownloadCanceled(download));
    }

    /**
     * Creates the XDG state directory and performs a one-time migration from
     * the pre-XDG-state location under XDG_DATA_HOME. The SQLite database and
     * its sidecars move as one group so a partial migration cannot silently
     * discard committed WAL records.
     */
    private static Path prepareStateDirectory() {
        Path stateDirectory = org.manager.util.OdmPaths.stateDirectory();
        Path legacyDirectory = org.manager.util.OdmPaths.dataDirectory();
        try {
            Files.createDirectories(stateDirectory);
            migrateLegacyStateFiles(legacyDirectory, stateDirectory);
            return stateDirectory;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Preserve the manager's lazy persistence-failure contract: a
            // malformed state path is surfaced by loadState()/saveState(),
            // not while unrelated services are being constructed.
            LOGGER.warn("ODM state path is not a directory: {}", stateDirectory);
            return stateDirectory;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to prepare ODM state directory "
                    + stateDirectory, e);
        }
    }

    static void migrateLegacyStateFiles(Path legacyDirectory, Path stateDirectory)
            throws IOException {
        if (legacyDirectory.toAbsolutePath().normalize()
                .equals(stateDirectory.toAbsolutePath().normalize())) {
            return;
        }

        List<String> stateFiles = List.of(
                STATE_DB_FILE,
                STATE_DB_FILE + "-wal",
                STATE_DB_FILE + "-shm",
                STATE_FILE,
                STATE_FILE + ".migrated");
        boolean legacyStateExists = stateFiles.stream()
                .anyMatch(name -> Files.exists(legacyDirectory.resolve(name)));
        if (!legacyStateExists) {
            return;
        }

        boolean targetStateExists = stateFiles.stream()
                .anyMatch(name -> Files.exists(stateDirectory.resolve(name)));
        if (targetStateExists) {
            LOGGER.warn("ODM state already exists in {}; leaving legacy state in {} untouched",
                    stateDirectory, legacyDirectory);
            return;
        }

        Files.createDirectories(stateDirectory);
        List<Path> movedTargets = new java.util.ArrayList<>();
        try {
            for (String name : stateFiles) {
                Path source = legacyDirectory.resolve(name);
                if (!Files.exists(source)) {
                    continue;
                }
                Path target = stateDirectory.resolve(name);
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(source, target);
                }
                movedTargets.add(target);
            }
        } catch (IOException migrationFailure) {
            for (int i = movedTargets.size() - 1; i >= 0; i--) {
                Path target = movedTargets.get(i);
                try {
                    Files.move(target, legacyDirectory.resolve(target.getFileName()));
                } catch (IOException rollbackFailure) {
                    migrationFailure.addSuppressed(rollbackFailure);
                }
            }
            throw migrationFailure;
        }
        LOGGER.info("Migrated ODM restart state from {} to {}", legacyDirectory, stateDirectory);
    }

    /**
     * Gets the handler factory from the dependency container.
     */
    private DownloadHandlerFactory getHandlerFactory() {
        return container.getRequired(DownloadHandlerFactory.class);
    }

    /**
     * Gets the settings factory from the dependency container.
     */
    private DownloadSettingsFactory getSettingsFactory() {
        return container.getRequired(DownloadSettingsFactory.class);
    }

    /**
     * Gets the action manager from the dependency container.
     */
    private AfterCompletionActionManager getActionManager() {
        return container.getRequired(AfterCompletionActionManager.class);
    }

    /**
     * Initializes all components with proper error handling.
     */
    private Void initializeComponents() {
        try {
            ToolManagerFactory toolFactory = container.getRequired(ToolManagerFactory.class);

            // Check if aria2 is available
            Aria2ToolManager aria2Manager = toolFactory.getAria2Manager();
            if (aria2Manager == null || !aria2Manager.isAvailable()) {
                throw new RuntimeException("aria2c is required for the download manager to function");
            }

            String aria2Version = aria2Manager.getVersion();
            if (aria2Version != null) {
                LOGGER.info("Using aria2 version: " + aria2Version);
            }

            // Initialize default download directory if not set
            if (defaultDownloadDirectory == null) {
                defaultDownloadDirectory = org.manager.util.OdmPaths.downloadDirectory();
            }

            // Create downloads directory if it doesn't exist
            if (!Files.exists(defaultDownloadDirectory)) {
                try {
                    Files.createDirectories(defaultDownloadDirectory);
                    LOGGER.info("Created download directory: " + defaultDownloadDirectory);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create download directory: " + defaultDownloadDirectory, e);
                }
            }

            // Initialize download handlers
            getHandlerFactory().initializeHandlers();

            // Load saved state
            loadState().join();

            // Initialize clipboard service if enabled
            clipboard.startIfEnabled();

            // Restore folder monitoring from persisted settings
            folderWatching.restoreFromSettings();

            // Start the periodic tracker refresh when configured
            servicesScheduler.startTrackerRefreshJob();

            // Periodic state snapshots: a crash or kill must not lose every
            // download added since launch (shutdown-only persistence did).
            servicesScheduler.startStateSnapshotJob();

            // Periodic proxy health check: without it, UNHEALTHY proxies are
            // never reset and BLOCKED never pruned once recorded
            proxyRotation.startHealthChecks();

            // Tool availability is already logged from the async
            // checkAllToolsAsync() in initializeDependencies; the old
            // synchronous getStatusReport() here spawned six sequential
            // --version subprocesses just to log, stalling startup
            // (versions stay available on demand via getStatusReport()).

            return null;
        } catch (Exception e) {
            throw new RuntimeException("Component initialization failed", e);
        }
    }

    /**
     * Internal method to create a download with error handling.
     */
    private Download createDownloadInternal(DownloadUrlPolicy.ValidatedSource source, Path destination) {
        try {
            Download download = Download.fromSource(source);

            Path finalDestination = destination != null ? destination
                    : getGlobalSettings().getDefaultDownloadDirectory();
            download.setDestination(finalDestination);

            // Initialize settings for this download type
            download.initSettings(getSettingsFactory());

            downloadRepository.addDownload(download);
            LOGGER.debug("Created download: " + download.getId());

            return download;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create download", e);
        }
    }

    /**
     * Internal method to create a torrent download with error handling.
     */
    private Download createTorrentDownloadInternal(Path torrentFile, Path destination) {
        try {
            if (!Files.exists(torrentFile)) {
                throw new RuntimeException("Torrent file not found: " + torrentFile);
            }

            if (!Files.isReadable(torrentFile)) {
                throw new RuntimeException("Cannot read torrent file: " + torrentFile);
            }

            Path finalDestination = destination != null ? destination
                    : getGlobalSettings().getDefaultDownloadDirectory();

            Download download = Download.fromTorrent(torrentFile, finalDestination);
            download.initSettings(getSettingsFactory());

            downloadRepository.addDownload(download);
            LOGGER.debug("Created torrent download: " + download.getId());

            return download;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create torrent download", e);
        }
    }

    /**
     * Internal method to queue a download with error handling.
     */
    private Void queueDownloadInternal(Download download, boolean manualStartRequired) {
        try {
            if (isShuttingDown.get()) {
                throw new RuntimeException("Cannot queue downloads while shutting down");
            }

            download.validateSourcesForTransfer();
            new DownloadSettingsFactory(getGlobalSettings()).applyInheritedProxy(download.getSettings());
            download.setManualStartRequired(manualStartRequired);
            download.setQueuePosition(nextQueuePosition());
            if (downloadRepository.getDownload(download.getId()) == null) {
                download.setStatus(Download.Status.QUEUED);
                downloadRepository.addDownload(download);
            } else {
                downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
            }
            // Queued, not started: consumers must be able to distinguish
            // (limit reached / outside schedule) from an actual start
            notifyDownloadQueued(download);

            // Start the download if the schedule allows downloading right
            // now (uGet-style ranges: outside the ranges downloads stay
            // QUEUED). Admission itself is the atomic slot claim inside
            // startDownloadInternal: a denied claim leaves this download
            // queued without a duplicate queued event.
            if (!manualStartRequired && isStartAllowedBySchedule(download)) {
                startDownloadInternal(download);
            } else if (manualStartRequired) {
                LOGGER.info("Download " + download.getName()
                        + " queued for manual start");
            } else {
                LOGGER.info("Download " + download.getName()
                        + " stays queued: outside the active download schedule");
            }

            LOGGER.debug("Queued download: " + download.getId());
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to queue download", e);
        }
    }

    private CompletableFuture<Void> saveStateForShutdown() {
        return saveState(Set.copyOf(activeDownloadsBeforeExit));
    }

    /**
     * Automatically resumes downloads that were active before application exit.
     */
    private void autoResumeActiveDownloads() {
        CompletableFuture.runAsync(() -> {
            try {
                // Gate on actual handler readiness instead of a fixed 2s
                // sleep: too short on slow disks meant failed resumes, too
                // long meant pointless startup delay. Bounded at 60s.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!org.manager.ApplicationContext.isComponentInitialized(
                        org.manager.StartupCoordinator.DOWNLOAD_HANDLER_FACTORY)
                        && System.nanoTime() < deadline
                        && !isShuttingDown.get()) {
                    Thread.sleep(100);
                }

                int resumedCount = 0;
                Set<String> resumableIds = new java.util.HashSet<>(activeDownloadsBeforeExit);
                if (torServiceAvailable) {
                    getDownloadsByStatus(Download.Status.PAUSED).stream()
                            .filter(d -> d.getPauseReason() == Download.PauseReason.TOR_SERVICE)
                            .map(Download::getId).forEach(resumableIds::add);
                }
                for (String downloadId : resumableIds) {
                    try {
                        Download download = getDownload(downloadId);
                        if (download != null && download.getStatus() == Download.Status.PAUSED) {
                            LOGGER.info("Auto-resuming download: " + download.getName());
                            resumeDownload(download).join();
                            resumedCount++;
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to auto-resume download: "
                                + downloadId, e);
                    }
                }

                // Clear the set after attempting resume
                activeDownloadsBeforeExit.clear();

                if (resumedCount > 0) {
                    LOGGER.info("Auto-resumed " + resumedCount + " downloads from previous session");
                }
            } catch (Exception e) {
                LOGGER.error("Failed to auto-resume downloads", e);
            } finally {
                // QUEUED rows represent prior explicit acceptance. They are
                // not part of active_before_exit, so without this startup
                // pump they could remain stranded forever.
                startNextQueuedDownload();
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Gets the clipboard service for URL monitoring and automatic download
     * detection.
     *
     * @return The clipboard service instance
     */
    @Override
    public ClipboardService getClipboardService() {
        return clipboard.service();
    }

    /**
     * Updates the clipboard monitoring settings.
     *
     * @param clipboardSettings The new clipboard settings
     */
    @Override
    public void updateClipboardSettings(ClipboardSettings clipboardSettings) {
        clipboard.updateSettings(clipboardSettings);
    }

    /**
     * Enables or disables clipboard monitoring.
     *
     * @param enabled true to enable clipboard monitoring, false to disable
     */
    @Override
    public void setClipboardMonitoringEnabled(boolean enabled) {
        clipboard.setMonitoringEnabled(enabled);
    }

    /**
     * Checks if clipboard monitoring is currently enabled.
     *
     * @return true if clipboard monitoring is enabled, false otherwise
     */
    @Override
    public boolean isClipboardMonitoringEnabled() {
        return clipboard.isMonitoringEnabled();
    }

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A future that completes with the list of created downloads
     */
    @Override
    public CompletableFuture<List<Download>> importFromClipboard() {
        return clipboard.importFromClipboard();
    }

    // Folder monitoring methods implementation
    @Override
    public FolderMonitorService getFolderMonitorService() {
        return folderWatching.folderMonitorService();
    }

    @Override
    public TorrentFolderMonitor getTorrentFolderMonitor() {
        return folderWatching.torrentFolderMonitor();
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath) {
        return folderWatching.startTorrentFolderMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        return folderWatching.startTorrentFolderMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath) {
        return folderWatching.stopTorrentFolderMonitoring(folderPath);
    }

    @Override
    public List<Path> getMonitoredTorrentFolders() {
        return folderWatching.getMonitoredTorrentFolders();
    }

    @Override
    public boolean isTorrentFolderMonitored(Path folderPath) {
        return folderWatching.isTorrentFolderMonitored(folderPath);
    }

    @Override
    public void setTorrentFolderMonitoringEnabled(boolean enabled) {
        folderWatching.setTorrentFolderMonitoringEnabled(enabled);
    }

    @Override
    public boolean isTorrentFolderMonitoringEnabled() {
        return folderWatching.isTorrentFolderMonitoringEnabled();
    }

    @Override
    public CompletableFuture<Void> startDefaultTorrentFolderMonitoring() {
        return folderWatching.startDefaultTorrentFolderMonitoring();
    }

    @Override
    public MetaLinkFolderMonitor getMetaLinkFolderMonitor() {
        return folderWatching.metaLinkFolderMonitor();
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath) {
        return folderWatching.startMetaLinkFolderMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        return folderWatching.startMetaLinkFolderMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopMetaLinkFolderMonitoring(Path folderPath) {
        return folderWatching.stopMetaLinkFolderMonitoring(folderPath);
    }

    @Override
    public boolean isMetaLinkFolderMonitored(Path folderPath) {
        return folderWatching.isMetaLinkFolderMonitored(folderPath);
    }

    @Override
    public void setMetaLinkFolderMonitoringEnabled(boolean enabled) {
        folderWatching.setMetaLinkFolderMonitoringEnabled(enabled);
    }

    @Override
    public boolean isMetaLinkFolderMonitoringEnabled() {
        return folderWatching.isMetaLinkFolderMonitoringEnabled();
    }

    @Override
    public CompletableFuture<Void> startDefaultMetaLinkFolderMonitoring() {
        return folderWatching.startDefaultMetaLinkFolderMonitoring();
    }

    /**
     * OPTIMIZATION: Reusable download listener that routes events based on
     * download ID. This eliminates the need to create a new listener instance
     * for each download, reducing memory allocation and GC pressure.
     */
    private class ReusableDownloadListener implements DownloadListener {

        @Override
        public void onDownloadStart(Download d) {
            getActionManager().markDownloadActivity();
            // Route to appropriate download manager listeners
            notifyDownloadStart(d);
        }

        @Override
        public void onDownloadProgress(Download d, float progress, long downloadedBytes, long totalBytes, float speed) {
            // Route to appropriate download manager listeners
            notifyDownloadProgress(d, progress, downloadedBytes, totalBytes, speed);
        }

        @Override
        public void onDownloadStatusChanged(Download d,
                Download.Status previousStatus, Download.Status currentStatus) {
            if (switch (currentStatus) {
                case STARTING, CONNECTING, DOWNLOADING, SEEDING -> true;
                default -> false;
            }) {
                getActionManager().markDownloadActivity();
            }
            // Handler state changes happen before this callback. Reindex from
            // the captured source status so cached status queries and sidebar
            // counts immediately agree with the model.
            downloadRepository.transitionDownloadStatus(
                    d, previousStatus, currentStatus);
            notifyDownloadStatusChanged(d, previousStatus, currentStatus);
        }

        @Override
        public void onDownloadPause(Download d) {
            // Handler-first status write: membership reindex (the manager's
            // pause path performs the exact transition afterwards)
            downloadRepository.updateDownloadStatus(d, Download.Status.PAUSED);
            releaseRunningSlot(d.getId());
            notifyDownloadPause(d);
        }

        @Override
        public void onDownloadResume(Download d) {
            getActionManager().markDownloadActivity();
            downloadRepository.updateDownloadStatus(d, Download.Status.DOWNLOADING);
            notifyDownloadResume(d);
        }

        @Override
        public void onDownloadComplete(Download d) {
            LOGGER.info("Download completed: " + d.getName());

            if (!tryBeginTerminal(d.getId(), d.getAttemptGeneration())) {
                return;
            }

            // Let a retry wrapper finalize (release proxy, cancel any
            // scheduled retry, settle its future) before terminal handling
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                interceptor.interceptComplete(d.getId());
            }

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.COMPLETED);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), d.getAttemptGeneration());

            notifyDownloadComplete(d);

            // Register per-download post-processing before advancing the
            // queue so the global-idle check cannot overtake it.
            CompletableFuture<Void> completionActions = executeAfterCompletionActions(d);
            startNextQueuedDownload();
            completionActions.whenComplete((ignored, error) ->
                    maybeExecuteGlobalCompletionActions(d));
        }

        @Override
        public void onDownloadError(Download d, String errorMessage) {
            long errorGeneration = d.getAttemptGeneration();

            // A retry wrapper owns intermediate retryable failures: it
            // schedules the retry and the manager defers ALL terminal
            // handling (no ERROR reindex, no slot release, no next-queued
            // start) so the running slot stays with the logical operation.
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                RetryEventInterceptor.RetryDecision decision = interceptor.interceptError(d.getId(), errorMessage);
                if (decision == RetryEventInterceptor.RetryDecision.RETRY_SCHEDULED) {
                    LOGGER.debug("Retry scheduled for download " + d.getName() + "; terminal handling deferred");
                    return;
                }
                if (decision == RetryEventInterceptor.RetryDecision.STALE) {
                    LOGGER.debug("Stale generation error for download " + d.getName()
                            + " dropped; terminal handling skipped");
                    return;
                }
            }

            if (!isCurrentAttempt(d.getId(), errorGeneration)) {
                LOGGER.debug("Stale generation error for download " + d.getName()
                        + " dropped after handler interception");
                return;
            }

            if (maybeFallbackProxychainsToCurl(d, new RuntimeException(errorMessage),
                    errorGeneration)) {
                return;
            }

            if (!tryBeginTerminal(d.getId(), errorGeneration)) {
                return;
            }

            LOGGER.error("Download {} failed: {}", d.getId(),
                    org.manager.tools.ProcessDiagnostics.sanitize(errorMessage));

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.ERROR);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), errorGeneration);

            notifyDownloadError(d, errorMessage);

            // Check for queued downloads to start
            startNextQueuedDownload();
            maybeExecuteGlobalCompletionActions(d);
        }

        @Override
        public void onDownloadCanceled(Download d) {
            LOGGER.info("Download canceled: " + d.getName());

            if (!tryBeginTerminal(d.getId(), d.getAttemptGeneration())) {
                return;
            }

            // Let a retry wrapper finalize before terminal handling
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                interceptor.interceptCanceled(d.getId());
            }

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.CANCELED);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), d.getAttemptGeneration());

            notifyDownloadCanceled(d);

            // Check for queued downloads to start
            startNextQueuedDownload();
            maybeExecuteGlobalCompletionActions(d);
        }
    }

    /**
     * OPTIMIZATION: Clean up resources associated with a completed/failed
     * download. This prevents memory leaks by removing entries from tracking
     * maps. Cleanup only takes effect for the CURRENT operation generation:
     * late terminal results of a superseded start are logged and dropped so
     * they never remove the replacement's handler or release its slot.
     *
     * @param downloadId The ID of the download to clean up
     * @param generation The operation generation the terminal result belongs to
     */
    private void cleanupDownloadResources(String downloadId, long generation) {
        try {
            if (!isCurrentAttempt(downloadId, generation)) {
                LOGGER.debug("Stale generation terminal result for download " + downloadId
                        + " dropped; current operation left untouched");
                return;
            }

            // Release the concurrency slot exactly once per start attempt.
            // Terminal events for downloads that never started (e.g. cancel
            // of a QUEUED download) and duplicate terminal notifications are
            // both naturally idempotent here.
            releaseRunningSlot(downloadId);

            // Remove the handler reference. The shared per-type handler keeps
            // the reusable listener attached: other running downloads of the
            // same type still deliver their events through it.
            activeHandlers.remove(downloadId);

            // Clean up GID mapping if exists
            gidToIdMap.entrySet().removeIf(entry -> downloadId.equals(entry.getValue()));

        } catch (Exception e) {
            LOGGER.warn("Error during resource cleanup for download: " + downloadId, e);
        }
    }
}
