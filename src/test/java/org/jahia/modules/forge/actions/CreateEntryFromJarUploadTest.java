package org.jahia.modules.forge.actions;

import org.jahia.data.templates.ModuleReleaseInfo;
import org.jahia.utils.ProcessHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration-body coverage for {@code CreateEntryFromJar} (the pure coordinate/extension/version
 * validators are covered by {@link CreateEntryFromJarValidatorTest}, and the size/SNAPSHOT/wrong-
 * extension/tar-bomb <em>rejections</em> are exercised end-to-end by Cypress {@code
 * 11-moduleUpload.cy.ts} — the full {@code doExecute} entry point pulls in Jahia static
 * initializers that need a live container, so it is covered at the integration level). Seams used
 * here, all behaviour-preserving (no source change):
 * <ul>
 *   <li>{@code deployToMaven} is private — invoked by reflection.</li>
 *   <li>{@code ProcessHelper.execute} is statically mocked (mockito-inline) so no real Maven runs.</li>
 * </ul>
 */
class CreateEntryFromJarUploadTest {

    private static Method declared(String name, Class<?>... params) throws Exception {
        Method m = CreateEntryFromJar.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /** Build a temp jar carrying a Maven pom (drives the pom-coordinate re-validation). */
    private static File jarWithPom(String groupId, String artifactId, String version) throws Exception {
        File jar = File.createTempFile("upload-test", ".jar");
        jar.deleteOnExit();
        String pom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                + "<modelVersion>4.0.0</modelVersion>"
                + "<groupId>" + groupId + "</groupId>"
                + "<artifactId>" + artifactId + "</artifactId>"
                + "<version>" + version + "</version></project>";
        try (ZipOutputStream zos = new ZipOutputStream(new java.io.FileOutputStream(jar))) {
            zos.putNextEntry(new ZipEntry("META-INF/maven/g/a/pom.xml"));
            zos.write(pom.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return jar;
    }

    private static ModuleReleaseInfo releaseInfo() {
        ModuleReleaseInfo info = new ModuleReleaseInfo();
        info.setRepositoryId("remote-repository");
        info.setRepositoryUrl("https://nexus.internal/repository/store/");
        info.setUsername("svc<x>");   // XML-metacharacter creds -> escaped, never break out
        info.setPassword("p&w\"d");
        return info;
    }

    // ---- S11 / U4 / D12 : owner-role grant skips guest -------------------------------------
    // CreateEntryFromJar.grantOwnerRole was replaced by OwnerRoleGrants (SEC-366: deferred,
    // system-session grant scoped to the upload subtree). S11 is covered there:
    // OwnerRoleGrantsTest#shouldRecord_newNode_isTrue / #recordOwner_newNode_isQueued (real user)
    // and #shouldRecord_guest_isFalse / #recordOwner_guest_queuesNothing (guest skipped).

    // ---- S12 / U6 : deploy re-validates pom coordinates before shelling out ----------------

    @Test
    @DisplayName("S12: hostile pom coordinates abort the deploy (ProcessHelper.execute never called)")
    void deploy_hostileCoordinates_neverExecutes() throws Exception {
        File jar = jarWithPom("${env.PATH}", "a", "1.0.0"); // expression-injection groupId
        CreateEntryFromJar action = new CreateEntryFromJar();
        action.setMavenExecutable("mvn");
        Method deploy = declared("deployToMaven", String.class, String.class,
                ModuleReleaseInfo.class, File.class);

        try (MockedStatic<ProcessHelper> ph = org.mockito.Mockito.mockStatic(ProcessHelper.class)) {
            assertThatThrownBy(() -> deploy.invoke(action, "g", "a", releaseInfo(), jar))
                    .isInstanceOf(InvocationTargetException.class)
                    .hasRootCauseInstanceOf(java.io.IOException.class);
            ph.verifyNoInteractions(); // no -D args ever built from the tainted coordinate
        }
    }

    @Test
    @DisplayName("S12: safe pom coordinates flow through validation into the deploy subprocess")
    void deploy_safeCoordinates_executesWithValidatedCoords() throws Exception {
        File jar = jarWithPom("org.example", "mymod", "1.0.0");
        CreateEntryFromJar action = new CreateEntryFromJar();
        action.setMavenExecutable("mvn");
        Method deploy = declared("deployToMaven", String.class, String.class,
                ModuleReleaseInfo.class, File.class);

        try (MockedStatic<ProcessHelper> ph = org.mockito.Mockito.mockStatic(ProcessHelper.class)) {
            ph.when(() -> ProcessHelper.execute(anyString(), any(), any(), any(), any(), any()))
                    .thenReturn(0);

            deploy.invoke(action, "g", "a", releaseInfo(), jar);

            ArgumentCaptor<String> exec = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String[]> params = ArgumentCaptor.forClass(String[].class);
            ph.verify(() -> ProcessHelper.execute(exec.capture(), params.capture(),
                    any(), any(), any(), any()));
            // mavenExecutable derives from config/sysprop, never from upload input.
            assertThat(exec.getValue()).isEqualTo("mvn");
            assertThat(params.getValue())
                    .contains("deploy:deploy-file", "-DgroupId=org.example",
                            "-DartifactId=mymod", "-Dversion=1.0.0");
        }
    }

    // ---- S13 / U6 : uploaded artifact is never class-loaded (RCE canary, metadata-only) ----

    @Test
    @DisplayName("S13: CreateEntryFromJar contains NO dynamic class-loading of the uploaded artifact")
    void uploadHandler_neverClassLoadsArtifact() throws Exception {
        // Metadata-only canary: the upload/deploy code path reads MANIFEST / package.json / pom
        // bytes and streams the file to a subprocess. It must never gain a class-loading capability
        // over the artifact, so its bytecode must reference no URLClassLoader / Class.forName /
        // ClassLoader.loadClass / defineClass sink.
        final String bytecode = classBytes(CreateEntryFromJar.class);
        assertThat(bytecode).doesNotContain("URLClassLoader");
        assertThat(bytecode).doesNotContain("defineClass");
        assertThat(bytecode).doesNotContain("loadClass");
        // Class.forName would leave a "forName" symbol referencing java/lang/Class.
        assertThat(bytecode).doesNotContain("forName");
    }

    private static String classBytes(Class<?> clazz) throws Exception {
        try (InputStream in = clazz.getResourceAsStream(clazz.getSimpleName() + ".class")) {
            byte[] all = in.readAllBytes();
            // ISO-8859-1 keeps every byte 1:1 so constant-pool UTF8 symbols survive as substrings.
            return new String(all, StandardCharsets.ISO_8859_1);
        }
    }
}
