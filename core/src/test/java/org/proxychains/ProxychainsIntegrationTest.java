package org.proxychains;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.ProxychainsDownloadHandler;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import utils.SocksHttpServer;

/**
 * Integration tests for Proxychains package. Tests the interaction between
 * ProxychainsClient, ProxychainsConfig, ProxychainsDownloadHandler, and
 * ProxychainsSettings using real tools and loopback SOCKS5 endpoints.
 *
 * Note: Some tests require proxychains to be installed on the system. Use
 * SKIP_PROXYCHAINS_INTEGRATION=true to skip tests requiring actual proxychains
 * installation.
 */
@DisplayName("Proxychains Integration Tests")
class ProxychainsIntegrationTest {

    @TempDir
    Path tempDir;

    @Mock
    private DownloadListener mockListener;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @Mock
    private DownloadSettingsFactory mockSettingsFactory;

    private ProxychainsConfig config;
    private ProxychainsDownloadHandler handler;
    private ProxychainsClient client;
    private ExecutorService executorService;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);
        executorService = Executors.newCachedThreadPool();

        // Setup mock global settings
        when(mockGlobalSettings.getProxychainsPath()).thenReturn("proxychains4");
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        // Create test configuration
        config = new ProxychainsConfig()
                .setChainType(ProxychainsConfig.ChainType.DYNAMIC)
                .setProxyDns(true)
                .setTcpReadTimeout(15000)
                .setTcpConnectTimeout(8000)
                .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9999);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (client != null) {
            client.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    @DisplayName("Should create and manage configuration files")
    void shouldCreateAndManageConfigurationFiles() throws IOException {
        // Create temporary config file
        Path configFile = config.createTempConfig();

        assertNotNull(configFile);
        assertTrue(Files.exists(configFile));

        // Verify config content
        String content = Files.readString(configFile);
        assertTrue(content.contains("dynamic_chain"));
        assertTrue(content.contains("proxy_dns"));
        assertTrue(content.contains("tcp_read_time_out 15000"));
        assertTrue(content.contains("tcp_connect_time_out 8000"));
        assertTrue(content.contains("socks5 127.0.0.1 9999"));

        // Load config back from file
        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        assertEquals(config.getChainType(), loadedConfig.getChainType());
        assertEquals(config.isProxyDns(), loadedConfig.isProxyDns());
        assertEquals(config.getTcpReadTimeout(), loadedConfig.getTcpReadTimeout());
        assertEquals(config.getTcpConnectTimeout(), loadedConfig.getTcpConnectTimeout());
        assertEquals(1, loadedConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = loadedConfig.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy.getType());
        assertEquals("127.0.0.1", proxy.getHost());
        assertEquals(9999, proxy.getPort());
    }

    @Test
    @DisplayName("Should integrate settings with download handler")
    void shouldIntegrateSettingsWithDownloadHandler() {
        ProxychainsSettings settings = new ProxychainsSettings()
                .setConfigFile("/etc/proxychains4.conf")
                .setQuiet(true)
                .setProgram("aria2c")
                .setForceV4(true)
                .setRandomChain(2)
                .setTorMode(true);

        // Verify settings can be converted to map for use in download options
        Map<String, String> optionsMap = settings.toMap();

        assertEquals("/etc/proxychains4.conf", optionsMap.get("proxychains.config"));
        assertEquals("true", optionsMap.get("proxychains.quiet"));
        assertEquals("aria2c", optionsMap.get("proxychains.program"));
        assertEquals("true", optionsMap.get("proxychains.4"));
        assertEquals("2", optionsMap.get("proxychains.random-chain"));
        assertEquals("true", optionsMap.get("proxychains.tor"));

        // Test settings copying
        ProxychainsSettings copiedSettings = (ProxychainsSettings) settings.copy();
        assertNotSame(settings, copiedSettings);
        assertEquals(settings.getConfigFile(), copiedSettings.getConfigFile());
        assertEquals(settings.isQuiet(), copiedSettings.isQuiet());
        assertEquals(settings.getProgram(), copiedSettings.getProgram());
        assertEquals(settings.isForceV4(), copiedSettings.isForceV4());
        assertEquals(settings.getRandomChain(), copiedSettings.getRandomChain());
        assertEquals(settings.isTorMode(), copiedSettings.isTorMode());
    }

    @ParameterizedTest
    @DisplayName("Should handle multiple proxy configurations")
    @CsvSource({
        "DYNAMIC, socks5://127.0.0.1:9050",
        "STRICT, http://proxy.example.com:8080",
        "RANDOM, socks4://localhost:1080"
    })
    void shouldHandleMultipleProxyConfigurations(ProxychainsConfig.ChainType chainType, String proxyString)
            throws IOException {
        ProxychainsConfig testConfig = new ProxychainsConfig()
                .setChainType(chainType)
                .parseProxyString(proxyString);

        Path configFile = testConfig.createTempConfig();

        assertTrue(Files.exists(configFile));
        String content = Files.readString(configFile);

        assertTrue(content.contains(chainType.getValue()));
        assertTrue(content.contains("[ProxyList]"));

        // Verify the proxy was added correctly
        assertEquals(1, testConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = testConfig.getProxyList().get(0);
        assertNotNull(proxy);

        // Parse expected values from proxy string
        String[] parts = proxyString.split("://");
        String expectedType = parts[0];
        String[] hostPort = parts[1].split(":");
        String expectedHost = hostPort[0];
        int expectedPort = Integer.parseInt(hostPort[1]);

        assertEquals(expectedType, proxy.getType().getValue());
        assertEquals(expectedHost, proxy.getHost());
        assertEquals(expectedPort, proxy.getPort());
    }

    @Test
    @DisplayName("Should create download handler with proper initialization")
    void shouldCreateDownloadHandlerWithProperInitialization() {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());

        assertNotNull(handler);
        assertEquals(0, handler.getActiveDownloadCount());
        assertFalse(handler.isActive("non-existent-id"));

        // Test listener management
        handler.addDownloadListener(mockListener);
        handler.removeDownloadListener(mockListener);

        // Test options management
        Map<String, String> options = new HashMap<>();
        options.put("test.key", "test.value");

        handler.setDownloadOptions("test-id", options);
        Map<String, String> retrievedOptions = handler.getDownloadOptions("test-id");

        assertEquals(options, retrievedOptions);
    }

    @Test
    @DisplayName("Should handle download lifecycle with proper state management")
    void shouldHandleDownloadLifecycleWithProperStateManagement() throws Exception {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
        handler.addDownloadListener(mockListener);

        assertDoesNotThrow(() -> handler.initialize().join());

        // Hold the response open so completion cannot race the lifecycle assertions.
        try (var proxy = new utils.SocksHttpServer(false, "lifecycle payload", new CountDownLatch(1))) {
            URI testUri = URI.create("http://lifecycle.odm.invalid/test-file.bin");
            Download download = new Download(testUri);
            download.setDestination(tempDir);
            download.setName("test-file.bin");
            download.setType(Download.Type.PROXYCHAINS);
            download.setProxyAddress("socks5h://127.0.0.1:" + proxy.port());
            download.setUseProxy(true);

            // Set up download options
            Map<String, String> options = new HashMap<>();
            options.put("aria2.max-connection-per-server", "1");
            options.put("aria2.split", "1");
            handler.setDownloadOptions(download.getId(), options);

            handler.startDownload(download).get(10, TimeUnit.SECONDS);

            // Verify download type was set
            assertEquals(Download.Type.PROXYCHAINS, download.getType());

            Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .until(() -> proxy.hosts.contains("lifecycle.odm.invalid"));
            assertTrue(handler.isActive(download.getId()));
            assertEquals(1, handler.getActiveDownloadCount());
            assertEquals(Download.Status.DOWNLOADING, download.getStatus());

            // Test pause functionality. (No doNothing() stubbing here: a mock's
            // default behavior is already a no-op, and mid-test stubbing races
            // the handler thread's asynchronous listener notifications, which
            // Mockito reports as "Unfinished stubbing".)
            handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
            assertEquals(Download.Status.PAUSED, download.getStatus());

            // Test resume functionality
            handler.resumeDownload(download).get(10, TimeUnit.SECONDS);
            assertEquals(Download.Status.DOWNLOADING, download.getStatus());
            assertTrue(handler.isActive(download.getId()));

            // Test cancellation
            handler.cancelDownload(download, false).get(10, TimeUnit.SECONDS);
            assertEquals(Download.Status.CANCELED, download.getStatus());
            assertEquals(0, handler.getActiveDownloadCount());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PROXYCHAINS_AVAILABLE", matches = "true")
    @DisplayName("Should work with actual proxychains installation")
    @Timeout(30)
    void shouldWorkWithActualProxychainsInstallation() throws Exception {
        assertTrue(ProxychainsClient.isProxychainsAvailable(), "Proxychains should be available for this test");
        try (var proxy = new SocksHttpServer(false, "native proxychains payload")) {
            Path configFile = new ProxychainsConfig()
                    .setChainType(ProxychainsConfig.ChainType.STRICT)
                    .setProxyDns(true)
                    .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", proxy.port())
                    .createTempConfig();
            client = new ProxychainsClient("proxychains4", configFile.toString());
            try {
                Download download = new Download(URI.create("http://native.odm.invalid/test-download.bin"));
                download.setDestination(tempDir);
                download.setName("test-download.bin");
                download.setConnections(1);
                client.startDownload(download, mockListener, Map.of()).get(10, TimeUnit.SECONDS);

                verify(mockListener, timeout(10000)).onDownloadComplete(download);
                assertEquals(Download.Status.COMPLETED, download.getStatus());
                assertEquals("native proxychains payload", Files.readString(tempDir.resolve(download.getName())));
                assertEquals(Set.of("native.odm.invalid"), Set.copyOf(proxy.hosts));
                assertEquals(Set.of("/test-download.bin"), Set.copyOf(proxy.requestTargets));
                assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
                verify(mockListener, never()).onDownloadError(eq(download), anyString());
            } finally {
                client.shutdown();
                Files.deleteIfExists(configFile);
            }
        }
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "SKIP_PROXYCHAINS_INTEGRATION", matches = "true")
    @DisplayName("Should handle concurrent downloads with different configurations")
    @Timeout(20)
    void shouldHandleConcurrentDownloadsWithDifferentConfigurations() throws Exception {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
        handler.addDownloadListener(mockListener);
        handler.initialize().get(10, TimeUnit.SECONDS);
        CountDownLatch responseReady = new CountDownLatch(1);
        try (var first = new SocksHttpServer(false, "payload 0", responseReady);
                var second = new SocksHttpServer(false, "payload 1", responseReady);
                var third = new SocksHttpServer(false, "payload 2", responseReady)) {
            var proxies = java.util.List.of(first, second, third);
            var downloads = new ArrayList<Download>();
            try {
                for (int i = 0; i < proxies.size(); i++) {
                    Download download = new Download(URI.create("http://route" + i + ".odm.invalid/file" + i + ".bin"));
                    download.setDestination(tempDir);
                    download.setName("file" + i + ".bin");
                    download.setType(Download.Type.PROXYCHAINS);
                    download.setProxyAddress("socks5h://127.0.0.1:" + proxies.get(i).port());
                    download.setUseProxy(true);
                    download.setConnections(1);
                    downloads.add(download);
                    handler.startDownload(download).get(10, TimeUnit.SECONDS);
                }
                await().atMost(10, TimeUnit.SECONDS)
                        .until(() -> proxies.stream().allMatch(proxy -> !proxy.requestTargets.isEmpty()));
                assertEquals(proxies.size(), handler.getActiveDownloadCount());
                responseReady.countDown();

                for (int i = 0; i < downloads.size(); i++) {
                    Download download = downloads.get(i);
                    verify(mockListener, timeout(10000)).onDownloadComplete(download);
                    assertEquals(Download.Status.COMPLETED, download.getStatus());
                    assertEquals("payload " + i, Files.readString(tempDir.resolve(download.getName())));
                    assertEquals(Set.of(download.getUri().getHost()), Set.copyOf(proxies.get(i).hosts));
                    assertEquals(Set.of(download.getUri().getPath()), Set.copyOf(proxies.get(i).requestTargets));
                    assertTrue(proxies.get(i).failures.isEmpty(), proxies.get(i).failures.toString());
                    verify(mockListener, never()).onDownloadError(eq(download), anyString());
                    verify(mockListener, never()).onDownloadCanceled(download);
                }
                await().atMost(5, TimeUnit.SECONDS).until(() -> handler.getActiveDownloadCount() == 0);
            } finally {
                responseReady.countDown();
                handler.shutdown().get(10, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Should handle configuration parsing edge cases")
    void shouldHandleConfigurationParsingEdgeCases() throws IOException {
        // Test config with various edge cases
        String edgeCaseConfig = "# Comment at the beginning\n"
                + "\n"
                + "random_chain  # Comment after directive\n"
                + "proxy_dns\n"
                + "\n"
                + "# Invalid lines that should be ignored\n"
                + "invalid_directive_without_value\n"
                + "tcp_read_time_out invalid_number\n"
                + "\n"
                + "[ProxyList]\n"
                + "# Valid proxy\n"
                + "socks5 127.0.0.1 9050\n"
                + "# Invalid proxy lines that should be ignored\n"
                + "invalid_proxy_line\n"
                + "http incomplete_proxy\n"
                + "socks4 invalid.proxy invalid_port\n"
                + "# Another valid proxy\n"
                + "http proxy.example.com 8080 user pass\n";

        Path configFile = tempDir.resolve("edge-case-config.conf");
        Files.writeString(configFile, edgeCaseConfig);

        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        // Should have parsed valid directives
        assertEquals(ProxychainsConfig.ChainType.RANDOM, loadedConfig.getChainType());
        assertTrue(loadedConfig.isProxyDns());

        // Should use default timeout values due to invalid timeout line
        assertEquals(15000, loadedConfig.getTcpReadTimeout());
        assertEquals(8000, loadedConfig.getTcpConnectTimeout());

        // Should have parsed only valid proxy entries
        assertEquals(2, loadedConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy1 = loadedConfig.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy1.getType());
        assertEquals("127.0.0.1", proxy1.getHost());
        assertEquals(9050, proxy1.getPort());
        assertFalse(proxy1.hasAuthentication());

        ProxychainsConfig.ProxyEntry proxy2 = loadedConfig.getProxyList().get(1);
        assertEquals(ProxychainsConfig.ProxyType.HTTP, proxy2.getType());
        assertEquals("proxy.example.com", proxy2.getHost());
        assertEquals(8080, proxy2.getPort());
        assertTrue(proxy2.hasAuthentication());
        assertEquals("user", proxy2.getUsername());
        assertEquals("pass", proxy2.getPassword());
    }

    @Test
    @DisplayName("Should integrate with download settings inheritance")
    void shouldIntegrateWithDownloadSettingsInheritance() {
        ProxychainsSettings settings = new ProxychainsSettings();

        // Set base class properties
        settings.setConnections(8);
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:9050");

        // Set proxychains-specific properties
        settings.setConfigFile("/etc/proxychains4.conf");
        settings.setProgram("curl");
        settings.setTorMode(true);

        // Verify inheritance works
        assertTrue(settings instanceof org.manager.download.DownloadSettings);

        // Test map conversion includes both base and derived properties
        Map<String, String> map = settings.toMap();
        assertNotNull(map);

        // Should contain proxychains-specific settings
        assertEquals("/etc/proxychains4.conf", map.get("proxychains.config"));
        assertEquals("curl", map.get("proxychains.program"));
        assertEquals("true", map.get("proxychains.tor"));

        // Test copying preserves all properties
        ProxychainsSettings copied = (ProxychainsSettings) settings.copy();

        assertEquals(settings.getConnections(), copied.getConnections());
        assertEquals(settings.isUseProxy(), copied.isUseProxy());
        assertEquals(settings.getProxyAddress(), copied.getProxyAddress());
        assertEquals(settings.getConfigFile(), copied.getConfigFile());
        assertEquals(settings.getProgram(), copied.getProgram());
        assertEquals(settings.isTorMode(), copied.isTorMode());
    }

    @Test
    @DisplayName("Should handle cleanup and resource management")
    void shouldHandleCleanupAndResourceManagement() throws Exception {
        // Create resources that need cleanup
        Path tempConfigFile = config.createTempConfig();
        assertTrue(Files.exists(tempConfigFile));

        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
        handler.addDownloadListener(mockListener);

        assertDoesNotThrow(() -> handler.initialize().join());

        try (var proxy = new utils.SocksHttpServer(false, "cleanup payload", new CountDownLatch(1))) {
            // Keep all three real transfers open until shutdown.
            for (int i = 0; i < 3; i++) {
                Download download = new Download(URI.create("http://cleanup.odm.invalid/file" + i + ".zip"));
                download.setDestination(tempDir);
                download.setType(Download.Type.PROXYCHAINS);
                download.setProxyAddress("socks5h://127.0.0.1:" + proxy.port());
                download.setUseProxy(true);
                handler.startDownload(download).get(10, TimeUnit.SECONDS);
            }

            Awaitility.await()
                    .atMost(10, TimeUnit.SECONDS)
                    .until(() -> proxy.hosts.size() == 3);
            assertEquals(3, handler.getActiveDownloadCount());

            // Shutdown should clean up all resources
            handler.shutdown().get(10, TimeUnit.SECONDS);

            // Verify cleanup
            assertEquals(0, handler.getActiveDownloadCount());
        }

        // Verify we can create new handler after shutdown
        ExecutorService newExecutor = Executors.newCachedThreadPool();
        ProxychainsDownloadHandler newHandler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory,
                newExecutor, ApplicationContext.getToolManagerFactory());
        assertEquals(0, newHandler.getActiveDownloadCount());
        newHandler.shutdown().join();
        newExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Should validate proxychains availability checking")
    void shouldValidateProxychainsAvailabilityChecking() {
        // Test static availability check
        boolean isAvailable = ProxychainsClient.isProxychainsAvailable();

        // The result depends on whether proxychains is installed
        // We just verify the method doesn't throw exceptions
        assertNotNull(isAvailable);

        // Test handler availability check
        boolean handlerAvailable = ProxychainsDownloadHandler.isProxychainsAvailable();
        assertEquals(isAvailable, handlerAvailable);
    }
}
