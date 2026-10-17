package org.jahia.modules.forge.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.BiPredicate;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import org.eclipse.jgit.api.Git;
import org.jahia.modules.external.ExternalData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data source over a real repository, built with JGit in a temporary directory and read
 * through its file URL: no network, and the same code path as an HTTP remote.
 */
class GitStoreDataSourceTest {

    @TempDir
    static Path tmp;

    private static String url;
    private static File cacheDir;

    /** Types as the Jahia registry would answer them for the types this test queries. */
    private static final BiPredicate<String, String> TYPES = (type, queried) -> type.equals(queried)
            || ("jmix:forgeElement".equals(queried) && "jnt:forgeModule".equals(type));

    private static String manifest(String artifact, String requiredVersion) {
        return "---\n"
                + "name: sample\n"
                + "groupId: org.example\n"
                + "title: Sample Module\n"
                + "artifact: " + artifact + "\n"
                + "icon: icon.png\n"
                + "license: MIT\n"
                + (requiredVersion == null ? "" : "requiredVersion: " + requiredVersion + "\n")
                + "---\n"
                + "A **sample** module.\n";
    }

    @BeforeAll
    static void repository() throws Exception {
        Path work = tmp.resolve("work");
        Files.createDirectories(work);
        cacheDir = tmp.resolve("cache").toFile();
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            write(work, "STORE.md", manifest("https://example.com/old/{version}.jar", null));
            write(work, "CHANGELOG.md", "# Changelog\n\n## [1.0.0]\n- first\n");
            Files.write(work.resolve("icon.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("1.0.0").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            git.tag().setName("1.0.0").call();

            write(work, "STORE.md", manifest("https://example.com/new/{version}.jar", "8.2.1.0"));
            write(work, "CHANGELOG.md", "# Changelog\n\n## [1.1.0]\n- second\n\n## [1.0.0]\n- first\n");
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("1.1.0").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            git.tag().setName("v1.1.0").setAnnotated(true).setSigned(false).setMessage("release").call();
            git.tag().setName("1.2.0-rc1").call();
        }
        url = work.toUri().toString();
    }

    private static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static GitStoreDataSource dataSource() throws Exception {
        return new GitStoreDataSource(new GitRepositoryReader(cacheDir, url), null, VersionTags.compile(null),
                60_000, new HashSet<>(Arrays.asList("en", "fr")), "/sites/store/contents/modules-repository/sample", TYPES);
    }

    @Test
    @DisplayName("the root is the module, with its title, groupId and description in every site language")
    void rootIsModule() throws Exception {
        ExternalData root = dataSource().getItemByPath("/");

        assertThat(root.getType()).isEqualTo("jnt:forgeModule");
        assertThat(root.getProperties().get("jcr:title")).containsExactly("Sample Module");
        assertThat(root.getProperties().get("groupId")).containsExactly("org.example");
        assertThat(root.getProperties().get("published")).containsExactly("true");
        assertThat(root.getProperties().get("codeRepository")).containsExactly(url);
        assertThat(root.getI18nProperties()).containsKeys("en", "fr");
        assertThat(root.getI18nProperties().get("fr").get("description")[0]).contains("<strong>sample</strong>");
        assertThat(root.getI18nProperties().get("en").get("license")).containsExactly("MIT");
    }

    @Test
    @DisplayName("the children are the icon folder, the screenshots list and one version per matching tag, newest first")
    void childrenAreVersionsAndFolders() throws Exception {
        List<String> children = dataSource().getChildren("/");

        assertThat(children).containsExactly("icon", "screenshots", "1.1.0", "1.0.0");
    }

    @Test
    @DisplayName("a version reads STORE.md and CHANGELOG.md as they stand at its tag")
    void versionReadsFilesAtTag() throws Exception {
        GitStoreDataSource ds = dataSource();
        ExternalData old = ds.getItemByPath("/1.0.0");
        ExternalData recent = ds.getItemByPath("/1.1.0");

        assertThat(old.getType()).isEqualTo("jnt:forgeModuleVersion");
        assertThat(old.getProperties().get("url")).containsExactly("https://example.com/old/1.0.0.jar");
        assertThat(old.getProperties().get("changeLog")[0]).contains("first").doesNotContain("second");
        assertThat(old.getProperties()).doesNotContainKey("requiredVersion");
        assertThat(recent.getProperties().get("url")).containsExactly("https://example.com/new/1.1.0.jar");
        assertThat(recent.getProperties().get("versionNumber")).containsExactly("1.1.0");
        assertThat(recent.getProperties().get("changeLog")[0]).contains("second");
        assertThat(recent.getProperties().get("uploadDate")[0]).startsWith("20");
    }

    @Test
    @DisplayName("the icon is served as a file with its binary content")
    void iconIsAFile() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.getChildren("/icon")).containsExactly("icon.png");
        ExternalData file = ds.getItemByPath("/icon/icon.png");
        assertThat(file.getType()).isEqualTo("jnt:file");
        assertThat(file.getMixin()).contains("jmix:image");
        ExternalData content = ds.getItemByPath("/icon/icon.png/jcr:content");
        assertThat(content.getProperties().get("jcr:mimeType")).containsExactly("image/png");
        assertThat(content.getBinaryProperties().get("jcr:data")[0].getSize()).isEqualTo(4);
        assertThat(ds.getChildren("/screenshots")).isEmpty();
    }

    @Test
    @DisplayName("an unknown path does not exist")
    void unknownPath() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.itemExists("/9.9.9")).isFalse();
        assertThatThrownBy(() -> ds.getItemByPath("/nope")).isInstanceOf(PathNotFoundException.class);
    }

    @Test
    @DisplayName("a repository without STORE.md is refused with the reason")
    void repositoryWithoutManifest() throws Exception {
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        try (Git git = Git.init().setDirectory(empty.toFile()).setInitialBranch("main").call()) {
            write(empty, "README.md", "nothing");
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("init").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
        GitStoreDataSource ds = new GitStoreDataSource(new GitRepositoryReader(cacheDir, empty.toUri().toString()), null,
                VersionTags.compile(null), 60_000, Collections.singleton("en"), "/x", TYPES);

        assertThatThrownBy(ds::isAvailable).isInstanceOf(RepositoryException.class).hasMessageContaining("STORE.md");
    }
}
