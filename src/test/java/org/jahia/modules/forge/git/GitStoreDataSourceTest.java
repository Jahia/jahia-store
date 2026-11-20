/*
 * ==========================================================================================
 * =                            JAHIA'S ENTERPRISE DISTRIBUTION                             =
 * ==========================================================================================
 *
 *                                  http://www.jahia.com
 *
 * JAHIA'S ENTERPRISE DISTRIBUTIONS LICENSING - IMPORTANT INFORMATION
 * ==========================================================================================
 *
 *     Copyright (C) 2002-2026 Jahia Solutions Group. All rights reserved.
 *
 *     This file is part of a Jahia's Enterprise Distribution.
 *
 *     Jahia's Enterprise Distributions must be used in accordance with the terms
 *     contained in the Jahia Solutions Group Terms & Conditions as well as
 *     the Jahia Sustainable Enterprise License (JSEL).
 *
 *     For questions regarding licensing, production usage...
 *     please contact our team at sales@jahia.com or go to http://www.jahia.com/license.
 *
 * ==========================================================================================
 */
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

    private static Path work;
    private static String url;
    private static File cacheDir;

    /** Types as the Jahia registry would answer them for the types this test queries. */
    private static final BiPredicate<String, String> TYPES = (type, queried) -> type.equals(queried)
            || ("jmix:forgeElement".equals(queried) && "jnt:forgeModule".equals(type));

    private static final String STORE_MD = "---\n"
            + "name: sample\n"
            + "groupId: org.example\n"
            + "title: Sample Module\n"
            + "icon: store/icon.png\n"
            + "screenshots:\n"
            + "  - store/shot.png\n"
            + "license: MIT\n"
            + "requiredVersion: 8.2.0.0\n"
            + "dependencies:\n"
            + "  - default\n"
            + "---\n"
            + "A **sample** module. ![Overview](store/overview.svg)\n"
            + "\n"
            + "## Versions\n"
            + "\n"
            + "- [1.1.0](store/1.1.0)\n"
            + "- [1.0.0](store/1.0.0)\n"
            + "- [0.9.0](store/0.9.0)\n"
            + "\n"
            + "## Tabs\n"
            + "\n"
            + "- [How to install](store/install.md)\n"
            + "- [License](store/license.md)\n";

    private static String version(String artifact, String date, String requiredVersion, boolean disabled, String notes) {
        return "---\n"
                + "artifact: " + artifact + "\n"
                + (date == null ? "" : "date: " + date + "\n")
                + (requiredVersion == null ? "" : "requiredVersion: " + requiredVersion + "\n")
                + (disabled ? "disabled: true\n" : "")
                + "---\n"
                + notes;
    }

    @BeforeAll
    static void repository() throws Exception {
        work = tmp.resolve("work");
        Files.createDirectories(work.resolve("store/1.0.0"));
        Files.createDirectories(work.resolve("store/1.1.0"));
        Files.createDirectories(work.resolve("store/0.9.0"));
        cacheDir = tmp.resolve("cache").toFile();
        try (Git git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call()) {
            write(work, "STORE.md", STORE_MD);
            Files.write(work.resolve("store/icon.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
            Files.write(work.resolve("store/shot.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 1});
            write(work, "store/overview.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"/>");
            write(work, "store/install.md", "Drop the **jar** in. ![Steps](install.png)\n");
            Files.write(work.resolve("store/install.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 3});
            write(work, "store/license.md", "MIT, in full.\n");
            write(work, "store/1.0.0/VERSION.md", version("https://example.com/old/1.0.0.jar", "2025-01-02", null, false, ""));
            write(work, "store/1.0.0/CHANGELOG.md", "- first\n");
            write(work, "store/1.1.0/VERSION.md", version("https://example.com/new/{version}.jar", "2026-03-04", "8.2.1.0", false,
                    "- second ![shot](shot-1.1.0.png)\n").replaceFirst("---\n", "---\ntag: v1.1.0\ntagUrl: https://example.com/tags/v1.1.0\n"));
            Files.write(work.resolve("store/1.1.0/shot-1.1.0.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 2});
            write(work, "store/0.9.0/VERSION.md", version("https://example.com/old/0.9.0.jar", "2024-01-02", null, true, "- disabled\n"));
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("store data").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
        url = work.toUri().toString();
    }

    private static void write(Path dir, String name, String content) throws Exception {
        Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
    }

    private static GitStoreDataSource dataSource() throws Exception {
        return new GitStoreDataSource(new GitRepositoryReader(cacheDir, url), null, new HashSet<>(Arrays.asList("en", "fr")),
                "/sites/store/contents/modules-repository/sample", "/files/live/sites/store/contents/modules-repository/sample", TYPES);
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
        assertThat(root.getI18nProperties().get("fr").get("description")[0]).contains("<strong>sample</strong>").doesNotContain("1.1.0")
                .contains("src=\"/files/live/sites/store/contents/modules-repository/sample/files/overview.svg\"");
        assertThat(root.getI18nProperties().get("en")).as("the License tab takes the place of the license field").doesNotContainKey("license");
    }

    @Test
    @DisplayName("the children are the icon folder, the screenshots list, the files, the tabs and every version, newest first")
    void childrenAreVersionsAndFolders() throws Exception {
        List<String> children = dataSource().getChildren("/");

        assertThat(children).containsExactly("icon", "screenshots", "files", "tabs", "1.1.0", "1.0.0", "0.9.0");
    }

    @Test
    @DisplayName("a tab is served under tabs with its title and its content, and the files it shows under files")
    void tabs() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.getChildren("/tabs")).containsExactly("how-to-install", "license");
        ExternalData tab = ds.getItemByPath("/tabs/how-to-install");
        assertThat(tab.getType()).isEqualTo("jnt:forgeTab");
        assertThat(tab.getProperties().get("jcr:title")).containsExactly("How to install");
        assertThat(tab.getProperties().get("content")[0]).contains("<strong>jar</strong>")
                .contains("src=\"/files/live/sites/store/contents/modules-repository/sample/files/install.png\"");
        assertThat(ds.getChildren("/files")).containsExactly("overview.svg", "install.png");
        assertThat(ds.itemExists("/tabs/nope")).isFalse();
    }

    @Test
    @DisplayName("a disabled version is served unpublished, and a file the description shows is served under files")
    void disabledVersionAndDescriptionFiles() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.getItemByPath("/0.9.0").getProperties().get("published")).containsExactly("false");
        assertThat(ds.getItemByPath("/1.0.0").getProperties().get("published")).containsExactly("true");
        assertThat(ds.getChildren("/files")).contains("overview.svg");
        assertThat(ds.getItemByPath("/files/overview.svg/jcr:content").getProperties().get("jcr:mimeType")).containsExactly("image/svg+xml");
    }

    @Test
    @DisplayName("a version reads its VERSION.md: the artefact, the date, the notes and the fields it inherits")
    void versionReadsItsFolder() throws Exception {
        GitStoreDataSource ds = dataSource();
        ExternalData old = ds.getItemByPath("/1.0.0");
        ExternalData recent = ds.getItemByPath("/1.1.0");

        assertThat(old.getType()).isEqualTo("jnt:forgeModuleVersion");
        assertThat(old.getProperties().get("url")).containsExactly("https://example.com/old/1.0.0.jar");
        assertThat(old.getProperties().get("changeLog")[0]).contains("first");
        assertThat(old.getProperties().get("uploadDate")[0]).startsWith("2025-01-02");
        assertThat(old.getProperties().get("references")).containsExactly("default");
        assertThat(recent.getProperties().get("url")).containsExactly("https://example.com/new/1.1.0.jar");
        assertThat(recent.getProperties().get("versionNumber")).containsExactly("1.1.0");
        assertThat(recent.getProperties().get("changeLog")[0]).contains("second")
                .contains("src=\"/files/live/sites/store/contents/modules-repository/sample/1.1.0/shot-1.1.0.png\"");
        assertThat(recent.getProperties().get("uploadDate")[0]).startsWith("2026-03-04");
        assertThat(recent.getProperties().get("tag")).containsExactly("v1.1.0");
        assertThat(recent.getProperties().get("tagUrl")).containsExactly("https://example.com/tags/v1.1.0");
        assertThat(recent.getProperties()).as("the host of a file URL is not known").doesNotContainKey("changelogUrl");
        assertThat(old.getProperties()).doesNotContainKey("tag");
    }

    @Test
    @DisplayName("the files of a version folder are served beside the version")
    void versionFiles() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.getChildren("/1.1.0")).containsExactly("shot-1.1.0.png");
        assertThat(ds.getChildren("/1.0.0")).isEmpty();
        ExternalData file = ds.getItemByPath("/1.1.0/shot-1.1.0.png");
        assertThat(file.getType()).isEqualTo("jnt:file");
        assertThat(ds.getItemByPath("/1.1.0/shot-1.1.0.png/jcr:content").getBinaryProperties().get("jcr:data")[0].getSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("the icon and the screenshots are served as files with their binary content")
    void iconAndScreenshots() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.getChildren("/icon")).containsExactly("icon.png");
        ExternalData file = ds.getItemByPath("/icon/icon.png");
        assertThat(file.getType()).isEqualTo("jnt:file");
        assertThat(file.getMixin()).contains("jmix:image");
        ExternalData content = ds.getItemByPath("/icon/icon.png/jcr:content");
        assertThat(content.getProperties().get("jcr:mimeType")).containsExactly("image/png");
        assertThat(content.getBinaryProperties().get("jcr:data")[0].getSize()).isEqualTo(4);
        assertThat(ds.getChildren("/screenshots")).containsExactly("shot.png");
        assertThat(ds.getItemByPath("/screenshots/shot.png/jcr:content").getBinaryProperties().get("jcr:data")[0].getSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("an unknown path does not exist")
    void unknownPath() throws Exception {
        GitStoreDataSource ds = dataSource();

        assertThat(ds.itemExists("/9.9.9")).isFalse();
        assertThatThrownBy(() -> ds.getItemByPath("/nope")).isInstanceOf(PathNotFoundException.class);
    }

    @Test
    @DisplayName("a reading is kept until a refresh, and a refresh that fails keeps it")
    void refresh() throws Exception {
        Path repo = tmp.resolve("refresh");
        Files.createDirectories(repo.resolve("store/1.0.0"));
        try (Git git = Git.init().setDirectory(repo.toFile()).setInitialBranch("main").call()) {
            write(repo, "STORE.md", withoutTabs(STORE_MD).replace("- [1.1.0](store/1.1.0)\n", "").replace("- [0.9.0](store/0.9.0)\n", ""));
            write(repo, "store/1.0.0/VERSION.md", version("https://example.com/1.0.0.jar", null, null, false, ""));
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("1.0.0").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            GitStoreDataSource ds = new GitStoreDataSource(new GitRepositoryReader(tmp.resolve("cache2").toFile(), repo.toUri().toString()),
                    null, Collections.singleton("en"), "/x", null, TYPES);
            assertThat(ds.getChildren("/")).containsExactly("icon", "screenshots", "files", "tabs", "1.0.0");

            Files.createDirectories(repo.resolve("store/1.1.0"));
            write(repo, "STORE.md", withoutTabs(STORE_MD).replace("- [0.9.0](store/0.9.0)\n", ""));
            write(repo, "store/1.1.0/VERSION.md", version("https://example.com/1.1.0.jar", null, null, false, ""));
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("1.1.0").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            assertThat(ds.getChildren("/")).as("nothing refreshes on its own").containsExactly("icon", "screenshots", "files", "tabs", "1.0.0");
            ds.refresh();
            assertThat(ds.getChildren("/")).containsExactly("icon", "screenshots", "files", "tabs", "1.1.0", "1.0.0");

            write(repo, "STORE.md", "no front matter\n");
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("broken").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
            assertThatThrownBy(ds::refresh).isInstanceOf(StoreManifestException.class).hasMessageContaining("front matter");
            assertThat(ds.getChildren("/")).as("the previous reading stays").containsExactly("icon", "screenshots", "files", "tabs", "1.1.0", "1.0.0");
        }
    }

    @Test
    @DisplayName("a repository without STORE.md, or whose version folder lacks its VERSION.md, is refused with the reason")
    void refusals() throws Exception {
        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        try (Git git = Git.init().setDirectory(empty.toFile()).setInitialBranch("main").call()) {
            write(empty, "README.md", "nothing");
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("init").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
        GitStoreDataSource noManifest = new GitStoreDataSource(new GitRepositoryReader(cacheDir, empty.toUri().toString()), null,
                Collections.singleton("en"), "/x", null, TYPES);
        assertThatThrownBy(noManifest::isAvailable).isInstanceOf(RepositoryException.class).hasMessageContaining("STORE.md");

        Path dangling = tmp.resolve("dangling");
        Files.createDirectories(dangling);
        try (Git git = Git.init().setDirectory(dangling.toFile()).setInitialBranch("main").call()) {
            write(dangling, "STORE.md", withoutTabs(STORE_MD));
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("init").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
        GitStoreDataSource noVersionFile = new GitStoreDataSource(new GitRepositoryReader(cacheDir, dangling.toUri().toString()), null,
                Collections.singleton("en"), "/x", null, TYPES);
        assertThatThrownBy(noVersionFile::isAvailable).hasMessageContaining("1.1.0").hasMessageContaining("VERSION.md");

        Path noTabFile = tmp.resolve("notab");
        Files.createDirectories(noTabFile.resolve("store/1.0.0"));
        try (Git git = Git.init().setDirectory(noTabFile.toFile()).setInitialBranch("main").call()) {
            write(noTabFile, "STORE.md", STORE_MD.replace("- [1.1.0](store/1.1.0)\n", "").replace("- [0.9.0](store/0.9.0)\n", ""));
            write(noTabFile, "store/1.0.0/VERSION.md", version("https://example.com/1.0.0.jar", null, null, false, ""));
            git.add().addFilepattern(".").call();
            git.commit().setSign(false).setMessage("init").setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
        }
        GitStoreDataSource missingTab = new GitStoreDataSource(new GitRepositoryReader(cacheDir, noTabFile.toUri().toString()), null,
                Collections.singleton("en"), "/x", null, TYPES);
        assertThatThrownBy(missingTab::isAvailable).hasMessageContaining("How to install").hasMessageContaining("store/install.md");
    }

    /** The manifest without its Tabs section, for a repository that holds no tab file. */
    private static String withoutTabs(String storeMd) {
        return storeMd.substring(0, storeMd.indexOf("## Tabs"));
    }
}
