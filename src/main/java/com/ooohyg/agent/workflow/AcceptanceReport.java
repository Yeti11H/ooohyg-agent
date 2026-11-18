package com.ooohyg.agent.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

final class AcceptanceReport {
    static void write(Path run, RunStore.State state, List<DevelopmentWorkflow.Round> rounds) throws IOException {
        var last = rounds.get(rounds.size() - 1);
        boolean checksPassed = last.checks().passed();
        boolean reviewRequired = state.mode() == RunStore.Mode.FULL || state.dsh();
        String outcome = checksPassed && (!reviewRequired || last.review().status().equals("completed"))
                ? "Awaiting human acceptance" : "Acceptance incomplete";
        var out = new StringBuilder("# ").append(outcome).append("\n\nMode: ").append(state.mode())
                .append("\n\nExecution: ").append(state.model().equals("scripted") ? "offline scripted fixture" : "configured model: " + state.model())
                .append("\n\nPinned baseline: `").append(state.commit())
                .append("`\n\nFinal patch: `patch.diff` SHA-256 `").append(last.patchSha256())
                .append("`\n\nFinal DSH review: ").append(last.review().status())
                .append(" (").append(last.review().reason()).append(")\n\n");
        for (var round : rounds) {
            out.append("## Round ").append(round.number()).append("\n\nCandidate: `").append(round.candidate())
                    .append("`\n\nPatch SHA-256: `").append(round.patchSha256()).append("`\n\n");
            if (round.repairReason() != null) out.append("Repair: ").append(round.repairReason()).append("\n\n");
            out.append("- Compilation: ").append(round.checks().compilation().status()).append("\n");
            for (var check : round.checks().assertions()) {
                out.append("- ").append(check.id()).append(": ").append(check.assertion())
                        .append(" — ").append(check.status()).append(" (`").append(check.log()).append("`)\n");
            }
            if (round.checks().assertions().isEmpty()) out.append("- Acceptance assertions: not run.\n");
            out.append("- DSH: ").append(round.review().status()).append(" — ").append(round.review().reason()).append("\n");
            if (round.review().report() != null) {
                out.append("- Report: `").append(round.review().report()).append("` SHA-256 `")
                        .append(round.review().reportSha256()).append("`\n");
            }
            if (!round.review().openIssueIds().isEmpty()) {
                out.append("- Open DSH findings require human disposition: ").append(round.review().openIssueIds()).append("\n");
            }
            out.append('\n');
        }
        WorkflowFiles.write(run.resolve("acceptance.md"), out.toString());
    }
}
