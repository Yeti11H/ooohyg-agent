package com.ooohyg.agent.core.capability.llm;

import com.ooohyg.agent.core.message.ToolCall;

import java.util.List;
import java.util.Objects;

/**
 * 一次模型调用的返回结果。
 *
 * <p>本类是"模型返回了什么"的载体，不承载"谁调用了模型"。
 * 如果需要在日志里关联"这次返回属于哪个策略"，用 {@code ExecutionId}
 * 串联，不在本类上加策略字段。
 *
 * <p><b>字段约束</b>（由紧凑构造器强制）：
 * <ul>
 *   <li>{@code content} 和 {@code toolCalls} <b>至少有一个非空</b>。
 *       两者都空表示模型什么都没返回，属于异常情况；</li>
 *   <li>{@code toolCalls} 非 null 时，若为空 list 则规范化为 null，
 *       统一"空"的表示；非空则做不可变拷贝；</li>
 *   <li>{@code finishReason} 必须非空。adapter 拿不到时应填
 *       {@code "unknown"}，而不是留 null。</li>
 * </ul>
 *
 * <p><b>content 可空的原因：</b>模型请求调用工具时，
 * 主流通用 API（OpenAI、Anthropic、通义千问）返回的 content 经常为 null
 * 或空，此时只有 tool_calls 有内容。如果强制 content 非空，
 * ReAct 的第一轮循环就无法构造响应。
 *
 * <p><b>刻意不包含的字段：</b>
 * <ul>
 *   <li>{@code strategyType}：模型不知道什么是策略，策略归属由
 *       {@code AgentResult} 承载；</li>
 *   <li>{@code tokenUsage}：第一版不做成本统计，等可观测性模块设计
 *       确定后再作为可选扩展加入；</li>
 *   <li>{@code rawResponse}：保留模型原始返回体是 adapter 层的职责，
 *       不在 core 承载。</li>
 * </ul>
 */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        String finishReason
) {

    /**
     * 紧凑构造器：规范化并校验。
     */
    public LlmResponse {
        // ---- 校验 finishReason ----
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        if (finishReason.isBlank()) {
            throw new IllegalArgumentException("finishReason must not be blank");
        }

        // ---- 校验 content 和 toolCalls 至少一个非空 ----
        boolean hasContent = content != null && !content.isBlank();
        boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

        if (!hasContent && !hasToolCalls) {
            throw new IllegalArgumentException(
                    "LlmResponse must have either non-blank content or non-empty toolCalls");
        }

        // ---- 规范化 ----
        // content 空白 → null
        if (content != null && content.isBlank()) {
            content = null;
        }
        // toolCalls 空 list → null；非空 → 不可变拷贝
        if (toolCalls != null) {
            toolCalls = toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
        }
    }

    /**
     * 是否携带工具调用请求。
     *
     * <p>ReAct 策略用它判断：有 → 进 ACTING；无 → 直接进 COMPLETED。
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /**
     * 是否是纯文本回答（无工具调用请求）。
     *
     * <p>等价于 {@code !hasToolCalls()}，提供这个命名是为了让
     * ReAct 的判断代码读起来更顺：{@code if (response.isPlainAnswer())}。
     */
    public boolean isPlainAnswer() {
        return !hasToolCalls();
    }
}