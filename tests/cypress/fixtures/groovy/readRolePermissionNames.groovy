// Reads the j:permissionNames of a role in the edit workspace (the list Jahia's access manager reads first).
// Replacements: ROLE_PLACEHOLDER (the role node name under /roles).
// Result: "NAMES=<names separated by spaces>", "NO_PERMISSION_NAMES" when the role has no such property,
//         or "SCRIPT_ERROR=..." on failure.
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate

def result
try {
    result = JCRTemplate.getInstance().doExecuteWithSystemSessionAsUser(null, "default", null, new JCRCallback<String>() {
        String doInJCR(JCRSessionWrapper session) {
            def role = session.getNode("/roles/ROLE_PLACEHOLDER")
            if (!role.hasProperty("j:permissionNames")) {
                return "NO_PERMISSION_NAMES"
            }
            return "NAMES=" + role.getProperty("j:permissionNames").getValues().collect { it.getString() }.join(" ")
        }
    })
} catch (Throwable t) {
    result = "SCRIPT_ERROR=" + t.getClass().getName() + ": " + t.getMessage()
}
setResult(result)
