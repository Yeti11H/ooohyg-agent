package com.ooohyg.agent.workflow;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.Decision;
import com.ooohyg.agent.core.capability.tool.DefaultToolRegistry;
import com.ooohyg.agent.core.execution.DefaultAgentContext;
import com.ooohyg.agent.core.execution.ExecutionId;
import com.ooohyg.agent.core.message.MessageRole;
import com.ooohyg.agent.core.message.ToolCall;
import com.ooohyg.agent.strategy.plan.PlanAndExecuteStrategy;
import com.ooohyg.agent.strategy.plan.PlanStep;
import com.ooohyg.agent.strategy.react.ReactStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.ooohyg.agent.workflow.RunStore.Mode;
import static com.ooohyg.agent.workflow.RunStore.Phase;
import static com.ooohyg.agent.workflow.RunStore.State;

final class DevelopmentWorkflow {
    record Draft(Phase phase, String token) {}
    record Round(int number, String patchSha256, String candidate, OrderFixture.Checks checks,
                 DshReviewRunner.Review review, String repairReason) {}

    private final Path home;
    private final RunStore store;
    private final Function<String, LlmClient> modelClient;

    DevelopmentWorkflow(Path home, RunStore store) {
        this(home, store, CompatibleChatClient::new);
    }

    DevelopmentWorkflow(Path home, RunStore store, Function<String, LlmClient> modelClient) {
        this.home = home;
        this.store = store;
        this.modelClient = modelClient;
    }

    Draft initialize(Mode mode, String request, String scope, boolean wiki, boolean dsh, String model)
            throws IOException, InterruptedException {
        if (request.isBlank() || request.length() > 2_000) throw new IllegalArgumentException("Enter a request up to 2000 characters");
        if (!List.of(OrderFixture.LIST, OrderFixture.HISTORY, OrderFixture.EXPORT).contains(scope)) {
            throw new IllegalArgumentException("Unknown demo scope");
        }
        String commit = OrderFixture.initialize(store.dir, scope);
        var match = wiki ? RepositoryWiki.find(home, OrderFixture.ID, source(), scope)
                : new RepositoryWiki.Match("disabled", null);
        WorkflowFiles.json(store.dir.resolve("wiki-match.json"), match);
        Phase initialPhase = OrderFixture.EXPORT.equals(scope) ? Phase.READ_ONLY : Phase.CLARIFY;
        var state = new State(mode, initialPhase, commit, request, scope, wiki,
                match.rule() == null ? null : match.rule().cap(), List.of(), List.of(),
                OrderFixture.baselineHash(scope), dsh, model);
        store.save(state);
        if (initialPhase == Phase.READ_ONLY) return new Draft(initialPhase, null);
        return mode == Mode.FULL ? intent(state) : state.cap() == null ? new Draft(Phase.CLARIFY, null) : plan(state);
    }

    Draft answer(State state, int cap) throws IOException {
        if (cap < 21 || cap > 70) throw new IllegalArgumentException("Demo cap must be 21–70");
        if (OrderFixture.EXPORT.equals(state.scope())) throw new IOException("Exports are out of scope for pagination");
        if (!List.of(Phase.CLARIFY, Phase.INTENT, Phase.SPEC, Phase.PLAN, Phase.READY).contains(state.phase())) {
            throw new IOException("Cannot change acceptance after execution starts");
        }
        var approvals = state.approvals().stream().filter(a -> a.phase() == Phase.INTENT).toList();
        var changed = state.next(state.phase(), cap, List.of(), approvals);
        if (state.mode() == Mode.LIGHT) return plan(changed);
        if (approvals.isEmpty()) return intent(changed);
        return spec(changed);
    }

    Draft confirm(State state, String token) throws IOException {
        if (!List.of(Phase.INTENT, Phase.SPEC, Phase.PLAN).contains(state.phase())) {
            throw new IOException("There is no pending document at " + state.phase());
        }
        if (state.phase() == Phase.INTENT && state.cap() == null) {
            throw new IOException("Clarify the pagination cap with answer before confirming Intent");
        }
        List<RunStore.Approval> approved = state.approve(store.confirm(state.phase(), token));
        State next = state.next(state.phase(), state.cap(), state.steps(), approved);
        return switch (state.phase()) {
            case INTENT -> spec(next);
            case SPEC -> plan(next);
            case PLAN -> {
                store.save(next.next(Phase.READY, next.cap(), next.steps(), next.approvals()));
                yield new Draft(Phase.READY, null);
            }
            default -> throw new IllegalStateException("Unexpected approval stage");
        };
    }

    private Draft intent(State state) throws IOException {
        return saveDraft(state, Phase.INTENT, "# Intent\n\nRequest: " + state.request()
                + "\n\n**Preset exercise:** pagination for `" + state.scope()
                + "`. If this does not match the request, stop here.\n\nClarification: how should a limit above the maximum behave?"
                + "\n\nAnswer: " + (state.cap() == null ? "Unresolved" : "Clamp to " + state.cap()) + "\n");
    }

    private Draft spec(State state) throws IOException {
        return saveDraft(state, Phase.SPEC, "# Spec\n\n" + acceptance(state) + wikiUsage(state));
    }

