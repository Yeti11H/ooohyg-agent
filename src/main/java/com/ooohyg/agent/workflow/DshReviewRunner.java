package com.ooohyg.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class DshReviewRunner {
    record Review(String status, Integer exitCode, String reason, String report, String reportSha256,
                  List<String> openIssueIds) {}

    static Review review(Path run, boolean enabled, int round, String base, String candidate,
                         Path snapshot, String expectedSource, String scope, int cap)
            throws InterruptedException {
        if (!enabled) return new Review("not_run", null, "DSH was not requested", null, null, List.of());
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            return new Review("not_run", null, "DEEPSEEK_API_KEY is missing", null, null, List.of());
        }
        Path report = run.resolve("review-" + round + ".json");
        Path stdout = run.resolve("review-" + round + ".out");
        Path stderr = run.resolve("review-" + round + ".err");
        List<String> command = new ArrayList<>();
        String script = System.getenv("WORKFLOW_DSH_SCRIPT");
        if (script != null && !script.isBlank()) {
            Path trusted = Path.of(script).toAbsolutePath();
            if (!Files.isRegularFile(trusted)) return incomplete(null, "Configured DSH script does not exist");
            command.addAll(List.of("node", trusted.toString()));
        } else {
            if (System.getProperty("os.name").startsWith("Windows")) {
                return incomplete(null, "Set WORKFLOW_DSH_SCRIPT to the installed DSH CLI .mjs entry");
            }
            command.add("dsh-review");
        }
        command.addAll(List.of("--cwd", snapshot.toString(), "--target", base,
                "--requirement", "Paginate " + scope + ": default 20; clamp above " + cap
                        + "; return no orders for non-positive limits; preserve ascending sort and do not change exports.",
                "--business-rules", "Bulk export remains unpaginated",
                "--output-json", report.toString(), "--exit-code", "--fail-on-needs-check"));
        try {
            var invoked = LocalProcess.run(run, command, stdout, stderr, Duration.ofSeconds(90),
                    Map.of("DEEPSEEK_API_KEY", key), null);
            if (invoked.limited()) return incomplete(null, "DSH exceeded time or output limits");
            return parse(report, invoked.exit(), base, candidate, expectedSource);
        } catch (IOException e) {
            return incomplete(null, "DSH unavailable or report unreadable");
        }
    }

    static Review parse(Path report, int exit, String base, String candidate, String expectedSource)
            throws IOException {
        if (exit != 0 && exit != 1) return incomplete(exit, "DSH reported an incomplete review");
        if (!Files.isRegularFile(report) || Files.size(report) > 2 * 1024 * 1024) {
            return incomplete(exit, "DSH report missing or oversized");
        }
        byte[] bytes = Files.readAllBytes(report);
        JsonNode parsed;
        try {
            parsed = WorkflowFiles.JSON.readTree(bytes);
        } catch (IOException e) {
            return incomplete(exit, "Malformed DSH report");
        }
        if (parsed == null) return incomplete(exit, "Empty DSH report");
        JsonNode snapshot = parsed.path("snapshot");
        JsonNode rounds = parsed.path("rounds");
        JsonNode last = rounds.path(rounds.size() - 1);
        if (parsed.path("schemaVersion").asInt() != 1
                || !parsed.path("status").asText().equals("completed")
                || !parsed.path("stale").isBoolean() || parsed.path("stale").asBoolean()
                || !snapshot.path("base").asText().equals(base)
                || !snapshot.path("head").asText().equals(candidate)
                || !snapshot.path("sources").path(OrderFixture.SOURCE).asText().equals(expectedSource)
                || snapshot.path("hash").asText().isBlank()
                || !last.path("codeHash").asText().equals(snapshot.path("hash").asText())
                || !last.path("issues").isArray()) {
            return incomplete(exit, "DSH report does not match the candidate snapshot");
        }
        List<String> findings = new ArrayList<>();
        for (JsonNode issue : last.path("issues")) {
            if (issue.path("status").asText().equals("open")
                    && !issue.path("verdict").asText().equals("false_positive")) {
                findings.add(issue.path("id").asText("(missing ID)"));
            }
        }
        if (exit == 1 && findings.isEmpty()) return incomplete(exit, "DSH gate triggered without a recorded finding");
        return new Review(exit == 0 && findings.isEmpty() ? "completed" : "findings", exit,
                exit == 0 && findings.isEmpty() ? "No open findings reported" : "Open findings require review",
                report.getFileName().toString(), WorkflowFiles.hash(bytes), List.copyOf(findings));
    }

    private static Review incomplete(Integer exit, String reason) {
        return new Review("incomplete", exit, reason, null, null, List.of());
    }
}
