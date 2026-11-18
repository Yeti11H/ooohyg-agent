package com.ooohyg.agent.workflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class LocalProcess {
    private static final long OUTPUT_LIMIT = 512 * 1024;
    private static final Set<String> SYSTEM_ENV = Set.of("PATH", "SYSTEMROOT", "WINDIR",
            "TEMP", "TMP", "PATHEXT", "LANG", "LC_ALL");

    record Result(int exit, boolean limited) {
        void requireSuccess(String operation, Path log) throws IOException {
            if (limited || exit != 0) {
                throw new IOException(operation + (limited ? " exceeded its limits" : " failed (exit " + exit + ")")
                        + "; see " + log);
            }
        }
    }

    static Result run(Path cwd, List<String> command, Path stdout, Path stderr,
                      Duration duration, Map<String, String> extraEnv, Path boundedDirectory)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        builder.environment().keySet().removeIf(k -> !SYSTEM_ENV.contains(k.toUpperCase(java.util.Locale.ROOT)));
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        long deadline = System.nanoTime() + duration.toNanos();
        try {
            process.getOutputStream().close();
            while (true) {
                boolean exited = process.waitFor(100, TimeUnit.MILLISECONDS);
                if (Files.size(stdout) + Files.size(stderr) > OUTPUT_LIMIT
                        || (boundedDirectory != null && tooLarge(boundedDirectory))) {
                    return new Result(-1, true);
                }
                if (exited) return new Result(process.exitValue(), false);
                if (System.nanoTime() >= deadline) return new Result(-1, true);
            }
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static boolean tooLarge(Path root) throws IOException {
        long remaining = 40L * 1024 * 1024;
        try (var paths = Files.walk(root)) {
            var cursor = paths.iterator();
            while (cursor.hasNext()) {
                Path path = cursor.next();
                if (Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        remaining -= Files.size(path);
                    } catch (java.nio.file.NoSuchFileException ignored) {
                        // Git can rename a temporary pack while a fetch is running.
                    }
                }
                if (remaining < 0) return true;
            }
        }
        return false;
    }
}
