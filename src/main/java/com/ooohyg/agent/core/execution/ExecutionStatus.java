package com.ooohyg.agent.core.execution;

import com.ooohyg.agent.core.base.AgentState;


/**
 * 一次 Agent 执行的运行时状态。
 *
 * <p>这是状态机的第二层，位于 {@link AgentState} 之下、策略内部状态之上。
 * {@code AgentState} 只回答"Agent 实例是否还活着"，而本枚举回答"这次执行
 * 具体走到哪了"——尤其是那些在工程上必须显式区分的等待态和重试态。
 *
 * <p>为什么需要这一层：只用 {@link AgentState} 的 4 个状态无法表达以下场景：
 * <ul>
 *   <li>SSE 断连时需要判断是否正在等模型或工具；</li>
 *   <li>Kafka 异步执行时需要区分 RUNNING 和 WAITING_TOOL；</li>
 *   <li>Human-in-the-loop 需要 WAITING_APPROVAL 独立状态；</li>
 *   <li>重试需要 RETRYING 而非笼统的 RUNNING。</li>
 * </ul>
 *
 * <p>状态流转（横向为并列分支，箭头为合法流转）：
 * <pre>
 *   CREATED ──┬──► RUNNING ──┬──► WAITING_MODEL ──┬──► RUNNING
 *             │              │                    ├──► RETRYING
 *             │              │                    └──► TIMED_OUT
 *             │              │
 *             │              ├──► WAITING_TOOL ──┬──► RUNNING
 *             │              │                   ├──► RETRYING
 *             │              │                   └──► TIMED_OUT
 *             │              │
 *             │              ├──► WAITING_APPROVAL ──┬──► RUNNING
 *             │              │                       ├──► CANCELLED
 *             │              │                       └──► TIMED_OUT
 *             │              │
 *             │              ├──► RETRYING ──┬──► RUNNING
 *             │              │                └──► FAILED
 *             │              │
 *             │              ├──► SUCCEEDED
 *             │              ├──► FAILED
 *             │              ├──► CANCELLED
 *             │              └──► TIMED_OUT
 *             │
 *             └──► CANCELLED
 * </pre>
 *
 * <p>终态：{@link #SUCCEEDED}、{@link #FAILED}、{@link #CANCELLED}、
 * {@link #TIMED_OUT}。进入终态后不可流转。
 */
public enum ExecutionStatus {

    /** 已创建。请求刚被接受，尚未进入执行。 */
    CREATED,

    /** 运行中。正在推进策略。 */
    RUNNING,

    /** 等待模型返回。已发出模型请求，等待响应。 */
    WAITING_MODEL,

    /** 等待工具返回。已发出工具调用，等待结果。 */
    WAITING_TOOL,

    /** 等待人工审批。写操作或敏感操作需要人工确认。 */
    WAITING_APPROVAL,

    /** 重试中。上一次尝试失败，正在等待下一次尝试。 */
    RETRYING,

    /** 成功。任务正常完成。 */
    SUCCEEDED,

    /** 失败。不可恢复错误，或重试已耗尽。 */
    FAILED,

    /** 已取消。用户主动取消。 */
    CANCELLED,

    /** 已超时。整体或单步超时。 */
    TIMED_OUT;

    /**
     * 是否为终态。
     *
     * @return {@link #SUCCEEDED}、{@link #FAILED}、{@link #CANCELLED}、
     *         {@link #TIMED_OUT} 返回 {@code true}
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED
                || this == CANCELLED || this == TIMED_OUT;
    }

    /**
     * 是否处于等待外部输入的状态。
     *
     * <p>等待态的特点是：Runtime 不推进，等待外部事件触发流转。
     *
     * @return {@link #WAITING_MODEL}、{@link #WAITING_TOOL}、
     *         {@link #WAITING_APPROVAL} 返回 {@code true}
     */
    public boolean isWaiting() {
        return this == WAITING_MODEL || this == WAITING_TOOL
                || this == WAITING_APPROVAL;
    }

    /**
     * 是否处于活跃推进状态。
     *
     * @return {@link #RUNNING} 或 {@link #RETRYING} 返回 {@code true}
     */
    public boolean isActive() {
        return this == RUNNING || this == RETRYING;
    }

    /**
     * 判断从当前状态到目标状态的流转是否合法。
     *
     * <p>核心约束：
     * <ul>
     *   <li>终态不可再流转；</li>
     *   <li>等待态不能直接跳到另一个等待态，必须先回 {@link #RUNNING}；</li>
     *   <li>{@link #RETRYING} 只能回到 {@link #RUNNING} 或进入失败终态；</li>
     *   <li>{@link #WAITING_APPROVAL} 不可进入 {@link #RETRYING}——
     *       被拒绝是业务决策，不是技术失败。</li>
     * </ul>
     *
     * @param next 目标状态，为 {@code null} 时返回 {@code false}
     * @return 允许流转返回 {@code true}
     */
    public boolean canTransitionTo(ExecutionStatus next) {
        if (next == null) {
            return false;
        }
        return switch (this) {
            case CREATED -> next == RUNNING || next == CANCELLED;

            case RUNNING -> next == WAITING_MODEL
                    || next == WAITING_TOOL
                    || next == WAITING_APPROVAL
                    || next == RETRYING
                    || next == SUCCEEDED
                    || next == FAILED
                    || next == CANCELLED
                    || next == TIMED_OUT;

            case WAITING_MODEL, WAITING_TOOL -> next == RUNNING
                    || next == RETRYING
                    || next == FAILED
                    || next == CANCELLED
                    || next == TIMED_OUT;

            case WAITING_APPROVAL -> next == RUNNING
                    || next == CANCELLED
                    || next == TIMED_OUT;

            case RETRYING -> next == RUNNING
                    || next == FAILED
                    || next == CANCELLED
                    || next == TIMED_OUT;

            case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT -> false;
        };
    }
}