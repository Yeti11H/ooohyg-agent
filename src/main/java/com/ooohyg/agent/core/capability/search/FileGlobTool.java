package com.ooohyg.agent.core.capability.search;

import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按文件名 glob 模式查找文件（对应 Claude Code 的 Glob）。
 *
 * <p>结果按最近修改时间倒序排序——"最近修改的文件最相关"的朴素启发式。
 * 结果数量受 scope 的 {@code maxFiles} 限制，超出时标注 [TRUNCATED]。
 */
public final class FileGlobTool extends FileSearchTool {

    /**
     * @param context 检索边界
     * @param toolName 注册到 Registry 的工具名（如 memory_glob / code_glob）
     */
    public FileGlobTool(SearchContext context, String toolName) {
        super(context);
        this.definition = new ToolDefinition(
                toolName,
                "按文件名模式查找文件，结果按最近修改时间排序。",
                """
                {"type":"object","properties":{
                  "pattern":{"type":"string","description":"glob模式，如 **/*.md"}
                },"required":["pattern"]}
                """
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        String pattern = extractValue(arguments, "pattern");
        if (pattern.isBlank()) {
            return ToolResult.failure("Empty pattern");
        }
        try {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            List<Path> files = listFiles();
            List<Path> matched = files.stream()
                    .filter(p -> matcher.matches(context.root().relativize(p)))
                    .toList();

            List<Map.Entry<Path, Long>> sorted = new ArrayList<>();
            for (Path f : matched) {
                long lastModified = Files.getLastModifiedTime(f).toMillis();
                sorted.add(Map.entry(f, lastModified));
            }
            sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

            List<String> result = new ArrayList<>();
            int limit = Math.min(sorted.size(), context.maxFiles());
            for (int i = 0; i < limit; i++) {
                result.add(sorted.get(i).getKey().toString());
            }
            if (sorted.size() > context.maxFiles()) {
                result.add("[TRUNCATED] Showing " + context.maxFiles() + " of " + sorted.size() + " files.");
            }
            return ToolResult.success(result.isEmpty() ? "No files matched." : String.join("\n", result));
        } catch (Exception e) {
            return ToolResult.failure("Glob failed: " + e.getMessage());
        }
    }
}
