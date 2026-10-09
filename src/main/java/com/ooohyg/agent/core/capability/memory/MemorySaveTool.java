package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.capability.search.FileSearchTool;
import com.ooohyg.agent.core.capability.tool.CapabilityKind;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 记忆写入工具：把一条带 frontmatter 的记忆保存为 Markdown 文件，并重建索引。
 *
 * <p>设计要点：
 * <ul>
 *   <li>强制四种记忆类型（user / feedback / project / reference），非法类型拒绝写入；</li>
 *   <li>每条记忆是独立的 .md 文件，文件头为 YAML frontmatter（name / description / type）；</li>
 *   <li>写入后重建 MEMORY.md 索引，索引上限 200 行，避免"长行索引炸弹"；</li>
 *   <li>写入的决策权交给调用方（用户或整理流程），工具本身不自动记忆任何东西。</li>
 * </ul>
 */
public final class MemorySaveTool implements Tool {

    private static final int INDEX_MAX_LINES = 200;
    private static final String INDEX_FILE_NAME = "MEMORY.md";

    private final Path memoryRoot;
    private final MemoryContext context;
    private final ToolDefinition definition;

    /**
     * @param memoryRoot 记忆文件存放目录
     * @param context    记忆上下文（类型约束等）
     */
    public MemorySaveTool(Path memoryRoot, MemoryContext context) {
        this.memoryRoot = memoryRoot.toAbsolutePath().normalize();
        this.context = context;
        this.definition = new ToolDefinition(
                "memory_save",
                "保存一条记忆到记忆库。type 必须是 user / feedback / project / reference 之一。",
                """
                {"type":"object","properties":{
                  "name":{"type":"string","description":"记忆标识"},
                  "description":{"type":"string","description":"一句话描述"},
                  "type":{"type":"string","enum":["user","feedback","project","reference"]},
                  "content":{"type":"string","description":"记忆正文"}
                },"required":["name","description","type","content"]}
                """
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public CapabilityKind capability() {
        return CapabilityKind.WRITE;
    }

    @Override
    public ToolResult execute(String arguments) {
        String name = FileSearchTool.extractValue(arguments, "name");
        String description = FileSearchTool.extractValue(arguments, "description");
        String type = FileSearchTool.extractValue(arguments, "type");
        String content = FileSearchTool.extractValue(arguments, "content");

        if (name.isBlank() || content.isBlank()) {
            return ToolResult.failure("name and content must not be blank");
        }
        if (!context.allowedTypes().contains(type)) {
            return ToolResult.failure("Invalid memory type: " + type
                    + ". Allowed: " + context.allowedTypes());
        }
        try {
            Files.createDirectories(memoryRoot);
            String fileName = sanitize(name) + ".md";
            String fullContent = "---\n"
                    + "name: " + name + "\n"
                    + "description: " + description + "\n"
                    + "type: " + type + "\n"
                    + "---\n\n" + content;
            Files.writeString(memoryRoot.resolve(fileName), fullContent);
            updateIndex();
            return ToolResult.success("Memory saved: " + name);
        } catch (IOException e) {
            return ToolResult.failure("Save failed: " + e.getMessage());
        }
    }

    /**
     * 把记忆名转成安全的文件名（去除 Windows / Unix 非法字符，空白折叠为下划线）。
     */
    private static String sanitize(String name) {
        String cleaned = name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        return cleaned.isEmpty() ? "memory_" + System.currentTimeMillis() : cleaned;
    }

    /**
     * 扫描所有记忆 .md 文件，读取 frontmatter，重建 MEMORY.md 索引。
     *
     * <p>索引上限 {@value #INDEX_MAX_LINES} 行；索引失败不影响保存主流程
     * （下次保存会再次重建）。
     */
    private void updateIndex() {
        try (var stream = Files.list(memoryRoot)) {
            List<Path> mdFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase(INDEX_FILE_NAME))
                    .sorted()
                    .toList();

            StringBuilder sb = new StringBuilder();
            sb.append("# MEMORY INDEX\n\n");
            int lines = 2;
            for (Path f : mdFiles) {
                if (lines >= INDEX_MAX_LINES) {
                    break;
                }
                sb.append(indexLine(f)).append('\n');
                lines++;
            }
            Files.writeString(memoryRoot.resolve(INDEX_FILE_NAME), sb.toString());
        } catch (IOException e) {
            // 索引失败静默：不影响"记忆已保存"的结果
        }
    }

    /**
     * 从单个记忆文件中解析 frontmatter，生成一行索引条目。
     */
    private static String indexLine(Path file) {
        String name = file.getFileName().toString();
        String description = "";
        String type = "";
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            boolean inFront = false;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.equals("---")) {
                    if (!inFront) {
                        inFront = true;
                        continue;
                    }
                    break;
                }
                if (inFront) {
                    if (trimmed.startsWith("name:")) {
                        name = trimmed.substring(5).trim();
                    } else if (trimmed.startsWith("description:")) {
                        description = trimmed.substring(12).trim();
                    } else if (trimmed.startsWith("type:")) {
                        type = trimmed.substring(5).trim();
                    }
                }
            }
        } catch (IOException ignored) {
            // 单个文件解析失败时回退到文件名
        }
        return "- " + name + " — " + description + " (type: " + type + ")";
    }
}
