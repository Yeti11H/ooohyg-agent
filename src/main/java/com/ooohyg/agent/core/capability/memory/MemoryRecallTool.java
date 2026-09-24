package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * 把长期记忆的"回忆"能力包装成 {@link Tool}，接入 ReAct 循环。
 *
 * <p>它做的事情：模型调用 {@code recall_memory} → 从 arguments
 * 提取 query → 调 {@link LongTermMemory#recall} → 把结果拼成
 * 文本返回给模型。
 *
 * <p><b>为什么长期记忆要包成 Tool，而不是像短期记忆那样用装饰器：</b>
 * <ul>
 *   <li>装饰器在每次 chat 前自动触发，无法判断"这次调用该用什么
 *       query 去回忆"；</li>
 *   <li>记忆是否相关需要模型判断——它知道自己在处理什么问题，
 *       框架不知道；</li>
 *   <li>不相关的记忆塞进上下文会干扰模型。让模型主动调，它才会
 *       在最该回忆的时刻回忆。</li>
 * </ul>
 * 这与 RAG 的 {@code RetrievalTool} 完全同构——外部知识检索和
 * 过往经验检索，本质是同一种动作。
 *
 * <p><b>query 提取策略：</b>
 * 模型返回的 arguments 通常是 {@code {"query":"..."}}。
 * 本类做极简字符串提取：找 {@code "query"} 键、提取其后的字符串值、
 * 处理基本的 {@code \"} 和 {@code \\} 转义。提取失败时把
 * arguments 整体当 query——覆盖模型直接返回纯字符串的场景。
 * 不处理嵌套 JSON、数组、Unicode 转义——那些场景属于极少数，
 * 引入完整 JSON 库不值得。
 *
 * <p><b>空结果返回 success 而不是 failure：</b>
 * "没找到相关记忆"是正常业务结果——模型需要看到这个信息才能
 * 决定换 query 重试或直接回答。返回 failure 会让模型以为工具坏了，
 * 产生不必要的重试。
 *
 * <p><b>结果拼接格式：</b>
 * <pre>
 *   [1] 记忆内容...
 *   [2] 记忆内容...
 * </pre>
 * 不输出 score——score 对模型推理意义不大，反而可能干扰判断。
 * 需要调试时可以在自己的 LongTermMemory 实现里打印。
 */
public final class MemoryRecallTool implements Tool {

    private static final String DEFAULT_TOOL_NAME = "recall_memory";

    private static final String DEFAULT_DESCRIPTION =
            "回忆之前对话中提到的信息。当你需要知道用户以前说过什么、"
                    + "有什么偏好、做过什么决定时，调用这个工具。"
                    + "参数是一个查询语句。";

    /**
     * 默认返回的候选数。5 是经验折中：够覆盖常见问题，
     * 又不会把 prompt 撑得过长。
     */
    private static final int DEFAULT_TOP_K = 5;

    private final LongTermMemory longTermMemory;
    private final ToolDefinition definition;
    private final int topK;

    /**
     * 用默认配置构造。
     *
     * @param longTermMemory 长期记忆实现，非 null
     */
    public MemoryRecallTool(LongTermMemory longTermMemory) {
        this(longTermMemory, DEFAULT_TOOL_NAME, DEFAULT_DESCRIPTION, DEFAULT_TOP_K);
    }

    /**
     * 全参构造。
     *
     * <p>多个记忆库场景下，用户应给每个工具不同的名字和描述，
     * 让模型能区分该调哪个。
     *
     * @param longTermMemory 长期记忆实现，非 null
     * @param toolName       工具名，非空
     * @param description    工具描述，非空
     * @param topK           每次检索返回的候选数，必须为正
     */
    public MemoryRecallTool(LongTermMemory longTermMemory,
                            String toolName,
                            String description,
                            int topK) {
        this.longTermMemory = Objects.requireNonNull(longTermMemory,
                "longTermMemory must not be null");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive, got: " + topK);
        }
        this.topK = topK;
        this.definition = new ToolDefinition(
                toolName,
                description,
                "{\"type\":\"object\","
                        + "\"properties\":{"
                        + "\"query\":{\"type\":\"string\","
                        + "\"description\":\"要查询的记忆内容\"}},"
                        + "\"required\":[\"query\"]}"
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(String arguments) {
        String query = extractValue(arguments, "query");
        if (query.isBlank()) {
            return ToolResult.failure(
                    "Empty query: arguments did not contain a usable query string.");
        }

        List<LongMemorySearchResult> results = longTermMemory.recall(query, topK);

        if (results.isEmpty()) {
            return ToolResult.success("No relevant memories found for query: " + query);
        }
        return ToolResult.success(formatResults(results));
    }

    private static String formatResults(List<LongMemorySearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(results.get(i).record().content())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 从模型的 arguments 中提取字符串字段。
     *
     * <p>优先尝试提取 JSON 中的 key 字段；失败时把 arguments
     * 整体当值返回。
     */
    private static String extractValue(String arguments, String key) {
        if (arguments == null) {
            return "";
        }
        String trimmed = arguments.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.startsWith("{")) {
            return stripQuotes(trimmed);
        }

        int keyIdx = trimmed.indexOf("\"" + key + "\"");
        if (keyIdx < 0) {
            return stripQuotes(trimmed);
        }
        int colonIdx = trimmed.indexOf(':', keyIdx);
        if (colonIdx < 0) {
            return stripQuotes(trimmed);
        }
        int valueStart = trimmed.indexOf('"', colonIdx + 1);
        if (valueStart < 0) {
            return stripQuotes(trimmed);
        }

        StringBuilder sb = new StringBuilder();
        for (int i = valueStart + 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                char next = trimmed.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return stripQuotes(trimmed);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}