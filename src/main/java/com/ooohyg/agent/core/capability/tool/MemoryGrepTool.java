// 2. MemoryGrepTool - 按内容搜索记忆文件
package com.ooohyg.agent.core.capability.tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class MemoryGrepTool implements Tool {
    private static final int MAX_MATCHING_FILES = 100;
    private static final int MAX_CONTENT_LINES = 200;

    private final Path memoryRoot;
    private final ToolDefinition definition;

    public MemoryGrepTool(Path memoryRoot) {
        this.memoryRoot = memoryRoot.toAbsolutePath().normalize();
        this.definition = new ToolDefinition(
                "memory_grep",
                "在记忆库中按内容搜索。默认只返回匹配的文件路径，也可返回匹配行或数量。",
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"要搜索的内容或正则表达式"},
                  "output_mode":{"type":"string","enum":["files_with_matches","content","count"],
                                 "description":"输出模式，默认为 files_with_matches"}
                },"required":["query"]}
                """
        );
    }

    @Override public ToolDefinition definition() { return definition; }

    @Override
    public ToolResult execute(String arguments) {
        // 极简参数提取（沿用你项目已有的 extractValue 风格）
        String query = extractValue(arguments, "query");
        String mode = extractValue(arguments, "output_mode");
        if (query.isBlank()) return ToolResult.failure("Empty query");
        if (mode.isBlank()) mode = "files_with_matches";

        try {
            Pattern pattern = Pattern.compile(query, Pattern.CASE_INSENSITIVE);
            List<Path> allFiles = MemoryFileUtils.listMemoryFiles(memoryRoot);
            List<String> matchedFiles = new ArrayList<>();
            List<String> contentLines = new ArrayList<>();
            int totalMatches = 0;

            for (Path file : allFiles) {
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
                if (fileMatched && matchedFiles.size() < MAX_MATCHING_FILES) {
                    matchedFiles.add(file.toString());
                }
                if (matchedFiles.size() >= MAX_MATCHING_FILES && "files_with_matches".equals(mode)) break;
            }

            // 构造返回结果，并在截断时明确告知模型
            if ("count".equals(mode)) {
                return ToolResult.success("Total matches: " + totalMatches);
            } else if ("content".equals(mode)) {
                String result = String.join("\n", contentLines);
                if (totalMatches > contentLines.size()) {
                    result += "\n\n[TRUNCATED] Showing first " + contentLines.size() + " lines of " + totalMatches + " total matches.";
                }
                return ToolResult.success(result.isEmpty() ? "No matches." : result);
            } else { // files_with_matches
                if (matchedFiles.isEmpty()) return ToolResult.success("No files matched.");
                String result = String.join("\n", matchedFiles);
                if (matchedFiles.size() >= MAX_MATCHING_FILES) {
                    result += "\n\n[TRUNCATED] Showing first " + MAX_MATCHING_FILES + " files. Narrow your search.";
                }
                return ToolResult.success(result);
            }
        } catch (Exception e) {
            return ToolResult.failure("Grep failed: " + e.getMessage());
        }
    }

    private static String extractValue(String args, String key) { /* 同你项目已有逻辑，略 */
        return args;
    }
}