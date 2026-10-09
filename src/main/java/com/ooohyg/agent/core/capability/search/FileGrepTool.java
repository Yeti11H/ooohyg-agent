package com.ooohyg.agent.core.capability.search;

import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按内容搜索文件（对应 Claude Code 的 Grep）。
 *
 * <p>支持三种输出模式：
 * <ul>
 *   <li>{@code files_with_matches}（默认）：只返回命中的文件路径；</li>
 *   <li>{@code content}：返回命中的行（最多 {@value #MAX_CONTENT_LINES} 行）；</li>
 *   <li>{@code count}：只返回总命中数。</li>
 * </ul>
 *
 * <p>结果超出 scope 上限时会在返回内容中明确标注 [TRUNCATED]，
 * 引导模型缩小搜索范围，避免模型误以为已拿到全部结果。
 */
public final class FileGrepTool extends FileSearchTool {

    /** content 模式下最多返回的命中行数。 */
    private static final int MAX_CONTENT_LINES = 200;

    /**
     * @param context 检索边界
     * @param toolName 注册到 Registry 的工具名（如 memory_grep / code_grep）
     */
    public FileGrepTool(SearchContext context, String toolName) {
        super(context);
        this.definition = new ToolDefinition(
                toolName,
                "在指定范围内按内容搜索。支持 files_with_matches / content / count 三种输出模式。",
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"搜索词或正则表达式"},
                  "output_mode":{"type":"string","enum":["files_with_matches","content","count"]},
                  "scope":{"type":"string","description":"检索范围子路径，可选"}
                },"required":["query"]}
                """
        );
    }

    @Override
    public ToolResult execute(String arguments) {
        String query = extractValue(arguments, "query");
        String mode = extractValue(arguments, "output_mode");
        if (query.isBlank()) {
            return ToolResult.failure("Empty query");
        }
        if (mode.isBlank()) {
            mode = "files_with_matches";
        }

        try {
            Pattern pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            List<Path> files = listFiles();
            List<String> matchedFiles = new ArrayList<>();
            List<String> contentLines = new ArrayList<>();
            int totalMatches = 0;

            for (Path file : files) {
                List<String> lines = Files.readAllLines(file);
                boolean fileMatched = false;
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).find()) {
                        fileMatched = true;
                        totalMatches++;
                        if ("content".equals(mode) && contentLines.size() < MAX_CONTENT_LINES) {
                            contentLines.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i));
                        }
                    }
                }
                if (fileMatched && matchedFiles.size() < context.maxFiles()) {
                    matchedFiles.add(file.toString());
                }
                if (matchedFiles.size() >= context.maxFiles() && "files_with_matches".equals(mode)) {
                    break;
                }
            }

            if ("count".equals(mode)) {
                return ToolResult.success("Total matches: " + totalMatches);
            }
            if ("content".equals(mode)) {
                String result = String.join("\n", contentLines);
                if (totalMatches > contentLines.size()) {
                    result += "\n\n[TRUNCATED] Showing first " + contentLines.size()
                            + " lines of " + totalMatches + " total matches.";
                }
                return ToolResult.success(result.isEmpty() ? "No matches." : result);
            }
            if (matchedFiles.isEmpty()) {
                return ToolResult.success("No files matched.");
            }
            String result = String.join("\n", matchedFiles);
            if (matchedFiles.size() >= context.maxFiles()) {
                result += "\n\n[TRUNCATED] Showing first " + context.maxFiles()
                        + " files. Narrow your search.";
            }
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.failure("Grep failed: " + e.getMessage());
        }
    }
}
