package com.ooohyg.agent.workflow;

import com.ooohyg.agent.strategy.plan.PlanStep;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class RunStore {
    enum Mode { LIGHT, FULL }
    enum Phase { CLARIFY, INTENT, SPEC, PLAN, READY, EXECUTING, NEEDS_INSPECTION, FINISHED, READ_ONLY }
    record Approval(Phase phase, String version, String at) {}
    record State(Mode mode, Phase phase, String commit, String request, String scope,
                 boolean withWiki, Integer cap, List<PlanStep> steps, List<Approval> approvals,
                 String executionBaseline, boolean dsh, String model) {
        State next(Phase phase, Integer cap, List<PlanStep> steps, List<Approval> approvals) {
            return new State(mode, phase, commit, request, scope, withWiki, cap, steps, approvals,
                    executionBaseline, dsh, model);
        }

        List<Approval> approve(String version) {
            var updated = new ArrayList<>(approvals);
            updated.add(new Approval(phase, version, Instant.now().toString()));
            return updated;
        }
    }

    @FunctionalInterface
    interface Action { void run(State state) throws Exception; }

    final Path dir;

    private RunStore(Path dir) {
        this.dir = dir;
    }

    static RunStore create(Path home) throws IOException {
        Path runs = Files.createDirectories(home.resolve("runs"));
        return new RunStore(Files.createDirectory(runs.resolve(UUID.randomUUID().toString())));
    }

    static RunStore open(Path home, String id) throws IOException {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid run ID");
        Path dir = home.resolve("runs").resolve(id);
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) throw new IOException("Run not found");
        return new RunStore(dir);
    }

    void locked(Action action) throws Exception {
        try (var channel = FileChannel.open(dir.resolve(".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Run is busy");
            State state = WorkflowFiles.JSON.readValue(dir.resolve("run.json").toFile(), State.class);
            action.run(state);
        }
    }

    void save(State state) throws IOException {
        WorkflowFiles.json(dir.resolve("run.json"), state);
    }

    String draft(Phase phase, String markdown) throws IOException {
        Path current = document(phase);
        String version = WorkflowFiles.hash(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path history = Files.createDirectories(dir.resolve("documents"));
        WorkflowFiles.write(history.resolve(current.getFileName() + "-" + version), markdown);
        WorkflowFiles.write(current, markdown);
        return version;
    }

    Path document(Phase phase) {
        return dir.resolve(switch (phase) {
            case INTENT -> "intent.md";
            case SPEC -> "spec.md";
            case PLAN -> "plan.md";
            default -> throw new IllegalArgumentException("No document for " + phase);
        });
    }

    String confirm(Phase phase, String version) throws IOException {
        if (!version.matches("[0-9a-f]{64}") || !version.equals(WorkflowFiles.hash(document(phase)))
                || !version.equals(WorkflowFiles.hash(dir.resolve("documents")
                        .resolve(document(phase).getFileName() + "-" + version)))) {
            throw new IOException("Document changed or token is invalid; use status to see the current draft");
        }
        return version;
    }
}
