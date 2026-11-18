package com.ooohyg.agent.workflow;

import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.message.MessageRole;
import com.ooohyg.agent.core.message.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.ooohyg.agent.workflow.RunStore.*;
import static org.junit.jupiter.api.Assertions.*;

class DevelopmentWorkflowTest {
    @TempDir Path home;

    @Test
    void fullReplayRequiresApprovalsRepairsBoundaryAndPublishesScopedWiki() throws Exception {
        var store = RunStore.create(home);
        var workflow = new DevelopmentWorkflow(home, store);
        var draft = workflow.initialize(Mode.FULL, "Improve the order list", OrderFixture.LIST, false, false, "scripted");
        assertEquals(Phase.INTENT, draft.phase());
        store.locked(state -> {
            assertThrows(IOException.class, () -> workflow.execute(state));
            assertThrows(IOException.class, () -> workflow.confirm(state, draft.token()));
            workflow.answer(state, 50);
        });
        confirm(workflow, store, Phase.INTENT);
        confirm(workflow, store, Phase.SPEC);
        String obsolete = WorkflowFiles.hash(store.document(Phase.PLAN));
        store.locked(state -> workflow.answer(state, 55));
        store.locked(state -> {
            assertEquals(Phase.SPEC, state.phase());
            assertThrows(IOException.class, () -> workflow.confirm(state, obsolete));
            workflow.answer(state, 50);
        });
        confirm(workflow, store, Phase.SPEC);
        confirm(workflow, store, Phase.PLAN);
        store.locked(workflow::execute);
        var rounds = WorkflowFiles.JSON.readTree(store.dir.resolve("checks.json").toFile());
        assertEquals(2, rounds.size());
        assertTrue(Files.readString(store.dir.resolve("checks-0/old-sort.out")).contains("passed"));
        assertTrue(Files.readString(store.dir.resolve("checks-0/AC-02-above.err")).contains("AssertionError"));
        for (String check : new String[]{"AC-01", "AC-02-below", "AC-02-at", "AC-02-above", "AC-02-nonpositive", "AC-03", "AC-04"}) {
            assertTrue(Files.readString(store.dir.resolve("checks-1/" + check + ".out")).contains("passed"));
        }
        assertTrue(Files.readString(store.dir.resolve("acceptance.md")).contains("Acceptance incomplete"));
        assertTrue(Files.readString(store.dir.resolve("patch.diff")).contains("Math.min(50, limit)"));
        store.locked(state -> RepositoryWiki.publish(home, store, state,
                WorkflowFiles.hash(store.dir.resolve("wiki-proposal.json"))));

        var second = RunStore.create(home);
        var secondWorkflow = new DevelopmentWorkflow(home, second);
        assertEquals(Phase.PLAN, secondWorkflow.initialize(Mode.LIGHT, "Paginate order history",
                OrderFixture.HISTORY, true, false, "scripted").phase());
        var withoutWiki = RunStore.create(home);
        assertEquals(Phase.CLARIFY, new DevelopmentWorkflow(home, withoutWiki).initialize(Mode.LIGHT,
                "Paginate order history", OrderFixture.HISTORY, false, false, "scripted").phase());
        second.locked(on -> withoutWiki.locked(off -> {
            assertEquals(on.commit(), off.commit());
            assertEquals(50, on.cap());
            assertNull(off.cap());
        }));
        assertTrue(Files.readString(second.document(Phase.PLAN)).contains("Used for cap 50"));
        confirm(secondWorkflow, second, Phase.PLAN);
        second.locked(secondWorkflow::execute);
        assertTrue(Files.readString(second.dir.resolve("acceptance.md")).contains("Awaiting human acceptance"));
        Path secondSource = second.dir.resolve("source");
        assertEquals("out_of_scope", RepositoryWiki.find(home, OrderFixture.ID, secondSource, OrderFixture.EXPORT).status());
        assertEquals("missing", RepositoryWiki.find(home, "different-repository", secondSource, OrderFixture.HISTORY).status());
        Files.writeString(secondSource.resolve(OrderFixture.PROVENANCE), "Changed module rules");
        assertEquals("stale_source", RepositoryWiki.find(home, OrderFixture.ID, secondSource, OrderFixture.HISTORY).status());
    }

