package org.jahia.modules.forge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRPropertyWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.JCRValueWrapper;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.services.content.QueryManagerWrapper;
import org.jahia.services.query.QueryResultWrapper;
import org.jahia.services.query.QueryWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.query.Query;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityFeedTest {

    private static final String SITE = "store";
    private static final String ADVISORIES = "/sites/store/contents/security-advisories";
    private static final ObjectMapper JSON = new ObjectMapper();

    private JCRTemplate jcrTemplate;
    private JCRSessionWrapper editSession;
    private PublicationCheck publicationCheck;
    private JCRNodeWrapper repository;
    private QueryManagerWrapper queryManager;
    private QueryResultWrapper queryResult;
    private final ArgumentCaptor<String> statement = ArgumentCaptor.forClass(String.class);

    @BeforeEach
    void setUp() throws Exception {
        jcrTemplate = mock(JCRTemplate.class);
        editSession = mock(JCRSessionWrapper.class);
        publicationCheck = mock(PublicationCheck.class);
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("default"), eq(Locale.ENGLISH), any()))
                .thenAnswer(inv -> inv.<JCRCallback<?>>getArgument(3).doInJCR(editSession));
        when(editSession.getNode(anyString())).thenThrow(new PathNotFoundException());

        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getSiteKey()).thenReturn(SITE);
        repository = folder("modules-repository");
        when(repository.getResolveSite()).thenReturn(site);
        when(repository.getPath()).thenReturn("/sites/store/contents/modules-repository");

        JCRSessionWrapper liveSession = mock(JCRSessionWrapper.class);
        JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
        queryManager = mock(QueryManagerWrapper.class);
        QueryWrapper query = mock(QueryWrapper.class);
        queryResult = mock(QueryResultWrapper.class);
        when(repository.getSession()).thenReturn(liveSession);
        when(liveSession.getWorkspace()).thenReturn(workspace);
        when(workspace.getQueryManager()).thenReturn(queryManager);
        when(queryManager.createQuery(anyString(), eq(Query.JCR_SQL2))).thenReturn(query);
        when(query.execute()).thenReturn(queryResult);
        queryReturns();
    }

    private JsonNode build() throws Exception {
        return JSON.readTree(new SecurityFeed(jcrTemplate, publicationCheck).build(repository));
    }

    // ---------------------------------------------------------------- advisories

    @Test
    @DisplayName("no advisories folder -> schemaVersion 1 and empty lists")
    void noAdvisoriesFolder() throws Exception {
        JsonNode feed = build();

        assertThat(feed.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(feed.get("advisories")).isEmpty();
        assertThat(feed.get("flaggedVersions")).isEmpty();
    }

    @Test
    @DisplayName("a fully published advisory is emitted with every field, fixes grouped by component")
    void publishedAdvisoryIsEmitted() throws Exception {
        JCRNodeWrapper advisory = advisory("jsa-2026-0011", "JSA-2026-0011", "high",
                "graphql-dxm-provider@3.10.0", "org.jahia.modules:graphql-dxm-provider@2.24.0", "jahia-core@8.2.3.7");
        when(advisory.getDisplayableName()).thenReturn("GraphQL flaw");
        stringProperty(advisory, "advisoryUrl", "https://academy.jahia.com/jsa-2026-0011");
        stringProperty(advisory, "summary", "Short summary");
        multiProperty(advisory, "cves", "CVE-2026-1234");
        JCRPropertyWrapper cvss = mock(JCRPropertyWrapper.class);
        when(cvss.getDouble()).thenReturn(7.5);
        when(advisory.hasProperty("cvss")).thenReturn(true);
        when(advisory.getProperty("cvss")).thenReturn(cvss);
        JCRPropertyWrapper date = mock(JCRPropertyWrapper.class);
        Calendar published = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        published.clear();
        published.set(2026, Calendar.NOVEMBER, 3);
        when(date.getDate()).thenReturn(published);
        when(advisory.hasProperty("publishedDate")).thenReturn(true);
        when(advisory.getProperty("publishedDate")).thenReturn(date);
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(true);

        JsonNode a = build().get("advisories").get(0);

        assertThat(a.get("id").asText()).isEqualTo("JSA-2026-0011");
        assertThat(a.get("title").asText()).isEqualTo("GraphQL flaw");
        assertThat(a.get("severity").asText()).isEqualTo("high");
        assertThat(a.get("cvss").asDouble()).isEqualTo(7.5);
        assertThat(a.get("cves")).extracting(JsonNode::asText).containsExactly("CVE-2026-1234");
        assertThat(a.get("url").asText()).isEqualTo("https://academy.jahia.com/jsa-2026-0011");
        assertThat(a.get("published").asText()).isEqualTo("2026-11-03");
        assertThat(a.get("summary").asText()).isEqualTo("Short summary");
        JsonNode fixes = a.get("fixes");
        assertThat(fixes).hasSize(2);
        assertThat(fixes.get(0).get("component").asText()).isEqualTo("graphql-dxm-provider");
        assertThat(fixes.get(0).get("groupId").asText()).isEqualTo("org.jahia.modules");
        assertThat(fixes.get(0).get("fixedVersions")).extracting(JsonNode::asText)
                .containsExactly("3.10.0", "2.24.0");
        assertThat(fixes.get(1).get("component").asText()).isEqualTo("jahia-core");
        assertThat(fixes.get(1).has("groupId")).isFalse();
        assertThat(fixes.get(1).get("fixedVersions")).extracting(JsonNode::asText).containsExactly("8.2.3.7");
    }

    @Test
    @DisplayName("an advisory that is not fully published (draft, modified, live-only) is not emitted")
    void unpublishedAdvisoryIsSkipped() throws Exception {
        JCRNodeWrapper draft = advisory("draft", "JSA-2026-0012", "critical", "jahia-core@8.2.3.8");
        JCRNodeWrapper published = advisory("pub", "JSA-2026-0013", "low", "jcontent@3.8.2");
        advisoriesFolder(draft, published);
        when(publicationCheck.isFullyPublished(draft)).thenReturn(false);
        when(publicationCheck.isFullyPublished(published)).thenReturn(true);

        JsonNode advisories = build().get("advisories");

        assertThat(advisories).extracting(a -> a.get("id").asText()).containsExactly("JSA-2026-0013");
    }

    @Test
    @DisplayName("optional fields are omitted, not emitted as null or empty strings")
    void optionalFieldsOmitted() throws Exception {
        JCRNodeWrapper advisory = advisory("min", "CVE-2026-9999", "medium", "jcontent@3.8.2");
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(true);

        JsonNode a = build().get("advisories").get(0);

        assertThat(a.has("cvss")).isFalse();
        assertThat(a.has("url")).isFalse();
        assertThat(a.has("published")).isFalse();
        assertThat(a.has("summary")).isFalse();
        assertThat(a.get("cves")).isEmpty();
    }

    @Test
    @DisplayName("a published advisory with one invalid fixedIn value fails the feed (never a silent drop)")
    void invalidFixedInFailsLoud() throws Exception {
        JCRNodeWrapper advisory = advisory("bad", "JSA-2026-0014", "high", "jcontent@3.8.2", "jahia-core@8.2..3");
        when(advisory.getPath()).thenReturn(ADVISORIES + "/bad");
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(true);

        assertThatThrownBy(this::build).isInstanceOf(RepositoryException.class)
                .hasMessageContaining(ADVISORIES + "/bad").hasMessageContaining("fixedIn");
    }

    @Test
    @DisplayName("a published advisory with no fixedIn value fails the feed")
    void noFixedInFailsLoud() throws Exception {
        JCRNodeWrapper advisory = advisory("empty", "JSA-2026-0016", "high");
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(true);

        assertThatThrownBy(this::build).isInstanceOf(RepositoryException.class).hasMessageContaining("fixedIn");
    }

    @Test
    @DisplayName("a published advisory with an unknown severity fails the feed")
    void unknownSeverityFailsLoud() throws Exception {
        JCRNodeWrapper advisory = advisory("sev", "JSA-2026-0015", "urgent", "jcontent@3.8.2");
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(true);

        assertThatThrownBy(this::build).isInstanceOf(RepositoryException.class).hasMessageContaining("severity");
    }

    @Test
    @DisplayName("an invalid advisory that is NOT published is ignored, not an error")
    void invalidDraftIgnored() throws Exception {
        JCRNodeWrapper advisory = advisory("draft", "JSA-2026-0017", "urgent", "nonsense");
        advisoriesFolder(advisory);
        when(publicationCheck.isFullyPublished(advisory)).thenReturn(false);

        assertThat(build().get("advisories")).isEmpty();
    }

    @Test
    @DisplayName("children that are not advisories are ignored")
    void otherChildrenIgnored() throws Exception {
        JCRNodeWrapper text = mock(JCRNodeWrapper.class);
        when(text.isNodeType(anyString())).thenReturn(false);
        advisoriesFolder(text);

        assertThat(build().get("advisories")).isEmpty();
    }

    @Test
    @DisplayName("a repository error while reading advisories propagates: never answer 'no advisories'")
    void repositoryErrorPropagates() throws Exception {
        when(jcrTemplate.doExecuteWithSystemSessionAsUser(isNull(), eq("default"), eq(Locale.ENGLISH), any()))
                .thenThrow(new RepositoryException("boom"));

        assertThatThrownBy(this::build).isInstanceOf(RepositoryException.class);
    }

    // ---------------------------------------------------------------- owner-flagged versions

    @Test
    @DisplayName("versions returned by the securityFix query are listed with their module coordinates")
    void flaggedVersionsListed() throws Exception {
        JCRNodeWrapper module = module("my-module", "org.jahia.community", true);
        JCRNodeWrapper flagged = version(module, "1.4.2");
        stringProperty(flagged, "securitySeverity", "medium");
        multiProperty(flagged, "securityReferences", "GHSA-abcd-efgh-ijkl");
        stringProperty(flagged, "securityNote", "Fixes an XSS");
        queryReturns(flagged);

        JsonNode versions = build().get("flaggedVersions");

        assertThat(versions).hasSize(1);
        JsonNode v = versions.get(0);
        assertThat(v.get("groupId").asText()).isEqualTo("org.jahia.community");
        assertThat(v.get("name").asText()).isEqualTo("my-module");
        assertThat(v.get("version").asText()).isEqualTo("1.4.2");
        assertThat(v.get("severity").asText()).isEqualTo("medium");
        assertThat(v.get("references")).extracting(JsonNode::asText).containsExactly("GHSA-abcd-efgh-ijkl");
        assertThat(v.get("note").asText()).isEqualTo("Fixes an XSS");
    }

    @Test
    @DisplayName("the query selects published, flagged module versions under the repository, quote-escaped")
    void queryStatement() throws Exception {
        when(repository.getPath()).thenReturn("/sites/store/contents/o'brien");
        queryReturns();

        build();

        verify(queryManager).createQuery(statement.capture(), eq(Query.JCR_SQL2));
        assertThat(statement.getValue())
                .startsWith("SELECT * FROM [jnt:forgeModuleVersion] AS v WHERE ISDESCENDANTNODE(v, '/sites/store/contents/o''brien')")
                .contains("v.[securityFix] = true")
                .contains("v.[published] = true");
    }

    @Test
    @DisplayName("versions of an unpublished module, or of a non-module parent, are never listed")
    void unpublishedOrForeignParentSkipped() throws Exception {
        JCRNodeWrapper hidden = version(module("hidden", "org.jahia.community", false), "2.0.0");
        JCRNodeWrapper packageParent = mock(JCRNodeWrapper.class);
        when(packageParent.isNodeType("jnt:forgeModule")).thenReturn(false);
        JCRNodeWrapper foreign = version(packageParent, "1.0.0");
        queryReturns(hidden, foreign);

        assertThat(build().get("flaggedVersions")).isEmpty();
    }

    @Test
    @DisplayName("an unknown severity or a missing groupId is omitted, not emitted as null")
    void unknownSeverityAndMissingGroupIdOmitted() throws Exception {
        JCRNodeWrapper flagged = version(module("m", null, true), "2.0.0");
        stringProperty(flagged, "securitySeverity", "urgent");
        queryReturns(flagged);

        JsonNode v = build().get("flaggedVersions").get(0);

        assertThat(v.has("severity")).isFalse();
        assertThat(v.has("groupId")).isFalse();
        assertThat(v.has("note")).isFalse();
        assertThat(v.get("references")).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private JCRNodeWrapper advisory(String name, String id, String severity, String... fixedIn) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.getDisplayableName()).thenReturn(name);
        when(node.isNodeType("jnt:forgeSecurityAdvisory")).thenReturn(true);
        stringProperty(node, "advisoryId", id);
        stringProperty(node, "severity", severity);
        multiProperty(node, "fixedIn", fixedIn);
        return node;
    }

    private void advisoriesFolder(JCRNodeWrapper... advisories) throws Exception {
        JCRNodeWrapper folder = folder("security-advisories", advisories);
        doReturn(folder).when(editSession).getNode(ADVISORIES);
    }

    private static JCRNodeWrapper module(String name, String groupId, boolean published) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.isNodeType("jnt:forgeModule")).thenReturn(true);
        booleanProperty(node, "published", published);
        if (groupId != null) {
            stringProperty(node, "groupId", groupId);
        }
        return node;
    }

    private static JCRNodeWrapper version(JCRNodeWrapper parent, String number) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getParent()).thenReturn(parent);
        stringProperty(node, "versionNumber", number);
        return node;
    }

    private void queryReturns(JCRNodeWrapper... versions) throws Exception {
        List<JCRNodeWrapper> list = Arrays.asList(versions);
        when(queryResult.getNodes()).thenAnswer(inv -> iterator(list));
    }

    private static JCRNodeWrapper folder(String name, JCRNodeWrapper... kids) throws Exception {
        JCRNodeWrapper node = mock(JCRNodeWrapper.class);
        when(node.getName()).thenReturn(name);
        when(node.isNodeType("jnt:contentFolder")).thenReturn(true);
        children(node, kids);
        return node;
    }

    private static void children(JCRNodeWrapper parent, JCRNodeWrapper... kids) throws Exception {
        List<JCRNodeWrapper> list = new ArrayList<>(Arrays.asList(kids));
        when(parent.getNodes()).thenAnswer(inv -> iterator(list));
    }

    private static JCRNodeIteratorWrapper iterator(List<JCRNodeWrapper> list) {
        Iterator<JCRNodeWrapper> it = list.iterator();
        JCRNodeIteratorWrapper wrapper = mock(JCRNodeIteratorWrapper.class);
        when(wrapper.hasNext()).thenAnswer(inv -> it.hasNext());
        when(wrapper.nextNode()).thenAnswer(inv -> it.next());
        when(wrapper.next()).thenAnswer(inv -> it.next());
        return wrapper;
    }

    private static void stringProperty(JCRNodeWrapper node, String name, String value) throws Exception {
        JCRPropertyWrapper prop = mock(JCRPropertyWrapper.class);
        when(prop.getString()).thenReturn(value);
        when(node.hasProperty(name)).thenReturn(true);
        when(node.getProperty(name)).thenReturn(prop);
        when(node.getPropertyAsString(name)).thenReturn(value);
    }

    private static void booleanProperty(JCRNodeWrapper node, String name, boolean value) throws Exception {
        JCRPropertyWrapper prop = mock(JCRPropertyWrapper.class);
        when(prop.getBoolean()).thenReturn(value);
        when(prop.getString()).thenReturn(Boolean.toString(value));
        when(node.hasProperty(name)).thenReturn(true);
        when(node.getProperty(name)).thenReturn(prop);
    }

    private static void multiProperty(JCRNodeWrapper node, String name, String... values) throws Exception {
        JCRPropertyWrapper prop = mock(JCRPropertyWrapper.class);
        JCRValueWrapper[] wrapped = new JCRValueWrapper[values.length];
        for (int i = 0; i < values.length; i++) {
            JCRValueWrapper v = mock(JCRValueWrapper.class);
            when(v.getString()).thenReturn(values[i]);
            wrapped[i] = v;
        }
        when(prop.isMultiple()).thenReturn(true);
        when(prop.getValues()).thenReturn(wrapped);
        when(node.hasProperty(name)).thenReturn(true);
        when(node.getProperty(name)).thenReturn(prop);
    }
}
