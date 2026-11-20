Review the Jahia module `${module}` version `${version}` (groupId `${groupId}`) before the Jahia Store publishes it.

Archive: ${artifactUrl}
Source repository: ${sourceUrl}

Open the archive (a jar for a Java module, a tgz for a JavaScript module) and read what it ships: the manifest or package.json, the definitions, the views and templates, the Java or JavaScript code, the configuration files, the embedded libraries.

Judge the module on these points, in the terms of a Jahia reviewer:
1. The module declares what it is: a symbolic name, a version that matches `${version}`, a groupId that matches `${groupId}`, the Jahia version it requires, its dependencies.
2. The code follows the conventions of a Jahia module: OSGi declarative services rather than Spring, no deprecated API of Jahia, no hard-coded path of a site, no credential or secret in the archive.
3. The module is honest: what the manifest, the definitions and the resources declare matches the code, and nothing runs at install time that the description does not state.
4. The quality is fit for a store: no debug code, no commented-out blocks, no generated file left behind, no test resource in the archive.

Answer with one JSON object and nothing else:

{"status": "approved" | "rejected" | "review", "comments": ["one finding per entry, each naming the file and what to change"]}

`approved` when nothing blocks the publication, `rejected` when a finding must be fixed first, `review` when a human must look at something you cannot decide. Keep every finding in the comments, the approving ones included.