    @Test
    void modelSuppliedExpressionProducesTheCheckedPatchAndCanRepairIt() throws Exception {
        var store = RunStore.create(home);
        var workflow = new DevelopmentWorkflow(home, store, name -> (messages, tools) -> {
            boolean repair = messages.stream().filter(m -> m.role() == MessageRole.USER)
                    .anyMatch(m -> m.content().contains("AC-02-above failed"));
            long calls = messages.stream().filter(m -> m.role() == MessageRole.TOOL).count();
            if (calls == 0) return toolCall("read_order_query", "{}");
            if (calls == 1) return toolCall("edit_order_query", repair
                    ? """
                    {"defaultLimit":20,"limitExpression":"Math.min(Math.max(limit, 0), 50)"}
                    """
                    : """
                    {"defaultLimit":20,"limitExpression":"Math.max(limit, 0)"}
                    """);
            return new LlmResponse("Done", null, "stop");
        });
        workflow.initialize(Mode.FULL, "Paginate the order history", OrderFixture.HISTORY, false, false, "test-model");
        store.locked(state -> workflow.answer(state, 50));
        confirm(workflow, store, Phase.INTENT);
        confirm(workflow, store, Phase.SPEC);
        confirm(workflow, store, Phase.PLAN);
        store.locked(workflow::execute);

        var rounds = WorkflowFiles.JSON.readTree(store.dir.resolve("checks.json").toFile());
        assertEquals(2, rounds.size());
        assertTrue(Files.readString(store.dir.resolve("patch-0.diff")).contains("Math.max(limit, 0)"));
        assertTrue(Files.readString(store.dir.resolve("checks-0/AC-02-above.err")).contains("AssertionError"));
        Path candidate = store.dir.resolve("candidate-1").resolve(OrderFixture.SOURCE);
        assertEquals(Files.readString(candidate), Files.readString(store.dir.resolve("checks-1/OrderQueries.java")));
        String patch = Files.readString(store.dir.resolve("patch.diff"));
        assertTrue(patch.contains("+        return page(ids, limit == null ? 20 : Math.min(Math.max(limit, 0), 50));"));
        assertTrue(patch.contains("@@") && patch.contains("history(List<Integer>"));
        for (var check : rounds.get(1).path("checks").path("assertions")) {
            assertEquals("passed", check.path("status").asText(), check.path("id").asText());
        }
        assertEquals("passed", rounds.get(1).path("checks").path("compilation").path("status").asText());
    }

    @Test
    void editRejectsUnapprovedArgumentsAndExpressionsBeforeWriting() throws Exception {
        var store = RunStore.create(home);
        OrderFixture.initialize(store.dir, OrderFixture.LIST);
        Path source = store.dir.resolve("source");
        var edit = new OrderFixture.ScopedEdit(source, OrderFixture.LIST);
        Path target = source.resolve(OrderFixture.SOURCE);
        String original = Files.readString(target);
        for (String invalid : List.of(
                "null",
                "{\"defaultLimit\":20,\"defaultLimit\":10,\"limitExpression\":\"limit\"}",
                "{\"defaultLimit\":20,\"limitExpression\":\"limit\",\"scope\":\"export\"}",
                "{\"defaultLimit\":20,\"limitExpression\":\"limit\"} {}",
                "{\"defaultLimit\":20,\"limitExpression\":\"limit) + 1\"}",
                "{\"defaultLimit\":20,\"limitExpression\":\"Math.abs(limit)\"}",
                "{\"defaultLimit\":20,\"limitExpression\":\"limit; return 0\"}")) {
            assertFalse(edit.execute(invalid).success(), invalid);
            assertEquals(original, Files.readString(target));
        }
        Files.writeString(target, original + "\n");
        assertFalse(edit.execute("{\"defaultLimit\":20,\"limitExpression\":\"Math.max(limit, 0)\"}").success());
        assertEquals(original + "\n", Files.readString(target));
    }

    @Test
    void interruptedRunDoesNotWriteOrReplay() throws Exception {
        var store = RunStore.create(home);
        var workflow = new DevelopmentWorkflow(home, store);
        workflow.initialize(Mode.LIGHT, "Paginate", OrderFixture.LIST, false, false, "scripted");
        store.locked(state -> workflow.answer(state, 50));
        confirm(workflow, store, Phase.PLAN);
        store.locked(state -> store.save(state.next(Phase.EXECUTING, state.cap(), state.steps(), state.approvals())));
        Path source = store.dir.resolve("source").resolve(OrderFixture.SOURCE);
        String before = WorkflowFiles.hash(source);
        store.locked(state -> assertThrows(IOException.class, () -> workflow.execute(state)));
        store.locked(state -> assertEquals(Phase.NEEDS_INSPECTION, state.phase()));
        assertEquals(before, WorkflowFiles.hash(source));
        assertFalse(Files.exists(store.dir.resolve("patch.diff")));
    }

    @Test
    void dshReportMustMatchCandidateAndExitCode() throws Exception {
        Path report = home.resolve("review.json");
        var session = WorkflowFiles.JSON.createObjectNode();
        session.put("schemaVersion", 1).put("status", "completed").put("stale", false);
        var snapshot = session.putObject("snapshot").put("base", "base").put("head", "candidate").put("hash", "code");
        snapshot.putObject("sources").put(OrderFixture.SOURCE, "candidate source");
        var issues = session.putArray("rounds").addObject().put("codeHash", "code").putArray("issues");
        WorkflowFiles.json(report, session);
        assertEquals("completed", review(report, 0, "candidate"));
        assertEquals("incomplete", review(report, 0, "wrong"));
        assertEquals("incomplete", review(report, 1, "candidate"));
        issues.addObject().put("id", "issue").put("status", "open").put("verdict", "confirmed");
        WorkflowFiles.json(report, session);
        assertEquals("findings", review(report, 1, "candidate"));
        assertEquals("incomplete", review(report, 2, "candidate"));
        Files.writeString(report, "{");
        assertEquals("incomplete", review(report, 0, "candidate"));
    }

    private static String review(Path report, int exit, String candidate) throws IOException {
        return DshReviewRunner.parse(report, exit, "base", candidate, "candidate source").status();
    }

    private static LlmResponse toolCall(String name, String args) {
        return new LlmResponse(null, List.of(new ToolCall(name + "-1", name, args)), "tool_calls");
    }

    private static void confirm(DevelopmentWorkflow workflow, RunStore store, Phase phase) throws Exception {
        store.locked(state -> {
            assertEquals(phase, state.phase());
            workflow.confirm(state, WorkflowFiles.hash(store.document(phase)));
        });
    }
}
