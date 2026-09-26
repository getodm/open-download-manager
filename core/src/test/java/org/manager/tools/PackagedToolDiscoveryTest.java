package org.manager.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.aria2.Aria2ToolManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;

class PackagedToolDiscoveryTest {
    @TempDir Path root;
    private final String previousDirectory = System.getProperty("odm.tools.directory");
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private GlobalSettings settings;

    @BeforeEach void configure() {
        settings = new GlobalSettings();
        settings.setAria2Path(root.resolve("missing-custom-tool").toString());
    }

    @AfterEach void restore() {
        if (previousDirectory == null) {
            System.clearProperty("odm.tools.directory");
        } else {
            System.setProperty("odm.tools.directory", previousDirectory);
        }
        executor.shutdownNow();
    }

    @Test void discoversToolsAfterTheAppImageMovesWithoutPersistingTheMount() throws Exception {
        Path original = Files.createDirectories(root.resolve("original AppDir/usr/bin"));
        Files.createSymbolicLink(original.resolve("aria2c"), Path.of("/bin/true"));
        Path relocated = root.resolve("relocated tools");
        Files.move(original, relocated);
        System.setProperty("odm.tools.directory", relocated.toString());

        var manager = new Aria2ToolManager(settings, executor);
        assertEquals(relocated.resolve("aria2c").toString(), manager.getToolPath());
        assertEquals(root.resolve("missing-custom-tool").toString(), settings.getAria2Path());
    }

    @Test void honorsAnExplicitCustomTool() throws Exception {
        Path custom = Files.createSymbolicLink(root.resolve("custom-aria2"), Path.of("/bin/true"));
        System.setProperty("odm.tools.directory", root.toString());
        Files.createSymbolicLink(root.resolve("aria2c"), Path.of("/bin/false"));
        settings.setAria2Path(custom.toString());
        assertEquals(custom.toString(), new Aria2ToolManager(settings, executor).getToolPath());
    }

    @Test void rejectsDirectoriesAndNonExecutablePayloads() throws Exception {
        System.setProperty("odm.tools.directory", root.toString());
        Path tool = Files.createDirectory(root.resolve("aria2c"));
        var manager = new Aria2ToolManager(settings, executor) {
            @Override protected String findInCommonLocations() { return null; }
        };
        assertNull(manager.getToolPath());
        Files.delete(tool);
        Files.writeString(tool, "incomplete extraction");
        assertFalse(Files.isExecutable(tool));
        assertNull(manager.getToolPath());
    }
}
