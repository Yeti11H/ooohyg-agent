// 4. MemoryReadTool - 按需读取文件内容
package com.ooohyg.agent.core.capability.tool;

import java.nio.file.*;
import java.util.List;

public final class MemoryReadTool implements Tool {
    private static final int DEFAULT_LIMIT = 2000;
    private final Path memoryRoot;
    private final ToolDefinition definition;

    public MemoryReadTool(Path memoryRoot) {
        this.memoryRoot = memoryRoot.toAbsolutePath().normalize();
        this.definition = new ToolDefinition(
                "memory_read",
                "读取指定记忆文件的内容。默认最多读取 2000 行，可用 offset 和 limit 分段读取。",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"相对于记忆根目录的文件路径"},
                  "offset":{"type":"integer","description":"起始行号（从 1 开始），默认为 1"},
                  "limit":{"type":"integer","description":"最多读取行数，默认为 2000"}
                },"required":["path"]}
                """
        );
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(String arguments) {
        String relativePath = extractValue(arguments, "path");
        if (relativePath.isBlank()) return ToolResult.failure("Empty path");
        int offset = parseIntSafe(extractValue(arguments, "offset"), 1);
        int limit = parseIntSafe(extractValue(arguments, "limit"), DEFAULT_LIMIT);

        try {
            Path target = MemoryFileUtils.resolveSafe(memoryRoot, relativePath);
            if (!Files.exists(target) || !Files.isRegularFile(target)) {
                return ToolResult.failure("File not found: " + relativePath);
            }
            List<String> allLines = Files.readAllLines(target);
            int start = Math.max(0, offset - 1);
            int end = Math.min(allLines.size(), start + limit);
            if (start >= allLines.size()) return ToolResult.success("(offset beyond end of file)");

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append(": ").append(allLines.get(i)).append("\n");
            }
            if (end < allLines.size()) {
                sb.append("\n[TRUNCATED] File has ").append(allLines.size())
                        .append(" lines total. Use offset/limit to read more.");
            }
            return ToolResult.success(sb.toString().stripTrailing());
        } catch (Exception e) {
            return ToolResult.failure("Read failed: " + e.getMessage());
        }
    }
    private static String extractValue(String args, String key) { /* 同上 */
    return args;}
    private static int parseIntSafe(String s, int def) {
        try { return s == null || s.isBlank() ? def : Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}