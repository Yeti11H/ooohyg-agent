package com.ooohyg.agent.core.capability.tool;

import java.util.Objects;

/**
 * 工具的对外描述。
 *
 * <p>ToolDefinition 是"告诉模型有哪些工具可用"的载体。每次调用模型时，
 * 会把当前可用的工具列表作为 {@code tools} 参数传给模型，模型根据
 * 每个工具的 name / description / parametersSchema 决定是否调用。
 *
 * <p>它和 {@link com.ooohyg.agent.core.message.ToolCall} 的关系：
 * <ul>
 *   <li>{@code ToolDefinition} 是"我有什么工具"——后端告诉模型的；</li>
 *   <li>{@code ToolCall} 是"我要调哪个工具"——模型返回给后端的；</li>
 *   <li>ToolCall 的 {@code name} 必须匹配某个 ToolDefinition 的 {@code name}。</li>
 * </ul>
 *
 * <p><b>为什么 parametersSchema 是 String 而不是 Map：</b>
 * <ul>
 *   <li>core 层不引入 JSON 解析依赖，保持纯净；</li>
 *   <li>它是"模型的输入格式"，原样保留比解析后再序列化更安全；</li>
 *   <li>与 {@code ToolCall.arguments} 的表示方式一致（都是 String 形式的 JSON）。</li>
 * </ul>
 *
 * <p><b>description 的写法建议（给使用者）：</b>
 * <ul>
 *   <li>写清楚"这个工具做什么、什么时候该用"，模型靠它判断是否调用；</li>
 *   <li>太短模型不知道用途，太长浪费 Token；</li>
 *   <li>core 不做长度校验，这是使用者的责任。</li>
 * </ul>
 */
public record ToolDefinition(
        String name,
        String description,
        String parametersSchema
) {

    /**
     * 紧凑构造器：三个字段全部非空校验。
     */
    public ToolDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        Objects.requireNonNull(parametersSchema, "parametersSchema must not be null");
        if (parametersSchema.isBlank()) {
            throw new IllegalArgumentException("parametersSchema must not be blank");
        }
    }
}