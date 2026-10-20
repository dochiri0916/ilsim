package com.example.coderiskanalyzer.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.example.coderiskanalyzer.production.source.SourceDiscoveryException;
import com.example.coderiskanalyzer.production.source.SourceDiscoveryService;
import com.example.coderiskanalyzer.production.source.SourceRootDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDiscoveryServiceTests {

    @TempDir
    Path tempDir;

    private final SourceDiscoveryService service = new SourceDiscoveryService(new SourceRootDetector());

    @Test
    void discoversSingleModuleSourcesAndIgnoresBuildOutput() throws Exception {
        Path root = tempDir.resolve("src/main/java");
        Files.createDirectories(root.resolve("com/example"));
        Files.createFile(root.resolve("com/example/App.java"));
        Path generated = tempDir.resolve("build/generated/Generated.java");
        Files.createDirectories(generated.getParent());
        Files.createFile(generated);

        assertThat(service.discover(tempDir)).extracting(file -> file.path().getFileName().toString())
                .containsExactly("App.java");
    }

    @Test
    void reportsUnreadableDirectoriesInsteadOfSilentlySkippingThem() throws Exception {
        Path source = tempDir.resolve("src/main/java/A.java");
        Path locked = tempDir.resolve("src/main/java/locked");
        Files.createDirectories(locked);
        Files.createFile(source);
        Files.createFile(locked.resolve("Hidden.java"));
        java.util.List<Path> unreadable = new java.util.ArrayList<>();
        try {
            if (!locked.toFile().setReadable(false) || Files.isReadable(locked)) return;
            service.discover(tempDir, false, java.util.List.of(), java.util.List.of(), unreadable);
            assertThat(unreadable).contains(locked.toAbsolutePath().normalize());
        } finally {
            locked.toFile().setReadable(true);
        }
    }

    @Test
    void discoversMultiModuleSourcesAndKeepsPackagesNamedLikeBuildDirectories() throws Exception {
        Path first = tempDir.resolve("service-a/src/main/java/A.java");
        Path second = tempDir.resolve("service-b/src/main/java/B.java");
        Path excluded = tempDir.resolve("service-a/src/main/java/build/Generated.java");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.createDirectories(excluded.getParent());
        Files.createFile(first);
        Files.createFile(second);
        Files.createFile(excluded);

        assertThat(service.discover(tempDir)).extracting(file -> file.path().getFileName().toString())
                .containsExactly("A.java", "Generated.java", "B.java");
    }

    @Test
    void excludesTestSourcesByDefaultAndCanIncludeThem() throws Exception {
        Path testSource = tempDir.resolve("src/test/java/TestFixture.java");
        Files.createDirectories(testSource.getParent());
        Files.createFile(testSource);

        assertThat(service.discover(tempDir)).isEmpty();
        assertThat(service.discover(tempDir, true)).hasSize(1);
    }

    @Test
    void honorsConfiguredRootsAndGlobExclusionsAndRejectsRootEscape() throws Exception {
        Path custom = tempDir.resolve("custom/java");
        Files.createDirectories(custom.resolve("generated"));
        Files.writeString(custom.resolve("Keep.java"), "class Keep {}");
        Files.writeString(custom.resolve("generated/Skip.java"), "class Skip {}");

        assertThat(service.discover(tempDir, false, java.util.List.of("custom/java"),
                java.util.List.of("**/generated/**"))).extracting(file -> file.path().getFileName().toString())
                .containsExactly("Keep.java");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.discover(tempDir, false,
                java.util.List.of("../outside"), java.util.List.of()))
                .isInstanceOf(SourceDiscoveryException.class).hasMessageContaining("escapes");
    }
}
