package com.ooohyg.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class WorkflowCli {
    private WorkflowCli() {}

    public static int run(String[] args) {
        try {
            dispatch(args);
            return 0;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Workflow: " + e.getMessage());
            return 1;
        }
    }

    private static void dispatch(String[] args) throws Exception {
        if (args.length == 0 || List.of("help", "--help").contains(args[0])) {
            System.out.println("""
                    workflow start <light|full> <order-list|order-history|export> "<request>" [--wiki] [--dsh] [--model=NAME]
                    workflow status <run>
                    workflow answer <run> <maximum-page-size>
                    workflow confirm <run> <document-token>
                    workflow execute <run>
                    workflow wiki-publish <run> <proposal-token>
                    workflow import <https://github.com/OWNER/REPO>
                    workflow read <import> <repository-relative-file>
                    Set WORKFLOW_HOME to choose the local artifact directory (default: .agent-workflow).
                    """);
            return;
        }
        String configuredHome = System.getenv("WORKFLOW_HOME");
        Path home = Path.of(configuredHome == null || configuredHome.isBlank() ? ".agent-workflow" : configuredHome)
                .toAbsolutePath().normalize();
        switch (args[0]) {
            case "start" -> {
                if (args.length < 4) throw new IllegalArgumentException("start requires mode, scope and request");
                var flags = Arrays.asList(args).subList(4, args.length);
                if (flags.size() != new HashSet<>(flags).size() || flags.stream()
                        .anyMatch(f -> !(f.equals("--wiki") || f.equals("--dsh") || f.matches("--model=[A-Za-z0-9_./:-]{1,120}")))) {
                    throw new IllegalArgumentException("Invalid or repeated start option");
                }
                var models = flags.stream().filter(f -> f.startsWith("--model=")).toList();
                if (models.size() > 1 || models.contains("--model=scripted")) {
                    throw new IllegalArgumentException("Specify one provider model, or omit --model for scripted mode");
                }
                var mode = RunStore.Mode.valueOf(args[1].toUpperCase(Locale.ROOT));
                var store = RunStore.create(home);
                var workflow = new DevelopmentWorkflow(home, store);
                System.out.println("Run: " + store.dir.getFileName() + "\nArtifacts: " + store.dir);
                printDraft(store, workflow.initialize(mode, args[3], args[2], flags.contains("--wiki"),
                        flags.contains("--dsh"), models.isEmpty() ? "scripted" : models.get(0).substring(8)));
            }
            case "import" -> {
                argc(args, 2);
                RepositoryWorkspace.githubUrl(args[1]);
                Path folder = Files.createDirectory(Files.createDirectories(home.resolve("imports"))
                        .resolve(UUID.randomUUID().toString()));
                System.out.println("Import: " + folder.getFileName() + "\nArtifacts: " + folder);
                var imported = RepositoryWorkspace.importGithub(folder, args[1]);
                WorkflowFiles.json(folder.resolve("repository.json"), imported);
                System.out.println(imported.repository() + " @ " + imported.commit() + "\nBuild: "
                        + imported.build() + "\nExecution: " + imported.execution());
            }
            case "read" -> {
                argc(args, 3);
                if (!UUID.fromString(args[1]).toString().equals(args[1])) throw new IllegalArgumentException("Invalid import ID");
                Path folder = home.resolve("imports").resolve(args[1]);
                var imported = WorkflowFiles.JSON.readValue(folder.resolve("repository.json").toFile(),
                        RepositoryWorkspace.Import.class);
                Path content = Files.createTempFile(folder, ".read-", ".txt");
                try {
                    RepositoryWorkspace.readImported(folder, imported, args[2], content);
                    System.out.print(Files.readString(content));
                } finally {
                    Files.deleteIfExists(content);
                    Files.deleteIfExists(content.resolveSibling(content.getFileName() + ".stderr"));
                }
            }
            case "status", "answer", "confirm", "execute", "wiki-publish" -> {
                argc(args, List.of("answer", "confirm", "wiki-publish").contains(args[0]) ? 3 : 2);
                var store = RunStore.open(home, args[1]);
                var workflow = new DevelopmentWorkflow(home, store);
                store.locked(state -> {
                    switch (args[0]) {
                        case "status" -> status(store, state);
                        case "answer" -> printDraft(store, workflow.answer(state, Integer.parseInt(args[2])));
                        case "confirm" -> printDraft(store, workflow.confirm(state, args[2]));
                        case "execute" -> {
                            workflow.execute(state);
                            System.out.println("Report: " + store.dir.resolve("acceptance.md"));
                            printProposal(store);
                        }
                        case "wiki-publish" -> {
                            RepositoryWiki.publish(home, store, state, args[2]);
                            System.out.println("Published human-confirmed local Wiki rule.");
                        }
                        default -> throw new IllegalStateException();
                    }
                });
            }
            default -> throw new IllegalArgumentException("Unknown command; run workflow help");
        }
    }

    private static void status(RunStore store, RunStore.State state) throws IOException {
        System.out.println("Run: " + store.dir.getFileName() + "\nPinned commit: " + state.commit());
        var phase = state.phase();
        String token = switch (phase) {
            case INTENT, SPEC, PLAN -> WorkflowFiles.hash(store.document(phase));
            default -> null;
        };
        printDraft(store, new DevelopmentWorkflow.Draft(phase, token));
        Path wiki = store.dir.resolve("wiki-match.json");
        if (Files.isRegularFile(wiki)) System.out.println("Wiki: " + Files.readString(wiki));
        if (Files.isRegularFile(store.dir.resolve("acceptance.md"))) {
            System.out.println("Report: " + store.dir.resolve("acceptance.md"));
        }
        printProposal(store);
    }

    private static void printDraft(RunStore store, DevelopmentWorkflow.Draft draft) throws IOException {
        System.out.println("Phase: " + draft.phase());
        if (draft.token() != null) {
            System.out.println(Files.readString(store.document(draft.phase())));
            System.out.println("Confirmation token: " + draft.token());
        } else if (draft.phase() == RunStore.Phase.CLARIFY) {
            System.out.println("Use answer to set a demo page limit.");
        } else if (draft.phase() == RunStore.Phase.READ_ONLY) {
            System.out.println("Bulk export is outside the pagination scope. No patch will be produced.");
        }
    }

    private static void printProposal(RunStore store) throws IOException {
        Path proposal = store.dir.resolve("wiki-proposal.json");
        if (Files.isRegularFile(proposal)) {
            System.out.println("Wiki proposal: " + proposal + "\nWiki approval token: " + WorkflowFiles.hash(proposal));
        }
    }

    private static void argc(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("Wrong argument count; run workflow help");
    }
}
