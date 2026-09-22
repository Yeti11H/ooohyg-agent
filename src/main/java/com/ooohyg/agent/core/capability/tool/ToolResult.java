package com.ooohyg.agent.core.capability.tool;

import java.util.Objects;

/**
 * 一次工具调用的返回结果。
 *
 * <p>这是"工具执行完了，结果是什么"的载体。策略在 ReAct 的 ACTING 阶段
 * 调用 {@code Tool.execute(arguments)}，拿到 ToolResult 后转成一条
 * {@link com.ooohyg.agent.core.message.Message#tool(String, String) TOOL 消息}
 * 追加到对话历史，进入 OBSERVING。
 *
 * <p><b>成功与失败二选一</b>（由紧凑构造器强制）：
 * <ul>
 *   <li>{@code success = true} 时，{@code output} 必须非 null，
 *       {@code errorMessage} 必须为 null；</li>
 *   <li>{@code success = false} 时，{@code errorMessage} 必须非 null，
 *       {@code output} 必须为 null。</li>
 * </ul>
 * 与 {@link com.ooohyg.agent.core.result.AgentResult} 的约束完全对称，
 * 目的是让策略能以同一套模式处理两种结果。
 *
 * <p><b>为什么不复用 AgentResult：</b>
 * <ul>
 *   <li>{@code AgentResult} 携带 {@code StrategyType}，而工具不知道策略；</li>
 *   <li>工具失败和 Agent 失败的语义不同：前者是"这一步没成功"，
 *       后者是"整个任务没成功"；</li>
 *   <li>两者的调用方不同：ToolResult 给策略，AgentResult 给 BaseAgent。</li>
 * </ul>
 *
 * <p><b>失败是返回值，不是异常——这是本类最重要的设计决策：</b>
 * <ul>
 *   <li>ReAct 的本质是"观察真实结果再推理"。工具失败本身就是一种真实观察，
 *       必须让模型看到，模型才能换参数、换工具、或改用别的方案；</li>
 *   <li>如果工具失败抛异常，ReAct 循环会直接中断，模型失去修正机会，
 *       这违背 ReAct 的初衷；</li>
 *   <li>业务失败（除零、文件不存在、参数不合法）走本类；
 *       技术故障（底层 SDK 崩溃、依赖服务 5xx、工具内部 NPE）走
 *       {@link com.ooohyg.agent.core.exception.AgentException}。
 *       判据：模型换参数能不能解决？能 → 业务失败；不能 → 技术故障。</li>
 * </ul>
 *
 * <p><b>output 的写法建议（给工具实现者）：</b>
 * <ul>
 *   <li>它是给模型看的，不是给程序员看的——用自然语言，别塞结构化 dump；</li>
 *   <li>简洁优先：模型不需要整个 HTTP 响应体，需要的是关键信息；</li>
 *   <li>如果输出很长，考虑截断并标注，避免撑爆上下文。</li>
 * </ul>
 */
public record ToolResult(
        boolean success,
        String output,
        String errorMessage
) {

    /**
     * 紧凑构造器：强制"成功与失败二选一"。
     */
    public ToolResult {
        if (success) {
            Objects.requireNonNull(output, "Successful ToolResult must have non-null output");
            if (errorMessage != null) {
                throw new IllegalArgumentException(
                        "Successful ToolResult must not carry an errorMessage");
            }
        } else {
            Objects.requireNonNull(errorMessage,
                    "Failed ToolResult must have non-null errorMessage");
            if (output != null) {
                throw new IllegalArgumentException(
                        "Failed ToolResult must not carry an output");
            }
        }
    }

    /**
     * 构造一个成功的工具结果。
     *
     * @param output 工具输出文本，非 null。通常是给模型看的自然语言摘要。
     */
    public static ToolResult success(String output) {
        return new ToolResult(true, output, null);
    }

    /**
     * 构造一个失败的（业务层面）工具结果。
     *
     * <p>这不是"技术故障"。失败的 ToolResult 会转成 TOOL 消息喂回给模型，
     * 让模型决定下一步——重试、换参数、换工具，或放弃。
     *
     * @param errorMessage 失败原因，非 null。写得让模型能据此修正。
     */
    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }
}