    private Draft plan(State state) throws IOException {
        List<PlanStep> steps = List.of(PlanStep.pending(0, "Update only " + state.scope() + " in "
                + OrderFixture.SOURCE + "; implement AC-01, AC-02, AC-03, preserve AC-04. "
                + "Harness checks default, below/at/above limit, non-positive limits, sorting and unchanged export."));
        var planned = state.next(state.phase(), state.cap(), steps, state.approvals());
        return saveDraft(planned, Phase.PLAN, "# Plan\n\nRequest: " + state.request()
                + "\n\nPinned commit: `" + state.commit() + "`\n\n" + acceptance(state)
                + "\n1. " + steps.get(0).description() + "\n\nExecution model: `" + state.model()
                + "`.\n\n" + (state.mode() == Mode.FULL && state.model().equals("scripted")
                    ? "First round injects a known missing-cap patch for regression demonstration.\n" : "")
                + wikiUsage(state));
    }

    private Draft saveDraft(State state, Phase phase, String text) throws IOException {
        String token = store.draft(phase, text);
        store.save(state.next(phase, state.cap(), state.steps(), state.approvals()));
        return new Draft(phase, token);
    }

    private String wikiUsage(State state) throws IOException {
        var match = WorkflowFiles.JSON.readValue(store.dir.resolve("wiki-match.json").toFile(), RepositoryWiki.Match.class);
        if (match.rule() == null) return "\nWiki: " + match.status() + ".\n";
        var rule = match.rule();
        return "\nWiki: " + rule.ruleId() + " applies to the interactive query scope. Source `"
                + rule.source() + "` at `" + rule.verifiedCommit() + "`; approved Spec in run `"
                + rule.decisionRun() + "`."
                + (rule.cap() == state.cap() ? " Used for cap " + rule.cap() + "."
                    : " Conflict: confirmed task cap " + state.cap() + " overrides wiki cap " + rule.cap()
                            + " for this task; wiki remains unchanged.") + "\n";
    }

    private static String acceptance(State state) {
        return """
                - AC-01: With no limit, return 20 orders when enough are available.
                - AC-02: Honor positive limits below and at %d; clamp higher limits to %d. Zero and negative limits return no orders.
                - AC-03: Preserve ascending ID order.
                - AC-04: Bulk exports remain unchanged.
                """.formatted(state.cap(), state.cap());
    }

