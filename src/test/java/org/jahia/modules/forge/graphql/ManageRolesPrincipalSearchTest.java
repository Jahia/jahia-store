package org.jahia.modules.forge.graphql;

import org.jahia.services.content.JCRNodeIteratorWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRWorkspaceWrapper;
import org.jahia.services.content.QueryManagerWrapper;
import org.jahia.services.query.QueryResultWrapper;
import org.jahia.services.query.QueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.jcr.query.Query;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S27 / U11 — principal search must be JCR-SQL2-injection-safe. {@code searchPrincipalNodes}
 * (private static) builds the query with {@code sqlSafe = JCRContentUtils.sqlEncode(v).replace("'",
 * "''")}, so a search term or siteKey carrying a single quote cannot break out of the
 * {@code like '%…%'} literal or the {@code isdescendantnode('…')} argument.
 */
class ManageRolesPrincipalSearchTest {

    private static String capturedQueryFor(String siteKey, String term) throws Exception {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        JCRWorkspaceWrapper workspace = mock(JCRWorkspaceWrapper.class);
        QueryManagerWrapper qm = mock(QueryManagerWrapper.class);
        QueryWrapper query = mock(QueryWrapper.class);
        QueryResultWrapper result = mock(QueryResultWrapper.class);
        JCRNodeIteratorWrapper empty = mock(JCRNodeIteratorWrapper.class);
        when(session.getWorkspace()).thenReturn(workspace);
        when(workspace.getQueryManager()).thenReturn(qm);
        ArgumentCaptor<String> stmt = ArgumentCaptor.forClass(String.class);
        when(qm.createQuery(stmt.capture(), eq(Query.JCR_SQL2))).thenReturn(query);
        when(query.execute()).thenReturn(result);
        when(result.getNodes()).thenReturn(empty);
        when(empty.hasNext()).thenReturn(false);

        Method m = ManageRolesQueryExtension.class.getDeclaredMethod(
                "searchPrincipalNodes", JCRSessionWrapper.class, String.class,
                PrincipalType.class, String.class);
        m.setAccessible(true);
        m.invoke(null, session, siteKey, PrincipalType.USER, term);
        return stmt.getValue();
    }

    @Test
    @DisplayName("a single-quote injection in the search term is doubled, not left to break the literal")
    void searchTerm_quotesDoubled() throws Exception {
        String statement = capturedQueryFor("mysite", "' or localname(p) like '%");
        // Every injected quote is doubled -> the payload stays inside the string literal.
        assertThat(statement).contains("''");
        assertThat(statement).doesNotContain("like '%' or localname");
    }

    @Test
    @DisplayName("a single-quote injection in the siteKey is escaped inside isdescendantnode")
    void siteKey_quotesDoubled() throws Exception {
        String statement = capturedQueryFor("s'x", "alice");
        // sqlSafe = JCRContentUtils.sqlEncode(v).replace("'","''"); sqlEncode already doubles the
        // quote, so the net effect is an over-escaped (never under-escaped) literal — the point is
        // that the quote is never left single to break out of the isdescendantnode('...') argument.
        assertThat(statement).contains("s''''x");
        assertThat(statement).doesNotContain("['/sites/s'x']");
    }
}
