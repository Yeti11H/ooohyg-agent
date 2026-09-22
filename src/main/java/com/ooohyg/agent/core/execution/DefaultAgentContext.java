package com.ooohyg.agent.core.execution;

import java.util.Objects;

/**
 * {@link AgentContext} 的默认实现。
 *
 * <p>用 Java 21 的 {@code record} 声明，理由是：
 * <ul>
 *   <li>字段固定三个，结构不复杂；</li>
 *   <li>构造后不可变，符合"执行环境只读"的定位；</li>
 *   <li>record 自动生成的访问器方法（{@code executionId()}、{@code userInput()}、
 *       {@code maxSteps()}）与接口方法签名一致，<b>不需要手写任何 @Override 方法</b>。</li>
 * </ul>
 *
 * <p>为什么不是 class：
 * <ul>
 *   <li>class 会引入不必要的 setter，破坏只读语义；</li>
 *   <li>record 自带的 equals/hashCode 在测试和日志里更友好。</li>
 * </ul>
 *
 * <p>本类不包含：
 * <ul>
 *   <li>消息历史（{@code List<Message>}）——属于策略内部状态；</li>
 *   <li>RAG 检索结果、工具调用结果——同上；</li>
 *   <li>扩展属性 Map——第一版不引入，等有真实需求再加。</li>
 * </ul>
 */
public record DefaultAgentContext(
        ExecutionId executionId,
        String userInput,
        int maxSteps
) implements AgentContext {

    /**
     * 紧凑构造器：校验三个字段的合法性。
     */
    public DefaultAgentContext {
        Objects.requireNonNull(executionId, "executionId must not be null");
        Objects.requireNonNull(userInput, "userInput must not be null");
        if (userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive, got: " + maxSteps);
        }
    }
}