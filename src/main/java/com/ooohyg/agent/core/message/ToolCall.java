package com.ooohyg.agent.core.message;

import java.util.Objects;

/**
 * 模型请求调用某个工具的结构化描述。
 *
 * <p>当模型决定调用工具时，会在 ASSISTANT 消息的 {@code toolCalls} 字段中
 * 携带一组 ToolCall。每个 ToolCall 描述"要调用哪个工具、传什么参数"。
 *
 * <p>为什么 {@code arguments} 是 String 而不是 Map：
 * <ul>
 *   <li>模型返回的 arguments 是一段 JSON 字符串，保留原始格式可避免在
 *       core 层引入 JSON 解析依赖；</li>
 *   <li>解析失败的场景（模型生成畸形 JSON）需要由策略层显式处理，
 *       不应在 core 层静默吞掉；</li>
 *   <li>审计和调试时能看到模型的原始输出。</li>
 * </ul>
 *
 * <p>{@code id} 的作用：和 TOOL 角色的消息通过 {@code toolCallId} 配对。
 * 模型发起一次工具调用 → 后端执行 → 把结果作为 TOOL 消息返回，
 * 两者用同一个 id 关联。
 */
public record ToolCall(
        String id,
        String name,
        String arguments
) {

    /**
     * 紧凑构造器：校验三个字段。
     *
     * <p>{@code arguments} 允许为空字符串（无参工具的参数就是 {@code "{}"}
     * 或 {@code ""}），但不允许为 null。
     */
    public ToolCall {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments must not be null");
    }
}