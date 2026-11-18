package com.ooohyg.agent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.ooohyg.agent.core.capability.tool.CapabilityKind;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class OrderFixture {
    static final String ID = "builtin/order-pagination";
    static final String SOURCE = "src/main/java/demo/orders/OrderQueries.java";
    static final String PROVENANCE = "docs/order-query.md";
    static final String LIST = "order-list";
    static final String HISTORY = "order-history";
    static final String EXPORT = "export";
    private static final String CHECKS = "OrderAcceptance.java";
    private static final String BASELINE = "return sorted(ids);";
    private static final String UNCAPPED = "return page(ids, limit == null ? 20 : Math.max(0, limit));";
    private static final String SOURCE_TEMPLATE = """
            package demo.orders;
            import java.util.List;
            public final class OrderQueries {
                public static List<Integer> list(List<Integer> ids, Integer limit) {
                    %s
                }
                public static List<Integer> history(List<Integer> ids, Integer limit) {
                    %s
                }
                public static List<Integer> export(List<Integer> ids) {
                    return sorted(ids);
                }
                private static List<Integer> page(List<Integer> ids, int limit) {
                    return sorted(ids).stream().limit(limit).toList();
                }
                private static List<Integer> sorted(List<Integer> ids) {
                    return ids.stream().sorted().toList();
                }
            }
            """;
    private static final String SOURCE_NOTE = """
            # Order query module
            `list` and `history` are interactive order query endpoints.
            `export` is a separate bulk operation and is outside these interactive rules.
            """;
    private static final String ACCEPTANCE = """
            import demo.orders.OrderQueries;
            import java.util.List;
            import java.util.stream.IntStream;
            public class OrderAcceptance {
                public static void main(String[] args) {
                    var ids = IntStream.iterate(80, n -> n - 1).limit(80).boxed().toList();
                    if (args[0].equals("old")) {
                        var sample = List.of(4, 2, 1, 3);
                        require("OLD-SORT", OrderQueries.list(sample, null).equals(List.of(1,2,3,4)));
                        return;
                    }
                    int cap = Integer.parseInt(args[1]);
                    String limit = args[2];
                    final var values = ids;
                    java.util.function.Function<Integer, List<Integer>> query = args[0].equals("order-list")
                            ? n -> OrderQueries.list(values, n) : n -> OrderQueries.history(values, n);
                    switch (limit) {
                        case "default" -> require("AC-01", query.apply(null).size() == 20);
                        case "below" -> require("AC-02-below", query.apply(cap - 1).size() == cap - 1);
                        case "at" -> require("AC-02-at", query.apply(cap).size() == cap);
                        case "above" -> require("AC-02-above", query.apply(cap + 1).size() == cap);
                        case "sort" -> require("AC-03", query.apply(3).equals(List.of(1,2,3)));
                        case "export" -> require("AC-04", OrderQueries.export(ids).size() == 80);
                        default -> throw new IllegalArgumentException(limit);
                    }
                }
                private static void require(String id, boolean ok) {
                    if (!ok) throw new AssertionError(id);
                    System.out.println(id + " passed");
                }
            }
            """;

    record Check(String id, String assertion, List<String> command, Integer exitCode, String status, String log) {}
    record Checks(Check compilation, List<Check> assertions) {
        boolean passed() {
            return compilation.status().equals("passed") && !assertions.isEmpty()
                    && assertions.stream().allMatch(c -> c.status().equals("passed"));
        }
    }

    static String initialize(Path run, String scope) throws IOException, InterruptedException {
        Path source = Files.createDirectory(run.resolve("source"));
        Files.createDirectories(source.resolve(SOURCE).getParent());
        Files.createDirectories(source.resolve(PROVENANCE).getParent());
        WorkflowFiles.write(source.resolve(SOURCE), baseline(scope));
        WorkflowFiles.write(source.resolve(PROVENANCE), SOURCE_NOTE);
        var out = run.resolve("git.txt");
        RepositoryWorkspace.git(source, out, "init");
        RepositoryWorkspace.git(source, out, "add", "--", SOURCE, PROVENANCE);
        RepositoryWorkspace.git(source, out, "-c", "commit.gpgSign=false", "commit", "-m", "Order fixture baseline");
        RepositoryWorkspace.git(source, out, "rev-parse", "HEAD");
        return Files.readString(out).strip();
    }

    static String baselineHash(String scope) {
        return hashText(baseline(scope));
    }

    private static String baseline(String scope) {
        return SOURCE_TEMPLATE.formatted(LIST.equals(scope) ? BASELINE : capped(50), BASELINE);
    }

    private static String hashText(String text) {
        return WorkflowFiles.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String capped(int cap) {
        return "return page(ids, limit == null ? 20 : Math.max(0, Math.min(" + cap + ", limit)));";
    }

    private static String candidateText(String scope, String implementation) {
        if (LIST.equals(scope)) return SOURCE_TEMPLATE.formatted(implementation, BASELINE);
        if (HISTORY.equals(scope)) return SOURCE_TEMPLATE.formatted(capped(50), implementation);
        throw new IllegalArgumentException("Export is read-only in this demo");
    }

    static Tool readTool(Path root) {
        return new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("read_order_query", "Read the approved order query source",
                        "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
            }

            public ToolResult execute(String arguments) {
                try {
                    return ToolResult.success(Files.readString(RepositoryWorkspace.scopedFile(root, SOURCE)));
                } catch (IOException e) {
                    return ToolResult.failure("Fixture source could not be read");
                }
            }
        };
    }

    static Tool editTool(Path root, String scope, int cap, boolean allowKnownBadPatch) {
        return new Tool() {
            public ToolDefinition definition() {
                return new ToolDefinition("edit_order_query",
                        "Apply the supported pagination edit to the approved endpoint. Use capped for the approved maximum.",
                        """
                        {"type":"object","properties":{"implementation":{"type":"string","enum":["capped","uncapped"]}},
                         "required":["implementation"],"additionalProperties":false}
                        """);
            }

            @Override
            public CapabilityKind capability() { return CapabilityKind.WRITE; }

            public ToolResult execute(String arguments) {
                try {
                    JsonNode args = WorkflowFiles.JSON.readTree(arguments);
                    String variant = args.path("implementation").asText();
                    if (args == null || !args.isObject() || args.size() != 1
                            || !(variant.equals("capped") || (allowKnownBadPatch && variant.equals("uncapped")))) {
                        return ToolResult.failure("Unsupported implementation");
                    }
                    Path target = RepositoryWorkspace.scopedFile(root, SOURCE);
                    requireTrusted(root, scope, cap);
                    WorkflowFiles.write(target, candidateText(scope, variant.equals("capped") ? capped(cap) : UNCAPPED));
                    return ToolResult.success("Updated the approved endpoint in " + SOURCE);
                } catch (IOException | IllegalArgumentException e) {
                    return ToolResult.failure("Fixture edit rejected: " + e.getMessage());
                }
            }
        };
    }

    static void requireTrusted(Path root, String scope, int cap) throws IOException {
        String actual = WorkflowFiles.hash(RepositoryWorkspace.scopedFile(root, SOURCE));
        if (!(baselineHash(scope).equals(actual)
                || hashText(candidateText(scope, capped(cap))).equals(actual)
                || hashText(candidateText(scope, UNCAPPED)).equals(actual))) {
            throw new IOException("Fixture source has changed outside approved templates");
        }
    }

    static Checks checks(Path run, String scope, int cap, int round) throws IOException, InterruptedException {
        Path root = run.resolve("source");
        requireTrusted(root, scope, cap);
        Path evidence = Files.createDirectory(run.resolve("checks-" + round));
        Path classes = Files.createDirectory(evidence.resolve("classes"));
        Path independentChecks = evidence.resolve(CHECKS);
        WorkflowFiles.write(independentChecks, ACCEPTANCE);
        Path checkedSource = evidence.resolve("OrderQueries.java");
        Files.copy(RepositoryWorkspace.scopedFile(root, SOURCE), checkedSource);
        requireTrustedCopy(checkedSource, scope, cap);
        Check compile = check(evidence, "compile", "Compile the captured fixture and independent checks",
                List.of(executable("javac"), "-encoding", "UTF-8", "-d", classes.toString(),
                        checkedSource.toString(), independentChecks.toString()));
        if (!compile.status.equals("passed")) return new Checks(compile, List.of());
        List<Check> assertions = new java.util.ArrayList<>();
        assertions.add(check(evidence, "old-sort", "Existing sort check with four orders",
                List.of(executable("java"), "-cp", classes.toString(), "OrderAcceptance", "old")));
        Map<String, String> ids = Map.of("default", "AC-01", "below", "AC-02-below", "at", "AC-02-at",
                "above", "AC-02-above", "sort", "AC-03", "export", "AC-04");
        for (String test : List.of("default", "below", "at", "above", "sort", "export")) {
            assertions.add(check(evidence, ids.get(test), switch (test) {
                case "default" -> "Missing limit returns 20";
                case "below" -> "Limit " + (cap - 1) + " returns " + (cap - 1);
                case "at" -> "Limit " + cap + " returns " + cap;
                case "above" -> "Limit " + (cap + 1) + " returns " + cap;
                case "sort" -> "Return the first three IDs in ascending order";
                default -> "Bulk export still returns all 80 orders";
            }, List.of(executable("java"), "-cp", classes.toString(), "OrderAcceptance", scope,
                    Integer.toString(cap), test)));
        }
        return new Checks(compile, List.copyOf(assertions));
    }

    private static void requireTrustedCopy(Path source, String scope, int cap) throws IOException {
        String actual = WorkflowFiles.hash(source);
        if (!(baselineHash(scope).equals(actual) || hashText(candidateText(scope, capped(cap))).equals(actual)
                || hashText(candidateText(scope, UNCAPPED)).equals(actual))) {
            throw new IOException("Captured fixture differs from approved templates");
        }
    }

    private static String executable(String name) {
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return Path.of(System.getProperty("java.home"), "bin", name + suffix).toAbsolutePath().toString();
    }

    private static Check check(Path evidence, String id, String assertion, List<String> command)
            throws IOException, InterruptedException {
        Path log = evidence.resolve(id + ".out");
        var result = LocalProcess.run(evidence, command, log, evidence.resolve(id + ".err"),
                Duration.ofSeconds(20), Map.of(), null);
        return new Check(id, assertion, command, result.limited() ? null : result.exit(),
                result.limited() ? "incomplete" : result.exit() == 0 ? "passed" : "failed",
                evidence.getFileName() + "/" + id + ".out / .err");
    }
}
