package com.ooohyg.agent.strategy.plan;



/**
 * Plan-and-Execute 策略的内部执行状态。
 *
 * <p>核心是"先规划再执行"：
 * <pre>
 *   PLANNING ──┬──► READY ──► EXECUTING ──┬──► COMPLETED
 *              │                          ├──► PAUSED ──► EXECUTING
 *              │                          └──► FAILED
 *              └──► FAILED
 * </pre>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>{@link #PLANNING} 只生成计划，不执行任何步骤；</li>
 *   <li>{@link #READY} 表示计划已就绪但未开始。此时计划可被外部审查或修改；</li>
 *   <li>{@link #EXECUTING} 期间，每个计划步骤通常委派给
 *       {@code ReactStrategy} 执行，PlanState 不关心每步内部循环；</li>
 *   <li>{@link #PAUSED} 用于等待人工确认或外部条件，恢复后回到
 *       {@link #EXECUTING}；</li>
 *   <li>{@link #COMPLETED} 要求所有计划步骤成功，任一关键步骤失败即进入
 *       {@link #FAILED}。</li>
 * </ul>
 */
public enum PlanState {

    /** 规划中。正在生成任务计划。 */
    PLANNING,

    /** 计划就绪。等待开始执行。 */
    READY,

    /** 执行中。按计划逐步执行。 */
    EXECUTING,

    /** 已暂停。等待人工确认或外部条件。 */
    PAUSED,

    /** 已完成。所有计划步骤成功结束。 */
    COMPLETED,

    /** 失败。计划生成失败，或执行过程中出现不可恢复错误。 */
    FAILED;

    /**
     * 是否为终态。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 是否处于等待外部触发的状态。
     */
    public boolean isWaiting() {
        return this == PAUSED;
    }

    /**
     * 判断流转是否合法。
     */
    public boolean canTransitionTo(PlanState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case PLANNING -> next == READY || next == FAILED;
            case READY -> next == EXECUTING || next == FAILED;
            case EXECUTING -> next == COMPLETED
                    || next == PAUSED
                    || next == FAILED;
            case PAUSED -> next == EXECUTING || next == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}
