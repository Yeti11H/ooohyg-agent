package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import com.ooohyg.agent.core.message.ToolCall;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的本地模型客户端。
 *
 * <p>不接任何外部 LLM API，用一组可解释的规则模拟"模型的决策"：
 * 需要计算时调 {@code add}、需要查资料时调 {@code retrieval}、
 * 需要回忆时调 {@code recall_memory}，其余情况直接给出文本回答。
 * 它的定位与 {@code InMemoryVectorStore} / {@code EchoTool} 一致——
 * 让用户不依赖任何外部服务就能把 ReAct / Plan / Reflexion / RAG /
 * 记忆等全部模块端到端跑通，作为接入真实模型前的骨架验证。
 *
 * <p><b>决策规则（按顺序匹配最后一条消息）：</b>
 * <ol>
 *   <li>最后一条是 TOOL 消息 → 基于工具结果拼出最终答案（结束循环）；</li>
 *   <li>USER 消息含"分解为若干步骤"（Plan 策略的规划指令）
 *       → 返回计划文本，每行一步；</li>
 *   <li>USER 消息含"修正建议"（Reflexion 策略的反馈注入）
 *       → 返回包含 {@link #finalKeywords} 的修正答案；</li>
 *   <li>USER 消息含"当前步骤"且涉及 add 工具 → 返回 {@code add} 调用；</li>
 *   <li>USER 消息含"检索"/"查询" 且可用工具含 {@code retrieval}
 *       → 返回检索调用；</li>
 *   <li>USER 消息含"回忆"/"记忆" 且可用工具含 {@code recall_memory}
 *       → 返回回忆调用；</li>
 *   <li>其余情况 → 返回纯文本回答。</li>
 * </ol>
 *
 * <p>实现约束：无实例可变状态（构造后全 final），天然线程安全；
 * 相同输入 → 相同输出（纯规则，无随机性）。
 */
public final class RuleBasedLlmClient implements LlmClient {

    /** add 工具名，与 {@link AddTool} 的 definition 保持一致。 */
    private static final String TOOL_ADD = "add";
    /** 检索工具名，与 {@code RetrievalTool} 默认名一致。 */
    private static final String TOOL_RETRIEVAL = "retrieval";
    /** 回忆工具名，与 {@code MemoryRecallTool} 默认名一致。 */
    private static final String TOOL_RECALL = "recall_memory";

    /** 匹配 "计算 a+b" / "计算 a 加 b" 形式的加法表达式。 */
    private static final Pattern ADD_PATTERN =
            Pattern.compile("(\\d+)\\s*[+加]\\s*(\\d+)");

    /** 最终答案里必须包含的关键词（用于 Reflexion 修正路径）。 */
    private final List<String> finalKeywords;

    public RuleBasedLlmClient() {
        this(List.of("最终答案"));
    }

    public RuleBasedLlmClient(List<String> finalKeywords) {
        Objects.requireNonNull(finalKeywords, "finalKeywords must not be null");
        this.finalKeywords = List.copyOf(finalKeywords);
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Objects.requireNonNull(messages, "messages must not be null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        Message last = messages.get(messages.size() - 1);

        // 规则 1：最后是工具结果 → 给最终答案。
        if (last.role() == MessageRole.TOOL) {
            return answer("根据工具返回的结果：" + last.content());
        }

        // 规则 2：Plan 策略的规划指令。
        if (last.role() == MessageRole.USER && last.content().contains("分解为若干步骤")) {
            return planFor(last.content());
        }

        // 规则 3：Reflexion 修正反馈。
        if (last.role() == MessageRole.USER && last.content().contains("修正建议")) {
            return answer(reflexionAnswer());
        }

        // 规则 3.5：收尾步骤（Plan 策略的"整理最终答案"）——直接给出结果，
        // 避免把"原始任务"里的加法表达式再次当作待计算表达式触发工具调用。
        Matcher wrapUpMatcher = ADD_PATTERN.matcher(last.content());
        if (last.role() == MessageRole.USER
                && last.content().contains("整理为最终答案")
                && wrapUpMatcher.find()) {
            long sum = Long.parseLong(wrapUpMatcher.group(1))
                    + Long.parseLong(wrapUpMatcher.group(2));
            return answer("最终答案：" + sum);
        }

        // 规则 4：需要调加法工具。
        Matcher addMatcher = ADD_PATTERN.matcher(last.content());
        if (addMatcher.find() && hasTool(tools, TOOL_ADD)) {
            String arguments = addMatcher.group(1) + "+" + addMatcher.group(2);
            return toolCall(TOOL_ADD, arguments);
        }

        // 规则 5：需要检索知识库。
        if (containsAny(last.content(), "检索", "查询知识", "查一下", "知识库")
                && hasTool(tools, TOOL_RETRIEVAL)) {
            return toolCall(TOOL_RETRIEVAL, jsonQuery(extractQuery(last.content())));
        }

        // 规则 6：需要回忆长期记忆。
        if (containsAny(last.content(), "回忆", "记忆", "以前说")
                && hasTool(tools, TOOL_RECALL)) {
            return toolCall(TOOL_RECALL, jsonQuery(extractQuery(last.content())));
        }

        // 规则 7：默认纯文本回答。
        return answer("我无法通过现有工具完成该请求，请补充信息。");
    }

    // ---------- 响应构造 ----------

    private LlmResponse answer(String content) {
        return new LlmResponse(content, null, "stop");
    }

    private LlmResponse toolCall(String name, String arguments) {
        ToolCall call = new ToolCall(UUID.randomUUID().toString(), name, arguments);
        return new LlmResponse(null, List.of(call), "tool_calls");
    }

    // ---------- 规则辅助 ----------

    /** 为规划指令生成一份两步骤计划（第一步计算，第二步整理答案）。 */
    private LlmResponse planFor(String task) {
        Matcher addMatcher = ADD_PATTERN.matcher(task);
        if (addMatcher.find()) {
            String a = addMatcher.group(1);
            String b = addMatcher.group(2);
            return answer("第一步：使用 add 工具计算 " + a + "+" + b + " 的和\n"
                    + "第二步：把上一步的计算结果整理为最终答案");
        }
        return answer("第一步：检索知识库获取相关信息\n"
                + "第二步：基于检索结果整理最终答案");
    }

    private String reflexionAnswer() {
        return String.join("，", finalKeywords) + "：已完成任务，结果如下。";
    }

    /** 从任务文本中提取查询语句：优先取冒号/引号后的内容，否则取整句。 */
    private static String extractQuery(String text) {
        int colon = text.lastIndexOf('：');
        if (colon >= 0 && colon < text.length() - 1) {
            return text.substring(colon + 1).trim();
        }
        int q = text.lastIndexOf('"');
        if (q >= 0 && q < text.length() - 1 && text.lastIndexOf('"', q - 1) >= 0) {
            int start = text.lastIndexOf('"', q - 1);
            return text.substring(start + 1, q).trim();
        }
        return text.trim();
    }

    private static String jsonQuery(String query) {
        return "{\"query\":\"" + query.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    private static boolean hasTool(List<ToolDefinition> tools, String name) {
        if (tools == null) {
            return false;
        }
        for (ToolDefinition def : tools) {
            if (name.equals(def.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
