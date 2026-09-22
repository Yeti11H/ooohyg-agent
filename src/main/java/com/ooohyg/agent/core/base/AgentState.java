package com.ooohyg.agent.core.base;

import com.ooohyg.agent.core.execution.ExecutionStatus;

/**
 * Agent 实例的生命周期状态。
 *
 * <p>这是状态机的第一层，也是最外层：描述"一个 Agent 实例作为执行单元"处于
 * 什么阶段。它不关心本次任务走到哪一步、是否在等工具、是否在重试——那些由
 * {@link ExecutionStatus} 承载；也不关心当前使用哪种执行策略——那由各策略的
 * 内部状态枚举承载。
 *
 * <p>一个 Agent 实例可以执行多次任务，因此 {@code AgentState} 和
 * {@link ExecutionStatus} 是一对多关系：
 * <ul>
 *   <li>第一次执行：IDLE → RUNNING → FINISHED</li>
 *   <li>第二次执行：需要重置到 IDLE，或创建新的 Agent 实例</li>
 * </ul>
 *
 * <p>状态流转：
 * <pre>
 *   IDLE ──► RUNNING ──┬──► FINISHED
 *                       └──► ERROR
 * </pre>
 *
 * <p>终态：{@link #FINISHED}、{@link #ERROR}。进入终态后，本实例不可再流转，
 * 需要外部显式重置或创建新实例。
 *
 * <p>设计说明：本状态机的建模参考了 OpenManus 等开源 Agent 项目的生命周期
 * 设计，但字段命名与行为约束为本框架自有。
 */
public enum AgentState {

    /**
     * 空闲。实例刚创建或已复位，可以接受新任务。
     */
    IDLE,

    /**
     * 运行中。策略正在推进执行。
     *
     * <p>本状态不区分"调模型"、"调工具"或"重试"，具体阶段由
     * {@link ExecutionStatus} 表达。
     */
    RUNNING,

    /**
     * 已完成。策略主动判定任务正常结束。
     */
    FINISHED,

    /**
     * 错误。出现未恢复异常、超时或用户取消。
     *
     * <p>具体原因不在此处承载，由 {@link ExecutionStatus} 的终态
     * （FAILED / CANCELLED / TIMED_OUT）与其关联的上下文记录。
     */
    ERROR;

    /**
     * 是否为终态。终态表示本实例本次执行已结束。
     *
     * @return {@link #FINISHED} 或 {@link #ERROR} 返回 {@code true}
     */
    public boolean isTerminal() {
        return this == FINISHED || this == ERROR;
    }

    /**
     * 是否处于活跃状态。
     *
     * @return 仅 {@link #RUNNING} 返回 {@code true}
     */
    public boolean isActive() {
        return this == RUNNING;
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>合法流转：
     * <pre>
     *   IDLE    → RUNNING
     *   RUNNING → FINISHED
     *   RUNNING → ERROR
     * </pre>
     *
     * <p>不合法示例：
     * <ul>
     *   <li>FINISHED → RUNNING：已结束的实例不可复活</li>
     *   <li>ERROR → RUNNING：错误后需要显式重置或新建实例</li>
     *   <li>IDLE → FINISHED：未运行不能视为完成</li>
     * </ul>
     *
     * @param next 目标状态，为 {@code null} 时返回 {@code false}
     * @return 允许流转返回 {@code true}
     */
    public boolean canTransitionTo(AgentState next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case IDLE -> next == RUNNING;
            case RUNNING -> next == FINISHED || next == ERROR;
            case FINISHED, ERROR -> false;
        };
    }
}