package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.search.FileSearchTool;
import com.ooohyg.agent.core.message.Message;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆选择器：用小模型做"选择题"，从所有记忆的 name / description 中挑出最相关的 top-K。
 *
 * <p>对应 Claude Code 记忆机制的"小模型做选择题"：
 * <ul>
 *   <li>不向量检索、不 embedding，只把记忆索引（name + description）发给模型；</li>
 *   <li>提示词要求"不确定就别选，宁可少选不可错选"；</li>
 *   <li>返回的候选清单再交给上层按需读取完整内容。</li>
 * </ul>
 */
public final class MemorySelector {

    private final LlmClient llmClient;
    private final MemoryContext context;

    /**
     * @param llmClient 用于做选择的模型客户端
     * @param context   记忆上下文（含检索边界）
     */
    public MemorySelector(LlmClient llmClient, MemoryContext context) {
        this.llmClient = llmClient;
        this.context = context;
    }

    /**
     * 根据查询挑出最相关的 top-K 条记忆文件名。
     *
     * @param query 用户查询 / 当前任务相关词
     * @param topK  最多返回条数
     * @return 记忆文件名列表（可能少于 topK，甚至为空）
     */
    public List<String> selectTopMemories(String query, int topK) {
        List<MemoryIndexEntry> entries = scanMemoryIndex();
        if (entries.isEmpty()) {
            return List.of();
        }
        String prompt = "Query: " + query + "\nAvailable memories:\n"
                + entries.stream()
                        .map(e -> "- " + e.name() + " — " + e.description())
                        .collect(Collectors.joining("\n"))
                + "\n\nOnly include memories that you are certain will be helpful. "
                + "Be selective and discerning. Return at most " + topK + " file names, one per line.";

        LlmResponse response = llmClient.chat(List.of(Message.user(prompt)), null);
        List<String> names = parseFileNames(response.content());
        return names.size() > topK ? names.subList(0, topK) : names;
    }

    /**
     * 扫描记忆根目录下所有 .md 文件的 frontmatter，生成索引条目。
     */
    private List<MemoryIndexEntry> scanMemoryIndex() {
        Path root = context.searchContext().root();
        List<MemoryIndexEntry> entries = new ArrayList<>();
        try (var stream = Files.list(root)) {
            List<Path> mdFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equalsIgnoreCase("MEMORY.md"))
                    .sorted()
                    .toList();
            for (Path f : mdFiles) {
                entries.add(readFrontmatter(f));
            }
        } catch (IOException ignored) {
            // 记忆目录不存在或不可读时返回空列表
        }
        return entries;
    }

    /**
     * 读取单个记忆文件的 frontmatter（name / description / type）。
     */
    private static MemoryIndexEntry readFrontmatter(Path file) {
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
            // 解析失败回退到文件名
        }
        return new MemoryIndexEntry(name, description, file.toString());
    }

    /**
     * 从模型返回内容中解析文件名列表：每行一个，容忍 "- " 前缀与首尾空白。
     */
    private static List<String> parseFileNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                trimmed = trimmed.substring(2).trim();
            }
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * 索引条目：记忆的 name / description / 文件绝对路径。
     */
    public record MemoryIndexEntry(
            String name,
            String description,
            String filePath
    ) {
    }
}
