package org.proxychains;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.ProxychainsDownloadHandler;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import utils.SocksHttpServer;

/**
 * Complete workflows through real proxychains4 and aria2 processes, using local
 * SOCKS5 and HTTP servers. The .invalid host requires the configured proxy to
 * resolve the destination, without Tor, public DNS, or internet access.
 */
@DisplayName("Proxychains E2E Tests")
@DisabledIfEnvironmentVariable(named = "SKIP_E2E_TESTS", matches = "true")
@Timeout(30)
class ProxychainsE2ETest {

    private static final String TEST_HOST = "e2e.odm.invalid";
    private static final String TEST_FILE_CONTENT = "This is a test file for download testing.\n".repeat(100);

    @TempDir
    Path tempDir;

    private MockWebServer mockWebServer;
    private SocksHttpServer proxy;
    private ProxychainsDownloadHandler handler;
    private ExecutorService executorService;
    private DownloadListener listener;

    @BeforeEach
    void setUp() throws Exception {
        executorService = Executors.newCachedThreadPool();
        GlobalSettings settings = new GlobalSettings()
                .setProxychainsPath("proxychains4")
                .setDefaultDownloadDirectory(tempDir);
        ApplicationContext.initialize();
        handler = new ProxychainsDownloadHandler(settings, new DownloadSettingsFactory(settings),
                executorService, ApplicationContext.getToolManagerFactory());
        listener = mock(DownloadListener.class);
        handler.addDownloadListener(listener);
        handler.initialize().get(10, TimeUnit.SECONDS);

        mockWebServer = new MockWebServer();
        mockWebServer.start();
        proxy = new SocksHttpServer(new InetSocketAddress("127.0.0.1", mockWebServer.getPort()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().get(10, TimeUnit.SECONDS);
        }
        if (proxy != null) {
            proxy.close();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("Should complete full download workflow with successful response")
    void shouldCompleteFullDownloadWorkflowWithSuccessfulResponse() throws Exception {
        // Send multiple aria2 read buffers over several progress intervals.
        String content = TEST_FILE_CONTENT.repeat(256);
        mockWebServer.enqueue(new MockResponse().setBody(content)
                .throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS));
        Download download = newDownload("test-file.txt", proxy);

        handler.startDownload(download).get(10, TimeUnit.SECONDS);
        assertCompleted(download, content);
        assertRoutedRequest("/test-file.txt");

        var progress = ArgumentCaptor.forClass(Float.class);
        verify(listener, atLeastOnce()).onDownloadProgress(eq(download), progress.capture(),
                anyLong(), anyLong(), anyFloat());
        assertTrue(progress.getAllValues().stream().allMatch(value -> value >= 0 && value <= 100));
        assertTrue(progress.getAllValues().stream().anyMatch(value -> value > 0 && value < 100),
                "The real transfer should report intermediate progress");
        var events = inOrder(listener);
        events.verify(listener).onDownloadStart(download);
        events.verify(listener).onDownloadComplete(download);
    }

    @Test
    @DisplayName("Should handle download with pause and resume")
    void shouldHandleDownloadWithPauseAndResume() throws Exception {
        CountDownLatch responseReady = new CountDownLatch(1);
        try (var heldProxy = new SocksHttpServer(false, TEST_FILE_CONTENT, responseReady)) {
            Download download = newDownload("resumed.txt", heldProxy);
            handler.startDownload(download).get(10, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> heldProxy.requestTargets.size() == 1);

            handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
            assertEquals(Download.Status.PAUSED, download.getStatus());
            assertFalse(handler.isActive(download.getId()));
            verify(listener).onDownloadPause(download);

            handler.resumeDownload(download).get(10, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> heldProxy.requestTargets.size() == 2);
            assertEquals(Download.Status.DOWNLOADING, download.getStatus());
            assertTrue(handler.isActive(download.getId()));
            responseReady.countDown();

            assertCompleted(download, TEST_FILE_CONTENT);
            var events = inOrder(listener);
            events.verify(listener).onDownloadStart(download);
            events.verify(listener).onDownloadPause(download);
            events.verify(listener).onDownloadResume(download);
            events.verify(listener).onDownloadComplete(download);
        } finally {
            responseReady.countDown();
        }
    }

    @Test
    @DisplayName("Should handle download cancellation")
    void shouldHandleDownloadCancellation() throws Exception {
        try (var heldProxy = new SocksHttpServer(false, TEST_FILE_CONTENT, new CountDownLatch(1))) {
            Download download = newDownload("canceled.txt", heldProxy);
            handler.startDownload(download).get(10, TimeUnit.SECONDS);
            await().atMost(10, TimeUnit.SECONDS).until(() -> !heldProxy.requestTargets.isEmpty());

            handler.cancelDownload(download, true).get(10, TimeUnit.SECONDS);

            assertEquals(Download.Status.CANCELED, download.getStatus());
            verify(listener).onDownloadStart(download);
            verify(listener).onDownloadCanceled(download);
            verify(listener, never()).onDownloadComplete(download);
            verify(listener, never()).onDownloadError(eq(download), anyString());
            assertFalse(handler.isActive(download.getId()));
            assertEquals(0, handler.getActiveDownloadCount());
            assertFalse(Files.exists(tempDir.resolve(download.getName())));
        }
    }

    @Test
    @DisplayName("Should handle multiple concurrent downloads")
    void shouldHandleMultipleConcurrentDownloads() throws Exception {
        Map<String, String> contents = Map.of(
                "/file0.txt", "Content for file 0\n".repeat(50),
                "/file1.txt", "Content for file 1\n".repeat(50),
                "/file2.txt", "Content for file 2\n".repeat(50));
        Set<String> requests = ConcurrentHashMap.newKeySet();
        CountDownLatch allRequested = new CountDownLatch(contents.size());
        CountDownLatch responseReady = new CountDownLatch(1);
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String body = contents.get(request.getPath());
                if (body == null) {
                    return new MockResponse().setResponseCode(404);
                }
                if (requests.add(request.getPath())) {
                    allRequested.countDown();
                }
                responseReady.await();
                return new MockResponse().setBody(body);
            }
        });
        var downloads = new ArrayList<Download>();
        try {
            for (String path : contents.keySet()) {
                Download download = newDownload(path.substring(1), proxy);
                downloads.add(download);
                handler.startDownload(download).get(10, TimeUnit.SECONDS);
            }
            assertTrue(allRequested.await(10, TimeUnit.SECONDS), "All distinct requests must reach the server");
            assertEquals(contents.size(), handler.getActiveDownloadCount());
            for (Download download : downloads) {
                assertEquals(Download.Status.DOWNLOADING, download.getStatus());
                verify(listener).onDownloadStart(download);
            }
            responseReady.countDown();

            for (Download download : downloads) {
                assertCompleted(download, contents.get(download.getUri().getPath()));
            }
            assertEquals(contents.keySet(), Set.copyOf(proxy.requestTargets));
            assertEquals(Set.of(TEST_HOST), Set.copyOf(proxy.hosts));
            assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
        } finally {
            responseReady.countDown();
        }
    }

    @ParameterizedTest
    @DisplayName("Should handle different HTTP response codes")
    @ValueSource(ints = {404, 500, 503, 403})
    void shouldHandleDifferentHttpResponseCodes(int responseCode) throws Exception {
        mockWebServer.enqueue(new MockResponse().setResponseCode(responseCode).setBody("Error response"));
        Download download = newDownload("error-file.txt", proxy);

        handler.startDownload(download).get(10, TimeUnit.SECONDS);

        String error = assertFailed(download);
        String expectedDiagnostic = responseCode == 404 ? "Resource not found" : Integer.toString(responseCode);
        assertTrue(error.contains(expectedDiagnostic), error);
        assertRoutedRequest("/error-file.txt");
    }

    @Test
    @DisplayName("Should create and use custom proxy configuration")
    void shouldCreateAndUseCustomProxyConfiguration() throws Exception {
        try (var authenticatedProxy = new SocksHttpServer(true, TEST_FILE_CONTENT)) {
            ProxychainsConfig customConfig = new ProxychainsConfig()
                    .setChainType(ProxychainsConfig.ChainType.STRICT)
                    .setProxyDns(true)
                    .setTcpReadTimeout(5000)
                    .setTcpConnectTimeout(3000)
                    .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1",
                            authenticatedProxy.port(), "user", "pass");
            Path configFile = customConfig.createTempConfig();
            String configContent = Files.readString(configFile);
            assertTrue(configContent.contains("strict_chain"));
            assertTrue(configContent.contains("proxy_dns"));
            assertTrue(configContent.contains("tcp_read_time_out 5000"));
            assertTrue(configContent.contains("tcp_connect_time_out 3000"));
            assertTrue(configContent.contains("socks5 127.0.0.1 " + authenticatedProxy.port() + " user pass"));

            ProxychainsClient client = new ProxychainsClient("proxychains4", configFile.toString());
            try {
                Download download = newDownload("config-test.txt", authenticatedProxy);
                // Exercise the supplied config file rather than generating one from the download.
                download.setUseProxy(false);
                client.startDownload(download, listener, Map.of()).get(10, TimeUnit.SECONDS);

                assertCompleted(download, TEST_FILE_CONTENT);
                assertEquals(Set.of(TEST_HOST), Set.copyOf(authenticatedProxy.hosts));
                assertEquals(Set.of("user:pass"), Set.copyOf(authenticatedProxy.credentials));
                assertEquals(Set.of("/config-test.txt"), Set.copyOf(authenticatedProxy.requestTargets));
                assertTrue(authenticatedProxy.failures.isEmpty(), authenticatedProxy.failures.toString());
            } finally {
                client.shutdown();
                Files.deleteIfExists(configFile);
            }
        }
    }

    @Test
    @DisplayName("Should handle network timeouts gracefully")
    void shouldHandleNetworkTimeoutsGracefully() throws Exception {
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        Download download = newDownload("timeout-test.txt", proxy);
        handler.setDownloadOptions(download.getId(), Map.of("aria2.timeout", "2"));

        handler.startDownload(download).get(10, TimeUnit.SECONDS);

        String error = assertFailed(download);
        assertTrue(error.contains("errorCode=2 Timeout"), error);
        assertRoutedRequest("/timeout-test.txt");
    }

    @Test
    @DisplayName("Should validate integration with ProxychainsSettings")
    void shouldValidateIntegrationWithProxychainsSettings() throws Exception {
        ProxychainsSettings settings = new ProxychainsSettings()
                .setProgram("aria2c")
                .setQuiet(true)
                .setForceV4(true)
                .setRandomChain(0)
                .setTorMode(false)
                .setStrictChain(false);
        Map<String, String> options = settings.toMap();
        assertEquals("aria2c", options.get("proxychains.program"));
        assertEquals("true", options.get("proxychains.quiet"));
        assertEquals("true", options.get("proxychains.4"));
        assertFalse(options.containsKey("proxychains.random-chain"));
        assertFalse(options.containsKey("proxychains.tor"));
        assertFalse(options.containsKey("proxychains.strict"));

        mockWebServer.enqueue(new MockResponse().setBody("Settings test content"));
        Download download = newDownload("settings-test.txt", proxy);
        download.setSettings(settings);
        download.setProxyAddress("socks5h://127.0.0.1:" + proxy.port());
        download.setUseProxy(true);
        download.setConnections(1);
        handler.setDownloadOptions(download.getId(), options);
        handler.startDownload(download).get(10, TimeUnit.SECONDS);

        assertCompleted(download, "Settings test content");
        assertRoutedRequest("/settings-test.txt");
    }

    @Test
    @DisplayName("Should download binary content through a local network endpoint")
    void shouldWorkWithLocalNetworkEndpoint() throws Exception {
        byte[] content = new byte[1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }
        mockWebServer.enqueue(new MockResponse().setBody(new Buffer().write(content)));
        Download download = newDownload("binary-file.bin", proxy);

        handler.startDownload(download).get(10, TimeUnit.SECONDS);

        awaitTerminal(download);
        assertEquals(Download.Status.COMPLETED, download.getStatus(), download.getErrorMessage());
        verify(listener, timeout(1000)).onDownloadComplete(download);
        verify(listener).onDownloadStart(download);
        verify(listener, never()).onDownloadError(eq(download), anyString());
        assertArrayEquals(content, Files.readAllBytes(tempDir.resolve(download.getName())));
        assertRoutedRequest("/binary-file.bin");
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.getActiveDownloadCount() == 0);
    }

    private Download newDownload(String fileName, SocksHttpServer route) {
        Download download = new Download(URI.create("http://" + TEST_HOST + "/" + fileName));
        download.setDestination(tempDir);
        download.setName(fileName);
        download.setType(Download.Type.PROXYCHAINS);
        download.setProxyAddress("socks5h://127.0.0.1:" + route.port());
        download.setUseProxy(true);
        download.setConnections(1);
        download.getSettings().setMaxRetries(1);
        download.getSettings().setRetryDelaySeconds(1);
        return download;
    }

    private void awaitTerminal(Download download) {
        await().atMost(10, TimeUnit.SECONDS).until(() ->
                download.getStatus() == Download.Status.COMPLETED
                        || download.getStatus() == Download.Status.ERROR
                        || download.getStatus() == Download.Status.CANCELED);
    }

    private void assertCompleted(Download download, String content) throws IOException {
        awaitTerminal(download);
        assertEquals(Download.Status.COMPLETED, download.getStatus(), download.getErrorMessage());
        verify(listener, timeout(1000)).onDownloadComplete(download);
        verify(listener, never()).onDownloadError(eq(download), anyString());
        verify(listener, never()).onDownloadCanceled(download);
        assertEquals(content, Files.readString(tempDir.resolve(download.getName())));
        await().atMost(5, TimeUnit.SECONDS).until(() -> !handler.isActive(download.getId()));
    }

    private String assertFailed(Download download) {
        awaitTerminal(download);
        assertEquals(Download.Status.ERROR, download.getStatus());
        var error = ArgumentCaptor.forClass(String.class);
        verify(listener, timeout(1000)).onDownloadError(eq(download), error.capture());
        assertNotNull(error.getValue());
        assertFalse(error.getValue().isBlank());
        verify(listener, never()).onDownloadComplete(download);
        verify(listener, never()).onDownloadCanceled(download);
        await().atMost(5, TimeUnit.SECONDS).until(() -> handler.getActiveDownloadCount() == 0);
        return error.getValue();
    }

    private void assertRoutedRequest(String path) throws InterruptedException {
        RecordedRequest request = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request, "The request should reach the local HTTP server through SOCKS");
        assertEquals(path, request.getPath());
        assertTrue(proxy.hosts.contains(TEST_HOST), proxy.hosts.toString());
        assertTrue(proxy.requestTargets.contains(path), proxy.requestTargets.toString());
    }
}
