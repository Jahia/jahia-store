package org.jahia.modules.forge.actions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSTORE-SEC-012, validation at upload: {@code CreateEntryFromJar} stores a package JAR and a JS module
 * tgz as {@code jnt:file} nodes under the forge element. It used to store them with the content type the
 * browser sent and without checking their bytes, so an uploader could store a markup file (an SVG/HTML
 * polyglot that still parses as an archive) under {@code image/svg+xml} and have it served inline. The
 * stored content type now comes from the trusted extension, and the bytes must start with the archive
 * signature of that extension.
 */
class ArtifactUploadGuardTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>";

    @Test
    @DisplayName("a jar is stored as application/java-archive, whatever the browser sent")
    void jarContentType() {
        assertThat(ArtifactUploadGuard.contentType("jar")).isEqualTo("application/java-archive");
    }

    @Test
    @DisplayName("a tgz is stored as application/gzip, whatever the browser sent")
    void tgzContentType() {
        assertThat(ArtifactUploadGuard.contentType("tgz")).isEqualTo("application/gzip");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jar", "tgz"})
    @DisplayName("a stored artifact content type is never script-capable")
    void contentTypeNeverScriptCapable(String extension) {
        assertThat(MagicByteImageValidator.isScriptCapableMime(ArtifactUploadGuard.contentType(extension))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"svg", "html", "", "JAR"})
    @DisplayName("an extension that is not a trusted one has no content type")
    void untrustedExtensionRejected(String extension) {
        assertThatThrownBy(() -> ArtifactUploadGuard.contentType(extension))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a real jar has the jar signature")
    void realJarAccepted() throws Exception {
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(zip()), "jar")).isTrue();
    }

    @Test
    @DisplayName("a real tgz has the tgz signature")
    void realTgzAccepted() throws Exception {
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(gzip()), "tgz")).isTrue();
    }

    @Test
    @DisplayName("an SVG prepended to a zip (a polyglot that still opens as a jar) is refused")
    void markupPolyglotJarRefused() throws Exception {
        byte[] polyglot = concat(SVG.getBytes(StandardCharsets.UTF_8), zip());
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(polyglot), "jar")).isFalse();
    }

    @Test
    @DisplayName("an SVG named .jar is refused")
    void svgNamedJarRefused() throws Exception {
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(SVG.getBytes(StandardCharsets.UTF_8)), "jar"))
                .isFalse();
    }

    @Test
    @DisplayName("a zip named .tgz, or a gzip named .jar, is refused")
    void wrongArchiveRefused() throws Exception {
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(zip()), "tgz")).isFalse();
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(gzip()), "jar")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3})
    @DisplayName("an empty or truncated upload is refused")
    void truncatedRefused(int length) throws Exception {
        byte[] truncated = java.util.Arrays.copyOf(zip(), length);
        assertThat(ArtifactUploadGuard.hasArchiveSignature(stream(truncated), "jar")).isFalse();
    }

    @Test
    @DisplayName("CreateEntryFromJar never stores the browser-supplied content type")
    void actionIgnoresClientContentType() throws Exception {
        // Bytecode canary, like CreateEntryFromJarUploadTest#uploadHandler_neverClassLoadsArtifact:
        // the action must not read FileItem#getContentType at all.
        try (InputStream in = CreateEntryFromJar.class.getResourceAsStream("CreateEntryFromJar.class")) {
            String bytecode = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(bytecode).doesNotContain("getContentType");
            assertThat(bytecode).contains("org/jahia/modules/forge/actions/ArtifactUploadGuard");
        }
    }

    private static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] zip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zos.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return out.toByteArray();
    }

    private static byte[] gzip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write("package/package.json".getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
