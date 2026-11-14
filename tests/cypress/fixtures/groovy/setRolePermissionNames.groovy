// Sets the j:permissionNames of a role in the edit workspace, then flushes Jahia's role-privilege caches so the
// next permission check sees the new list. Roles exist in the edit workspace only (jnt:role is jmix:nolive).
// j:permissionNames is protected: only a system session can write it.
// Replacements: ROLE_PLACEHOLDER (the role node name under /roles),
//               NAMES_PLACEHOLDER (permission names separated by spaces, as in roles.xml).
// Result: "SET=<the names written, separated by spaces>", or "SCRIPT_ERROR=..." on failure.
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate
import org.jahia.utils.security.AccessManagerUtils

def result
try {
    String[] names = "NAMES_PLACEHOLDER".trim().split("\\s+")
    JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, "default", null, new JCRCallback<Object>() {
        Object doInJCR(JCRSessionWrapper session) {
            def role = session.getNode("/roles/ROLE_PLACEHOLDER")
            role.setProperty("j:permissionNames", names)
            session.save()
            return null
        }
    })
    AccessManagerUtils.flushPrivilegesInRoles()
    AccessManagerUtils.flushMatchingPermissions()
    result = "SET=" + names.join(" ")
} catch (Throwable t) {
    result = "SCRIPT_ERROR=" + t.getClass().getName() + ": " + t.getMessage()
}
setResult(result)
