package com.ooohyg.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class RepositoryWorkspace {
    private static final Pattern GITHUB = Pattern.compile(
            "https://github\\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]{1,100})/?");
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    record Import(String repository, String commit, String build, String execution) {}

    static String githubUrl(String input) {
        var match = GITHUB.matcher(input);
        if (!match.matches()) throw new IllegalArgumentException("Use https://github.com/OWNER/REPO");
        String repo = match.group(2).replaceFirst("\\.git$", "");
        if (repo.isEmpty() || repo.equals(".") || repo.equals("..") || repo.startsWith("-")) {
            throw new IllegalArgumentException("Invalid GitHub repository name");
        }
        return "https://github.com/" + match.group(1) + "/" + repo;
    }

    static Import importGithub(Path task, String input) throws IOException, InterruptedException {
        String repository = githubUrl(input);
        Path source = Files.createDirectory(task.resolve("repository"));
        Path out = task.resolve("import-output.txt");
        git(source, out, "init", "--bare");
        git(source, out, "ls-remote", repository, "HEAD");
        String commit = Files.readString(out).lines().findFirst().orElse("").split("\\s+")[0];
        if (!SHA.matcher(commit).matches()) throw new IOException("Remote HEAD is unavailable");
        git(source, out, "fetch", "--depth=1", "--no-tags", repository, commit);
        git(source, out, "rev-parse", "FETCH_HEAD");
        if (!commit.equals(Files.readString(out).strip())) throw new IOException("Fetched version differs from pinned HEAD");
        git(source, task.resolve("files.txt"), "ls-tree", "--name-only", commit);
        List<String> files = Files.readAllLines(task.resolve("files.txt"));
        String build = files.contains("pom.xml") ? "Maven"
                : files.contains("build.gradle") || files.contains("build.gradle.kts") ? "Gradle"
                : files.contains("package.json") ? "Node" : "Unknown";
        return new Import(repository, commit, build, "READ_ONLY");
    }

    static void readImported(Path task, Import imported, String relative, Path output)
            throws IOException, InterruptedException {
        validateRelative(relative);
        if (!SHA.matcher(imported.commit()).matches()) throw new IOException("Invalid pinned commit");
        Path repository = task.resolve("repository");
        git(repository, output, "ls-tree", imported.commit(), "--", relative);
        String entry = Files.readString(output);
        if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) {
            throw new IOException("Path is not a regular file at the pinned commit");
        }
        git(repository, output, "show", imported.commit() + ":" + relative);
    }

    static void git(Path cwd, Path output, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false",
                "-c", "core.hooksPath=.disabled-hooks", "-c", "protocol.file.allow=never",
                "-c", "http.followRedirects=false", "-c", "credential.helper="));
        command.addAll(List.of(arguments));
        Path log = output.resolveSibling(output.getFileName() + ".stderr");
        Path config = output.getParent().resolve(".empty-gitconfig");
        if (!Files.exists(config)) WorkflowFiles.write(config, "");
        LocalProcess.run(cwd, command, output, log, Duration.ofSeconds(40),
                Map.of("GIT_CONFIG_GLOBAL", config.toAbsolutePath().toString(),
                        "GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0",
                        "GIT_AUTHOR_NAME", "Workflow Fixture", "GIT_COMMITTER_NAME", "Workflow Fixture",
                        "GIT_AUTHOR_EMAIL", "workflow@example.invalid", "GIT_COMMITTER_EMAIL", "workflow@example.invalid",
                        "GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z"),
                cwd).requireSuccess("git " + (arguments[0].equals("-c") ? arguments[2] : arguments[0]), log);
    }

    static Path scopedFile(Path root, String relative) throws IOException {
        validateRelative(relative);
        Path candidate = root.toAbsolutePath().normalize();
        BasicFileAttributes attributes = unlinked(candidate);
        for (Path part : Path.of(relative)) {
            if (!attributes.isDirectory()) throw new IOException("Expected a directory");
            candidate = candidate.resolve(part);
            attributes = unlinked(candidate);
        }
        if (!attributes.isRegularFile() || attributes.size() > 128 * 1024) {
            throw new IOException("Only bounded regular files are supported");
        }
        return candidate;
    }

    private static BasicFileAttributes unlinked(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) throw new IOException("Linked or special path");
        return attrs;
    }

    private static void validateRelative(String relative) {
        if (!relative.matches("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*")
                || List.of(relative.split("/")).stream()
                    .anyMatch(s -> s.equals(".") || s.equals("..") || s.equalsIgnoreCase(".git"))) {
            throw new IllegalArgumentException("Invalid repository-relative path");
        }
    }
}
