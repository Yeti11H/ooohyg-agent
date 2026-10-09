package com.ooohyg.agent.core.capability.search;

import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 按需读取文件内容（对应 Claude Code 的 Read）。
 *
 * <p>核心原则是"按需读取，不要贪心"：默认最多读取 scope 的
 * {@code maxLinesPerFile} 行，可用 offset / limit 分段读取。
 * 每次执行直接读取磁盘最新内容，无缓存、无索引，保证实时性。
 */
public final class FileReadTool extends FileSearchTool {

    /**
     * @param context 检索边界
     * @param toolName 注册到 Registry 的工具名（如 memory_read / code_read）
     */
    public FileReadTool(SearchContext context, String toolName) {
        super(context);
        this.definition = new ToolDefinition(
                toolName,
                "读取指定文件内容。默认最多读取 " + context.maxLinesPerFile()
                        + " 行，可用 offset 和 limit 分段读取。",
                """
                {"type":"object","properties":{
                  "path":{"type":"string","description":"相对于检索根目录的文件路径"},
                  "offset":{"type":"integer","description":"起始行号（从1开始）"},
                  "limit":{"type":"integer","description":"最多读取行数"}
                },"required":["path"]}
                """
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        String relativePath = extractValue(arguments, "path");
        if (relativePath.isBlank()) {
            return ToolResult.failure("Empty path");
        }
        int offset = parseIntSafe(extractValue(arguments, "offset"), 1);
        int limit = parseIntSafe(extractValue(arguments, "limit"), context.maxLinesPerFile());

        try {
            Path target = resolveSafe(relativePath);
            if (!Files.exists(target) || !Files.isRegularFile(target)) {
                return ToolResult.failure("File not found: " + relativePath);
            }
            List<String> allLines = Files.readAllLines(target);
            int start = Math.max(0, offset - 1);
            int end = Math.min(allLines.size(), start + limit);
            if (start >= allLines.size()) {
                return ToolResult.success("(offset beyond end of file)");
            }

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

    private static int parseIntSafe(String s, int def) {
        try {
            return s == null || s.isBlank() ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
