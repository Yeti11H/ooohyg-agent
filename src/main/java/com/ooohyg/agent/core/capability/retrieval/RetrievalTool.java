package com.ooohyg.agent.core.capability.retrieval;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

import java.util.List;
import java.util.Objects;

/**
 * 把 RAG 检索包装成 {@link Tool}，接入 ReAct 循环。
 *
 * <p>它做的事情很简单：模型调用 {@code retrieval} 工具 → 从 arguments
 * 里提取 query → 调 {@link EmbeddingClient#embed} → 调
 * {@link VectorStore#search} → 把结果拼成文本返回给模型。
 *
 * <p><b>为什么这是薄包装：</b>
 * <ul>
 *   <li>它不做混合检索——BM25 + 向量融合属于用户的 Retriever；</li>
 *   <li>它不做 rerank——Cross-Encoder 精排属于用户的 Retriever；</li>
 *   <li>它不做查询改写——意图识别和改写属于用户的 Retriever；</li>
 *   <li>它只做"embed + search + 拼接"三件事，职责单一。</li>
 * </ul>
 * 用户要更复杂的检索逻辑，应该在 adapter 层写一个自己的 Retriever，
 * 再把它包成 Tool——而不是修改 RetrievalTool。
 *
 * <p><b>为什么查询改写不在这里做：</b>
 * 改写通常需要调 LLM，会引入"谁调模型"的循环依赖（RetrievalTool
 * 在 ReAct 里，ReAct 又调模型）。而且改写策略是场景相关的——
 * 客服补全指代、搜索扩展同义词、医疗标准化术语，规则完全不同。
 * 把它放在用户的 Retriever 里，RetrievalTool 保持纯粹。
 *
 * <p><b>query 提取策略：</b>
 * 模型返回的 arguments 是 JSON 字符串，常见格式为
 * {@code {"query":"..."}}。本类内部做极简字符串提取：
 * <ul>
 *   <li>找到 {@code "query"} 键，提取其后的字符串值；</li>
 *   <li>处理基本转义：{@code \"}、{@code \\}；</li>
 *   <li>提取失败时——把整个 arguments 原样当 query。这覆盖模型
 *       直接返回纯字符串的场景。</li>
 * </ul>
 * 极简提取不处理嵌套对象、数组、Unicode 转义等复杂情况——
 * 这些都是 99% 之外的场景，引入完整 JSON 库不值得。
 *
 * <p><b>为什么空结果返回 success 而不是 failure：</b>
 * "没检索到相关内容"是正常的业务结果，模型需要看到这个信息，
 * 才能决定换 query 重试或直接回答。返回 failure 会让模型以为
 * 工具坏了，产生不必要的重试。
 *
 * <p><b>结果拼接格式：</b>
 * <pre>
 *   [1] 文档内容...
 *   [2] 文档内容...
 *   ...
 * </pre>
 * 不加 score——score 对模型的推理意义不大，反而可能干扰判断
 * （模型会误以为 score 高的一定更相关）。用户在调试时可以
 * 自己在 Retriever 里打印 score。
 */
public final class RetrievalTool implements Tool {

    /**
     * 默认工具名。用户在构造时可覆盖。
     */
    private static final String DEFAULT_TOOL_NAME = "retrieval";

    /**
     * 默认返回的候选数。
     *
     * <p>5 是一个经验折中：够覆盖常见问题的相关片段，又不会
     * 把 prompt 撑得过长。模型一轮检索通常不需要 10 条以上。
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 默认工具描述。
     */
    private static final String DEFAULT_DESCRIPTION =
            "检索知识库。当你需要查找训练知识、课程信息、专业术语或"
                    + "其他背景资料时，调用这个工具。参数是一个查询语句。";

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ToolDefinition definition;
    private final int topK;

    /**
     * 用默认配置构造。
     *
     * @param embeddingClient 嵌入客户端，非 null
     * @param vectorStore     向量存储，非 null
     */
    public RetrievalTool(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this(embeddingClient, vectorStore, DEFAULT_TOOL_NAME, DEFAULT_DESCRIPTION, DEFAULT_TOP_K);
    }

    /**
     * 全参构造。
     *
     * @param embeddingClient 嵌入客户端，非 null
     * @param vectorStore     向量存储，非 null
     * @param toolName        工具名，非空
     * @param description     工具描述，非空
     * @param topK            每次检索返回的候选数，必须为正
     */
    public RetrievalTool(EmbeddingClient embeddingClient,
                         VectorStore vectorStore,
                         String toolName,
                         String description,
                         int topK) {
        this.embeddingClient = Objects.requireNonNull(embeddingClient,
                "embeddingClient must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore,
                "vectorStore must not be null");
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
                        + "\"description\":\"要检索的查询语句\"}},"
                        + "\"required\":[\"query\"]}"
        );
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(String arguments) {
        String query = extractQuery(arguments);
        if (query.isBlank()) {
            return ToolResult.failure("Empty query: arguments did not contain a usable query string.");
        }

        List<Float> queryVector = embeddingClient.embed(query);
        List<SearchResult> results = vectorStore.search(queryVector, topK);

        if (results.isEmpty()) {
            return ToolResult.success("No relevant documents found for query: " + query);
        }

        return ToolResult.success(formatResults(results));
    }

    /**
     * 从模型的 arguments 中提取 query 字符串。
     *
     * <p>处理策略：优先尝试提取 JSON 中的 "query" 字段；
     * 提取失败时把 arguments 整体当作 query。
     *
     * <p>不处理复杂 JSON（嵌套、数组、转义码）——这里的目标是
     * 覆盖常见格式，不是实现一个 JSON 解析器。
     */
    private static String extractQuery(String arguments) {
        if (arguments == null) {
            return "";
        }
        String trimmed = arguments.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        // 非 JSON object，直接当 query。
        if (!trimmed.startsWith("{")) {
            return stripQuotes(trimmed);
        }

        // 找 "query" 键。
        int queryKeyIdx = trimmed.indexOf("\"query\"");
        if (queryKeyIdx < 0) {
            return stripQuotes(trimmed);
        }

        // 从 "query" 之后找第一个 ':'，再找后面的第一个 '"'。
        int colonIdx = trimmed.indexOf(':', queryKeyIdx);
        if (colonIdx < 0) {
            return stripQuotes(trimmed);
        }
        int valueStart = trimmed.indexOf('"', colonIdx + 1);
        if (valueStart < 0) {
            return stripQuotes(trimmed);
        }

        // 从 valueStart + 1 开始找匹配的结束引号，处理 \" 转义。
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
        // 没找到结束引号，退回到整体处理。
        return stripQuotes(trimmed);
    }

    /**
     * 去掉首尾的引号（如果存在）。
     * 处理模型直接返回 {@code "some query"} 的场景。
     */
    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 把检索结果拼成给模型看的文本。
     */
    private static String formatResults(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
                    .append(results.get(i).document().content())
                    .append('\n');
        }
        return sb.toString().trim();
    }
}