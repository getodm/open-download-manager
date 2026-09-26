package org.manager.tools;

import org.manager.GlobalSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class providing common functionality for all tool managers.
 * Implements standard tool discovery, version checking, and validation logic.
 */
public abstract class AbstractToolManager implements ToolManager {

    protected static final Logger LOGGER = LoggerFactory.getLogger(AbstractToolManager.class);

    protected final GlobalSettings settings;
    protected final ExecutorService executor;
    protected volatile String cachedToolPath;
    protected volatile String cachedVersion;
    protected volatile Map<String, Boolean> cachedFeatures;
    protected volatile Boolean isAvailable;

    /**
     * Creates a new AbstractToolManager.
     *
     * @param settings The global settings
     * @param executor The executor service for async operations
     */
    protected AbstractToolManager(GlobalSettings settings, ExecutorService executor) {
        this.settings = settings;
        this.executor = executor;
    }

    @Override
    public boolean isAvailable() {
        if (isAvailable == null) {
            isAvailable = checkToolAvailability();
        }
        return isAvailable;
    }

    @Override
    public CompletableFuture<Boolean> checkAvailabilityAsync() {
        return CompletableFuture.supplyAsync(this::isAvailable, executor);
    }

    @Override
    public String getToolPath() {
        if (cachedToolPath == null) {
            cachedToolPath = discoverToolPath();
        }
        return cachedToolPath;
    }

    @Override
    public void setToolPath(String path) {
        this.cachedToolPath = path;
        this.isAvailable = null; // Reset availability check
        updateSettingsPath(path);
    }

    @Override
    public String discoverToolPath() {
        // Try configured path first
        String configuredPath = getConfiguredPath();
        if (isValidExecutable(configuredPath)) {
            return configuredPath;
        }

        // AppImage mounts change at every launch. Resolve its tools from the
        // current launcher directory without persisting a stale mount path.
        String bundledDirectory = System.getProperty("odm.tools.directory");
        if (bundledDirectory != null && !bundledDirectory.isBlank()) {
            Path bundledTool = Path.of(bundledDirectory).resolve(getExecutableName());
            if (Files.isRegularFile(bundledTool) && Files.isExecutable(bundledTool)) {
                return bundledTool.toAbsolutePath().toString();
            }
        }

        // Try embedded binary
        if (supportsEmbeddedBinary()) {
            String embeddedPath = getEmbeddedBinaryPath();
            if (isValidExecutable(embeddedPath)) {
                return embeddedPath;
            }
        }

        // Search in common locations
        return findInCommonLocations();
    }

    @Override
    public String getVersion() {
        if (cachedVersion == null) {
            cachedVersion = detectVersion();
        }
        return cachedVersion;
    }

    @Override
    public boolean checkMinimumVersion(String minVersion) {
        String currentVersion = getVersion();
        if (currentVersion == null || minVersion == null) {
            return false;
        }
        return compareVersions(currentVersion, minVersion) >= 0;
    }

    @Override
    public Map<String, Boolean> getSupportedFeatures() {
        if (cachedFeatures == null) {
            cachedFeatures = detectFeatures();
        }
        return new HashMap<>(cachedFeatures);
    }

    @Override
    public boolean checkFeatureSupport(String... requiredFeatures) {
        Map<String, Boolean> features = getSupportedFeatures();
        return Arrays.stream(requiredFeatures)
                .allMatch(feature -> features.getOrDefault(feature, false));
    }

    @Override
    public Map<String, Object> getStatusReport() {
        Map<String, Object> report = new HashMap<>();
        report.put("toolId", getToolId());
        report.put("executableName", getExecutableName());
        report.put("available", isAvailable());
        report.put("path", getToolPath());
        report.put("version", getVersion());
        report.put("features", getSupportedFeatures());
        return report;
    }

    @Override
    public boolean initializeEmbeddedBinary(Path tempBinaryDir) {
        if (!supportsEmbeddedBinary()) {
            return false;
        }

        try {
            String resourcePath = getEmbeddedBinaryResourcePath();
            if (resourcePath == null) {
                return false;
            }

            InputStream binaryStream = getClass().getResourceAsStream(resourcePath);
            if (binaryStream == null) {
                LOGGER.warn("Embedded binary not found in resources: " + resourcePath);
                return false;
            }

            Path targetPath = tempBinaryDir.resolve(getExecutableName());
            Files.copy(binaryStream, targetPath, StandardCopyOption.REPLACE_EXISTING);

            // Make executable
            File targetFile = targetPath.toFile();
            targetFile.setExecutable(true);

            LOGGER.info("Initialized embedded binary for " + getToolId() + " at: " + targetPath);
            this.cachedToolPath = targetPath.toString();
            return true;

        } catch (IOException e) {
            LOGGER.warn("Failed to initialize embedded binary for " + getToolId(), e);
            return false;
        }
    }

