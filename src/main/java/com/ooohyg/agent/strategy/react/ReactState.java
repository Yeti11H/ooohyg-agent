package com.ooohyg.agent.strategy.react;


/**
 * ReAct 策略的内部执行状态。
 *
 * <p>ReAct（Reasoning + Acting）以"思考-行动-观察"循环为核心：
 * <pre>
 *   THINKING ──┬──► ACTING ──► OBSERVING ──► THINKING
 *              │
 *              └──► COMPLETED（无需工具）
 *              │
 *              └──► FAILED（模型调用失败）
 *
 *   循环中的任一状态 ──► FAILED
 * </pre>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #ACTING} 之后必须经过 {@link #OBSERVING}，不允许直接回
 *       {@link #THINKING}——这是 ReAct 的本质：行动后必须观察真实结果，
 *       模型才能基于事实推理，而非臆测工具行为；</li>
 *   <li>{@link #COMPLETED} 和 {@link #FAILED} 为终态，一轮 ReAct 结束后
 *       不再流转。重试由外层策略（如 Reflexion）新建一轮 ReAct 实例完成；</li>
 *   <li>本状态不承载模型完整思维链，只承载结构化决策，
 *       见 {@code ReactDecision}（后续实现）。</li>
 * </ul>
 */
public enum ReactState {

    /** 思考中。模型正在分析当前问题，决定下一步是回答还是调用工具。 */
    THINKING,

    /** 行动中。已选定工具，正在执行。 */
    ACTING,

    /** 观察中。等待工具返回，或处理工具返回结果。 */
    OBSERVING,

    /** 已完成。模型给出最终回答。 */
    COMPLETED,

    /** 失败。本轮 ReAct 放弃。 */
    FAILED;

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 判断流转是否合法。
     *
     * <p>{@code ACTING -> THINKING} 被显式禁止，见类级 Javadoc。
     */
    public boolean canTransitionTo(ReactState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case THINKING -> next == ACTING
                    || next == COMPLETED
                    || next == FAILED;
            case ACTING -> next == OBSERVING
                    || next == FAILED;
            case OBSERVING -> next == THINKING
                    || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}