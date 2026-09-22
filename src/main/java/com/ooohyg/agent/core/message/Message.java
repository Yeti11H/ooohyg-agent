package com.ooohyg.agent.core.message;

import java.util.List;
import java.util.Objects;

/**
 * 一条对话消息。
 *
 * <p>Message 是策略与模型交互的通用语言。每次模型调用都把当前
 * {@code List<Message>} 发给模型；模型返回的 ASSISTANT 消息追加进列表，
 * 形成多轮对话。
 *
 * <p><b>字段互斥关系</b>（由紧凑构造器强制）：
 * <pre>
 *   role = SYSTEM / USER  →  toolCallId 为 null，toolCalls 为 null
 *   role = ASSISTANT      →  toolCallId 为 null；
 *                            toolCalls 可空（纯文本回答）
 *                            或非空（请求调用工具）
 *   role = TOOL           →  toolCallId 非空，toolCalls 为 null
 * </pre>
 *
 * <p><b>content 的约束：</b>
 * <ul>
 *   <li>SYSTEM / USER / TOOL 角色的 content 必须非空白；</li>
 *   <li>ASSISTANT 角色的 content 允许为 null（模型只请求工具而不输出文本时），
 *       但 content 和 toolCalls 至少要有一个非空，否则构造失败。</li>
 * </ul>
 *
 * <p><b>刻意不包含的字段：</b>
 * <ul>
 *   <li>{@code name}：部分 LLM API 支持给消息命名，但工具信息已通过
 *       toolCallId 配对，不需要额外 name；</li>
 *   <li>{@code metadata}：core 层不引入逃生舱式扩展字段，
 *       等有真实需求再加。</li>
 * </ul>
 *
 * <p>本类是值对象，不可变。构造后内部字段不再变化。
 */
public record Message(
        MessageRole role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {

    /**
     * 紧凑构造器：强制字段互斥关系，并做不可变规范化。
     */
    public Message {
        Objects.requireNonNull(role, "role must not be null");

        switch (role) {
            case SYSTEM, USER -> {
                requireNonBlankContent(role, content);
                requireNull(toolCallId, "toolCallId", role);
                requireNull(toolCalls, "toolCalls", role);
            }

            case ASSISTANT -> {
                requireNull(toolCallId, "toolCallId", role);

                boolean hasContent = content != null && !content.isBlank();
                boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

                if (!hasContent && !hasToolCalls) {
                    throw new IllegalArgumentException(
                            "ASSISTANT message must have either non-blank content or non-empty toolCalls");
                }

                // 规范化：
                // - 空 list 转为 null，避免下游处理"两种空"
                // - 非空 list 做不可变拷贝，防止外部修改穿透
                if (toolCalls != null) {
                    toolCalls = toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
                }

                // content 为空字符串时也规范化为 null
                if (content != null && content.isBlank()) {
                    content = null;
                }
            }

            case TOOL -> {
                requireNonBlankContent(role, content);
                requireNonBlank(toolCallId, "toolCallId", role);
                requireNull(toolCalls, "toolCalls", role);
            }
        }
    }

    // ---------- 校验辅助方法 ----------

    private static void requireNonBlankContent(MessageRole role, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException(
                    role + " message must have non-blank content");
        }
    }

    private static void requireNonBlank(String value, String field, MessageRole role) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank for " + role + " message");
        }
    }

    private static void requireNull(Object value, String field, MessageRole role) {
        if (value != null) {
            throw new IllegalArgumentException(
                    field + " must be null for " + role + " message");
        }
    }

    // ---------- 静态工厂方法 ----------

    /**
     * 系统消息。
     */
    public static Message system(String content) {
        return new Message(MessageRole.SYSTEM, content, null, null);
    }

    /**
     * 用户消息。
     */
    public static Message user(String content) {
        return new Message(MessageRole.USER, content, null, null);
    }

    /**
     * 助手纯文本消息。
     */
    public static Message assistant(String content) {
        return new Message(MessageRole.ASSISTANT, content, null, null);
    }

    /**
     * 助手工具调用消息。
     *
     * @param content   可为 null（模型只请求工具），也可为非空（模型边说边请求）
     * @param toolCalls 非空列表
     */
    public static Message assistantWithToolCalls(String content, List<ToolCall> toolCalls) {
        return new Message(MessageRole.ASSISTANT, content, null, toolCalls);
    }

    /**
     * 工具执行结果消息。
     *
     * @param toolCallId 关联的 ToolCall id，非空白
     * @param content    工具输出文本，非空白
     */
    public static Message tool(String toolCallId, String content) {
        return new Message(MessageRole.TOOL, content, toolCallId, null);
    }

    // ---------- 便捷方法 ----------

    /**
     * 是否携带工具调用请求。
     */
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}