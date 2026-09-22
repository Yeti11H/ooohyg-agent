package com.ooohyg.agent.core.result;

import com.ooohyg.agent.strategy.StrategyType;

import java.util.Objects;

/**
 * Agent 执行结果。
 *
 * <p>这是策略执行完成后返回给 BaseAgent 的统一结果对象。三种策略
 * 各自的内部状态（ReactState / PlanState / ReflexionState）不暴露在这里，
 * 只暴露三件事：成功还是失败、输出内容、失败原因。
 *
 * <p><b>成功与失败二选一。</b>不允许"部分成功"：
 * <ul>
 *   <li>{@code success = true} 时，{@code output} 必须非 null，
 *       {@code failureMessage} 必须为 null；</li>
 *   <li>{@code success = false} 时，{@code failureMessage} 必须非 null，
 *       {@code output} 必须为 null。</li>
 * </ul>
 * 这个约束由紧凑构造器强制，构造出来的对象一定是合法的。
 *
 * <p><b>业务失败与技术异常的分工：</b>
 * <ul>
 *   <li>业务失败（达到最大步数、模型判定无解、用户输入不合法）
 *       → 返回 {@code AgentResult.failure(...)}；</li>
 *   <li>技术异常（模型 5xx、工具崩溃、OOM）
 *       → 抛出异常，由 BaseAgent 捕获并处理。</li>
 * </ul>
 * 本类只承载前者。后者走异常路径。
 *
 * <p><b>刻意不包含的字段：</b>
 * <ul>
 *   <li>{@code stackTrace}：异常堆栈由 BaseAgent 记录到日志，不放入结果；</li>
 *   <li>{@code tokenUsage}：等可观测性模块设计确定后再作为可选扩展加入；</li>
 *   <li>{@code checkpoints}：恢复机制是 runtime 层的职责，不属于策略结果。</li>
 * </ul>
 */
public record AgentResult(
        boolean success,
        String output,
        String failureMessage,
        StrategyType strategyType
) {

    /**
     * 紧凑构造器：强制"成功与失败二选一"的约束。
     */
    public AgentResult {
        Objects.requireNonNull(strategyType, "strategyType must not be null");

        if (success) {
            Objects.requireNonNull(output, "Successful result must have non-null output");
            if (failureMessage != null) {
                throw new IllegalArgumentException(
                        "Successful result must not carry a failureMessage");
            }
        } else {
            Objects.requireNonNull(failureMessage, "Failed result must have non-null failureMessage");
            if (output != null) {
                throw new IllegalArgumentException(
                        "Failed result must not carry an output");
            }
        }
    }

    /**
     * 构造一个成功结果。
     *
     * @param output 输出文本，非 null
     * @param type   产生此结果的策略类型，非 null
     */
    public static AgentResult success(String output, StrategyType type) {
        return new AgentResult(true, output, null, type);
    }

    /**
     * 构造一个失败结果。
     *
     * @param message 失败原因，非 null
     * @param type    产生此结果的策略类型，非 null
     */
    public static AgentResult failure(String message, StrategyType type) {
        return new AgentResult(false, null, message, type);
    }
}