    @Override
    public void validateTool() throws ToolException {
        if (!isAvailable()) {
            throw new ToolException("Tool " + getToolId() + " is not available");
        }

        String path = getToolPath();
        if (path == null) {
            throw new ToolException("No valid path found for " + getToolId());
        }

        if (!executeBasicCheck(path)) {
            throw new ToolException("Tool " + getToolId() + " failed basic functionality check");
        }
    }

    @Override
    public void cleanup() {
        // Reset cached values
        this.cachedToolPath = null;
        this.cachedVersion = null;
        this.cachedFeatures = null;
        this.isAvailable = null;
    }

    // Protected methods for subclasses to implement
    /**
     * Gets the configured path from settings.
     */
    protected abstract String getConfiguredPath();

    /**
     * Updates the path in settings.
     */
    protected abstract void updateSettingsPath(String path);

    /**
     * Gets the common search locations for this tool.
     */
    protected abstract List<String> getCommonLocations();

    /**
     * Gets the version check command for this tool.
     */
    protected abstract String[] getVersionCommand(String toolPath);

    /**
     * Parses version from tool output.
     */
    protected abstract String parseVersion(String output);

    /**
     * Detects supported features for this tool.
     */
    protected abstract Map<String, Boolean> detectFeatures();

    /**
     * Checks if this tool supports embedded binaries.
     */
    protected abstract boolean supportsEmbeddedBinary();

    /**
     * Gets the resource path for embedded binary.
     */
    protected abstract String getEmbeddedBinaryResourcePath();

    /**
     * Executes a basic functionality check.
     */
    protected abstract boolean executeBasicCheck(String toolPath);

    // Protected utility methods
    protected boolean checkToolAvailability() {
        String toolPath = discoverToolPath();
        return toolPath != null && isValidExecutable(toolPath);
    }

    protected boolean isValidExecutable(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }

        // Check if it's in PATH
        if (isInPath(path)) {
            return true;
        }

        // Check if it's a valid file
        File file = new File(path);
        return file.exists() && file.canExecute();
    }

    protected boolean isInPath(String executable) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[] { "which", executable });
            // Bounded wait: a wedged `which` (stale NFS mount) must not hang
            // tool discovery at startup
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    protected String findInCommonLocations() {
        List<String> locations = getCommonLocations();
        for (String location : locations) {
            if (isValidExecutable(location)) {
                LOGGER.info("Found " + getToolId() + " at: " + location);
                return location;
            }
        }
        return null;
    }

    protected String detectVersion() {
        String toolPath = getToolPath();
        if (toolPath == null) {
            return null;
        }

        try {
            String[] command = getVersionCommand(toolPath);
            Process process = Runtime.getRuntime().exec(command);

            if (process.waitFor(10, TimeUnit.SECONDS)) {
                String output = readProcessOutput(process);
                return parseVersion(output);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to detect version for " + getToolId(), e);
        }
        return null;
    }

    protected String readProcessOutput(Process process) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // Drain both streams CONCURRENTLY: reading stdout to EOF before
            // touching stderr deadlocks a tool that writes more than the
            // pipe capacity (64K) to stderr first
            Thread stderrDrain = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(output);
                } catch (IOException ignored) {
                    // process died; draining is done
                }
            }, "tool-stderr-drain");
            stderrDrain.setDaemon(true);
            stderrDrain.start();

            process.getInputStream().transferTo(output);
            try {
                stderrDrain.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return output.toString();
        }
    }

    protected int compareVersions(String version1, String version2) {
        String[] parts1 = version1.replaceAll("[^0-9.]", "").split("\\.");
        String[] parts2 = version2.replaceAll("[^0-9.]", "").split("\\.");

        int maxLength = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLength; i++) {
            int v1 = i < parts1.length ? Integer.parseInt(parts1[i]) : 0;
            int v2 = i < parts2.length ? Integer.parseInt(parts2[i]) : 0;

            if (v1 != v2) {
                return Integer.compare(v1, v2);
            }
        }
        return 0;
    }

    protected String getEmbeddedBinaryPath() {
        // Override in subclasses that support embedded binaries
        return null;
    }
}