    void execute(State state) throws IOException, InterruptedException {
        if (state.phase() == Phase.EXECUTING) {
            String observed = WorkflowFiles.hash(RepositoryWorkspace.scopedFile(source(), OrderFixture.SOURCE));
            store.save(state.next(Phase.NEEDS_INSPECTION, state.cap(), state.steps(), state.approvals()));
            WorkflowFiles.write(store.dir.resolve("interrupted.md"), "# Interrupted execution\n\nApproved baseline source: `"
                    + state.executionBaseline() + "`\n\nObserved source: `" + observed
                    + "`\n\nNo writes were replayed. Inspect retained source and artifacts; start a fresh run to retry.\n");
            throw new IOException("Previous execution was interrupted; inspect interrupted.md");
        }
        if (state.phase() != Phase.READY) throw new IOException("Confirm the required documents before execution");
        for (Phase phase : state.mode() == Mode.FULL ? List.of(Phase.INTENT, Phase.SPEC, Phase.PLAN) : List.of(Phase.PLAN)) {
            var approved = state.approvals().stream().filter(a -> a.phase() == phase).findFirst()
                    .orElseThrow(() -> new IOException("Missing " + phase + " approval"));
            store.confirm(phase, approved.version());
        }
        Path gitLog = store.dir.resolve("git.txt");
        RepositoryWorkspace.git(source(), gitLog, "rev-parse", "HEAD");
        if (!state.commit().equals(Files.readString(gitLog).strip())) throw new IOException("Baseline commit changed");
        RepositoryWorkspace.git(source(), gitLog, "status", "--porcelain=v1", "--untracked-files=all");
        if (!Files.readString(gitLog).isBlank()
                || !state.executionBaseline().equals(WorkflowFiles.hash(
                        RepositoryWorkspace.scopedFile(source(), OrderFixture.SOURCE)))) {
            throw new IOException("Baseline workspace changed");
        }
        var edit = new OrderFixture.ScopedEdit(source(), state.scope());
        LlmClient remote = state.model().equals("scripted") ? null : modelClient.apply(state.model());
        store.save(state.next(Phase.EXECUTING, state.cap(), state.steps(), state.approvals()));
        List<Round> rounds = new ArrayList<>();
        String repairReason = null;
        for (int round = 0; round <= 2; round++) {
            boolean knownBad = round == 0 && remote == null && state.mode() == Mode.FULL;
            var result = strategy(state, remote, knownBad, edit).executePlan(new DefaultAgentContext(
                            ExecutionId.generate(), acceptance(state)
                                    + (repairReason == null ? "" : "\nRepair only: " + repairReason), 8),
                    round == 0 ? state.steps() : List.of(PlanStep.pending(0, repairReason)));
            if (!result.success()) throw new IOException("Execution strategy failed; inspect retained source");
            edit.requireExpected(edit.target());
            RepositoryWorkspace.git(source(), gitLog, "status", "--porcelain=v1", "--untracked-files=all");
            if (!Files.readAllLines(gitLog).equals(List.of(" M " + OrderFixture.SOURCE))) {
                throw new IOException("Expected a change only to the approved source file; inspect retained source");
            }
            Path patch = store.dir.resolve("patch-" + round + ".diff");
            RepositoryWorkspace.git(source(), patch, "diff", "--binary", "HEAD", "--");
            byte[] bytes = Files.readAllBytes(patch);
            if (bytes.length == 0) throw new IOException("No patch produced; inspect retained source");
            String hash = WorkflowFiles.hash(bytes);
            if (!rounds.isEmpty() && hash.equals(rounds.get(rounds.size() - 1).patchSha256())) break;
            var check = OrderFixture.checks(store.dir, state.scope(), state.cap(), round, edit);
            Path snapshot = store.dir.resolve("candidate-" + round);
            RepositoryWorkspace.git(source(), gitLog, "-c", "protocol.file.allow=always",
                    "clone", "--local", "--no-hardlinks", "--no-checkout",
                    "--", source().toString(), snapshot.toString());
            RepositoryWorkspace.git(snapshot, gitLog, "checkout", "--detach", state.commit());
            RepositoryWorkspace.git(snapshot, gitLog, "apply", "--index", patch.toString());
            String checkedSource = Files.readString(store.dir.resolve("checks-" + round).resolve("OrderQueries.java"));
            if (!checkedSource.equals(Files.readString(snapshot.resolve(OrderFixture.SOURCE)))) {
                throw new IOException("Applied patch does not match checked source");
            }
            RepositoryWorkspace.git(snapshot, gitLog, "commit", "-m", "Candidate round " + round);
            RepositoryWorkspace.git(snapshot, gitLog, "rev-parse", "HEAD");
            String candidate = Files.readString(gitLog).strip();
            var review = DshReviewRunner.review(store.dir, state.dsh(), round, state.commit(), candidate,
                    snapshot, checkedSource, state.scope(), state.cap());
            rounds.add(new Round(round, hash, candidate, check, review, repairReason));
            WorkflowFiles.write(store.dir.resolve("patch.diff"), bytes);
            WorkflowFiles.json(store.dir.resolve("checks.json"), rounds);
            AcceptanceReport.write(store.dir, state, rounds);
            // The fixture has one verified repair: restore the confirmed maximum after a failed above-cap check.
            if (state.mode() == Mode.LIGHT
                    || check.assertions().stream().noneMatch(c -> c.id().equals("AC-02-above") && c.status().equals("failed"))
                    || check.assertions().stream().anyMatch(c -> !c.id().equals("AC-02-above") && !c.status().equals("passed"))) {
                break;
            }
            repairReason = "AC-02-above failed: cap the requested limit at " + state.cap() + " for " + state.scope();
        }
        if (!rounds.isEmpty() && rounds.get(rounds.size() - 1).checks().passed()
                && OrderFixture.LIST.equals(state.scope()) && state.mode() == Mode.FULL) {
            String specVersion = state.approvals().stream().filter(a -> a.phase() == Phase.SPEC)
                    .findFirst().orElseThrow().version();
            WorkflowFiles.json(store.dir.resolve("wiki-proposal.json"),
                    RepositoryWiki.propose(source(), state.commit(), state.cap(), specVersion, store.dir.getFileName().toString()));
        }
        store.save(state.next(Phase.FINISHED, state.cap(), state.steps(), state.approvals()));
    }

    private PlanAndExecuteStrategy strategy(State state, LlmClient remote, boolean knownBad, OrderFixture.ScopedEdit edit) {
        LlmClient worker = remote == null ? (messages, tools) -> {
            long calls = messages.stream().filter(m -> m.role() == MessageRole.TOOL).count();
            if (calls == 0) return call("read_order_query", "{}");
            if (calls == 1) return call("edit_order_query", "{\"defaultLimit\":20,\"limitExpression\":\""
                    + (knownBad ? "Math.max(0, limit)" : "Math.max(0, Math.min(" + state.cap() + ", limit))") + "\"}");
            return new LlmResponse("Fixture tool finished; acceptance is determined by actual checks.", null, "stop");
        } : remote;
        var react = new ReactStrategy(worker, new DefaultToolRegistry(List.of(OrderFixture.readTool(source()), edit)),
                "Read the approved source, then submit the integer null default and the non-null limit expression "
                        + "through edit_order_query. The harness checks actual output independently.",
                (tool, args, callId) -> Decision.ALLOW);
        return new PlanAndExecuteStrategy((m, t) -> { throw new IllegalStateException("Approved run cannot replan"); },
                text -> List.of(), react);
    }

    private static LlmResponse call(String name, String arguments) {
        return new LlmResponse(null, List.of(new ToolCall(name + "-1", name, arguments)), "tool_calls");
    }

    private Path source() { return store.dir.resolve("source"); }
}
