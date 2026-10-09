// 1. 共用的文件工具类，处理忽略规则和路径安全
package com.ooohyg.agent.core.capability.tool;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class MemoryFileUtils {
    // 硬编码的忽略列表，避免搜索噪音
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "target", "build", "node_modules", ".idea", ".vscode"
    );

    private MemoryFileUtils() {}

    // 判断路径是否应该被忽略
    static boolean isIgnored(Path path) {
        for (Path part : path) {
            if (IGNORED_DIRS.contains(part.toString())) return true;
        }
        return false;
    }

    // 安全解析记忆根目录下的相对路径，防止路径穿越
    static Path resolveSafe(Path memoryRoot, String relativePath) {
        Path resolved = memoryRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(memoryRoot)) {
            throw new IllegalArgumentException("Path escapes memory root: " + relativePath);
        }
        return resolved;
    }

    // 递归获取所有记忆文件（.md 或其他），并过滤忽略项
    static List<Path> listMemoryFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }
}