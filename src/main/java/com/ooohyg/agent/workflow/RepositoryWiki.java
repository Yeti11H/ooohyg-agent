package com.ooohyg.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class RepositoryWiki {
    record Rule(String ruleId, String repository, int cap, String source, String sourceSha256,
                String verifiedCommit, String scope, String decisionRun, String approvedSpecSha256) {}
    record Match(String status, Rule rule) {}

    static void publish(Path home, RunStore store, RunStore.State run, String version) throws IOException {
        if (run.phase() != RunStore.Phase.FINISHED || !OrderFixture.LIST.equals(run.scope())) {
            throw new IOException("Only a finished order-list run can propose this rule");
        }
        Path proposal = store.dir.resolve("wiki-proposal.json");
        if (!Files.isRegularFile(proposal) || !WorkflowFiles.hash(proposal).equals(version)) {
            throw new IOException("Inspect wiki-proposal.json and pass its displayed approval token");
        }
        Rule rule = WorkflowFiles.JSON.readValue(proposal.toFile(), Rule.class);
        Path dir = Files.createDirectories(directory(home, rule.repository()));
        Path index = dir.resolve("order-cap.json");
        if (Files.exists(index)) {
            Rule current = WorkflowFiles.JSON.readValue(index.toFile(), Rule.class);
            if (current.cap() != rule.cap()) throw new IOException("Wiki already contains a different cap; resolve the conflict before publishing");
        }
        WorkflowFiles.write(dir.resolve("order-cap.md"),
                "# " + rule.ruleId() + "\n\nInteractive order queries: clamp the requested limit to "
                        + rule.cap() + ". Excludes bulk exports.\n\nScope: " + rule.scope()
                        + "\n\nSource: `" + rule.source() + "` at `" + rule.verifiedCommit()
                        + "`\n\nSource SHA-256: `" + rule.sourceSha256() + "`\n\nDecision: approved Spec in run `"
                        + rule.decisionRun() + "`, SHA-256 `" + rule.approvedSpecSha256()
                        + "`.\n\nStatus: human confirmed.\n");
        WorkflowFiles.json(index, rule);
    }

    static Rule propose(Path source, String commit, int cap, String specVersion, String runId) throws IOException {
        return new Rule("ORDER-INTERACTIVE-CAP", OrderFixture.ID, cap, OrderFixture.PROVENANCE,
                WorkflowFiles.hash(RepositoryWorkspace.scopedFile(source, OrderFixture.PROVENANCE)),
                commit, "interactive-orders", runId, specVersion);
    }

    static Match find(Path home, String repository, Path source, String scope) throws IOException {
        Path index = directory(home, repository).resolve("order-cap.json");
        if (!Files.isRegularFile(index)) return new Match("missing", null);
        Rule rule = WorkflowFiles.JSON.readValue(index.toFile(), Rule.class);
        if (!rule.repository().equals(repository)) return new Match("different_repository", null);
        if (!"interactive-orders".equals(rule.scope())
                || !(OrderFixture.HISTORY.equals(scope) || OrderFixture.LIST.equals(scope))) {
            return new Match("out_of_scope", null);
        }
        try {
            if (!WorkflowFiles.hash(RepositoryWorkspace.scopedFile(source, rule.source())).equals(rule.sourceSha256())) {
                return new Match("stale_source", null);
            }
        } catch (IOException e) {
            return new Match("stale_source", null);
        }
        return new Match("matched", rule);
    }

    private static Path directory(Path home, String repository) {
        String key = WorkflowFiles.hash(repository.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return home.resolve("wiki").resolve(key);
    }
}
