package com.ooohyg.agent.strategy.reflexion;
/**
 * Reflexion 策略的内部执行状态。
 *
 * <p>Reflexion 是"包裹型"策略：它不直接执行任务，而是包裹在另一个策略
 * 外面，评估其执行结果，并在不合格时驱动修正与重跑。
 *
 * <p>组合关系（本框架推荐）：
 * <pre>
 *   ReflexionStrategy
 *     └── PlanAndExecuteStrategy
 *           └── ReactStrategy
 *                 └── Tool / RAG / Memory / Sandbox
 * </pre>
 *
 * <p>内部循环：
 * <pre>
 *   EVALUATING ──┬──► ACCEPTED（通过，终态）
 *                ├──► REVISING ──► REEXECUTING ──► EVALUATING
 *                ├──► EXHAUSTED（重试用尽，终态）
 *                └──► FAILED（评估过程本身失败，终态）
 * </pre>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #EVALUATING} 只做评估，不修改被包裹策略的任何状态；</li>
 *   <li>{@link #REVISING} 允许修改计划、参数或更换被包裹策略；
 *       "修正"必须是显式动作，不能隐式发生；</li>
 *   <li>{@link #REEXECUTING} 会重新驱动被包裹策略，被包裹策略的内部状态
 *       （如 {@link com.ooohyg.agent.strategy.plan.PlanState}）会从各自的
 *       初始态重新开始，但 ReflexionState 本身保持在 {@link #REEXECUTING}
 *       直到该轮执行结束；</li>
 *   <li>{@link #EXHAUSTED} 和 {@link #FAILED} 的区别：
 *       EXHAUSTED 表示"重试次数用尽"，流程本身运行正常；
 *       FAILED 表示"评估过程本身出错"，属于技术故障。</li>
 * </ul>
 */
public enum ReflexionState {

    /** 评估中。正在检查被包裹策略的执行结果。 */
    EVALUATING,

    /** 已接受。结果通过评估。 */
    ACCEPTED,

    /** 修正中。结果不合格，正在修改计划或参数。 */
    REVISING,

    /** 重新执行中。已修正，正在驱动被包裹策略重新执行。 */
    REEXECUTING,

    /** 重试耗尽。达到最大重试次数仍未通过。 */
    EXHAUSTED,

    /** 失败。评估过程本身出现错误。 */
    FAILED;

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == ACCEPTED || this == EXHAUSTED || this == FAILED;
    }

    /**
     * 判断流转是否合法。
     */
    public boolean canTransitionTo(ReflexionState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case EVALUATING -> next == ACCEPTED
                    || next == REVISING
                    || next == EXHAUSTED
                    || next == FAILED;
            case REVISING -> next == REEXECUTING || next == FAILED;
            case REEXECUTING -> next == EVALUATING || next == FAILED;
            case ACCEPTED, EXHAUSTED, FAILED -> false;
        };
    }
}
