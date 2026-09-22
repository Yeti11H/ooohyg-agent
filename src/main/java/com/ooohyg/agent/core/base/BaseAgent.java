package com.ooohyg.agent.core.base;

import com.ooohyg.agent.core.base.AgentState;
import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.execution.DefaultAgentContext;
import com.ooohyg.agent.core.execution.ExecutionId;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;

import java.util.Objects;

/**
 * Agent 执行容器。
 *
 * <p>BaseAgent 是"Agent 实例"的具体实现。它负责四件事：
 * <ol>
 *   <li>管理生命周期状态（{@link AgentState}）；</li>
 *   <li>根据用户输入创建执行上下文（{@link AgentContext}）；</li>
 *   <li>把执行委托给策略（{@link ExecutionStrategy}）；</li>
 *   <li>统一处理异常并推进状态流转。</li>
 * </ol>
 *
 * <p><b>BaseAgent 不做什么：</b>
 * <ul>
 *   <li>不写循环——循环由策略内部驱动（ReAct 循环 think-act-observe，
 *       Plan 循环 plan-execute-step）；</li>
 *   <li>不调模型、不调工具、不做 RAG——那些由策略通过能力接口实现；</li>
 *   <li>不发布事件——第一版不引入事件依赖；</li>
 *   <li>不提供 {@code setState()}——状态只能通过内部流转修改。</li>
 * </ul>
 *
 * <p><b>扩展方式：</b>通过替换策略（{@link ExecutionStrategy}）来改变执行行为，
 * <b>而不是继承 BaseAgent</b>。这是策略模式相对于模板方法模式的核心优势。
 *
 * <p><b>实例复用：</b>本版本不支持复用。一个 BaseAgent 实例只执行一次任务；
 * 需要再次执行时，创建新实例。原因：状态机一旦进入终态（FINISHED / ERROR）
 * 就不再流转，复位会引入额外的状态流转分支，第一版不引入。
 *
 * <p><b>线程安全：</b>本类<b>不是线程安全的</b>。同一个实例被多线程同时调用
 * {@link #run(String)} 会产生竞态（状态校验通过后可能被另一个线程推进）。
 * 需要并发调用时，每个线程使用独立实例。
 */
public class BaseAgent {

    private final String name;
    private final ExecutionStrategy strategy;
    private final int maxSteps;

    /**
     * 当前生命周期状态。
     *
     * <p>初始为 {@link AgentState#IDLE}。本字段只能通过
     * {@link #transitionTo(AgentState)} 修改，外部只能读。
     */
    private AgentState state = AgentState.IDLE;

    /**
     * 构造一个 Agent。
     *
     * @param name     Agent 名称，用于日志。非空。
     * @param strategy 执行策略，非 null。
     * @param maxSteps 步数预算，必须为正整数。透传给 AgentContext。
     * @throws NullPointerException     name 或 strategy 为 null
     * @throws IllegalArgumentException maxSteps 非正
     */
    public BaseAgent(String name, ExecutionStrategy strategy, int maxSteps) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive, got: " + maxSteps);
        }
        this.maxSteps = maxSteps;
    }

    /**
     * 执行一次 Agent 任务。
     *
     * <p><b>调用契约：</b>
     * <ul>
     *   <li>调用前 {@code state} 必须是 {@link AgentState#IDLE}，否则抛
     *       {@link IllegalStateException}；</li>
     *   <li>成功返回时 {@code state} 为 {@link AgentState#FINISHED}，
     *       返回值可能是 success=true 也可能是 success=false（业务失败）；</li>
     *   <li>技术异常抛出时 {@code state} 为 {@link AgentState#ERROR}，
     *       异常原样向上抛（或包装为 {@link AgentException}）。</li>
     * </ul>
     *
     * <p><b>业务失败 vs 技术异常：</b>
     * <ul>
     *   <li>业务失败（达到 maxSteps、模型判定无解）→ 策略返回
     *       {@code AgentResult.failure(...)}，本方法正常返回；</li>
     *   <li>技术异常（模型 5xx、工具崩溃）→ 策略抛出异常，
     *       本方法捕获后转 ERROR 状态并重新抛出。</li>
     * </ul>
     * 上层通过"返回值看业务结论、catch 看技术故障"的两种通道获取信息。
     *
     * @param userInput 用户输入，非空、非空白
     * @return 执行结果，永不为 null
     * @throws IllegalStateException 实例不在 IDLE 状态
     * @throws IllegalArgumentException userInput 为空白
     * @throws AgentException 技术故障
     */
    public AgentResult run(String userInput) {
        // ---- 前置校验（在 try 外）----
        // 状态非法是"调用方误用"，不是"执行中出错"，
        // 所以抛 IllegalStateException 而不是 AgentException，
        // 也不进入下面的 catch 分支。
        if (state != AgentState.IDLE) {
            throw new IllegalStateException(
                    "Agent[" + name + "] cannot run from state " + state
                            + " (expected IDLE). Create a new instance instead.");
        }
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalArgumentException("userInput must not be blank");
        }

        // ---- 状态切到 RUNNING ----
        transitionTo(AgentState.RUNNING);

        // ---- 创建执行上下文 ----
        // ctx 是"环境快照"：执行期间不会改变，策略通过它读取
        // executionId、userInput、maxSteps。
        ExecutionId executionId = ExecutionId.generate();
        AgentContext ctx = new DefaultAgentContext(executionId, userInput, maxSteps);

        // ---- 委托给策略，并统一处理异常 ----
        try {
            AgentResult result = strategy.execute(ctx);
            // 业务失败也走这里：成功或失败由 result.success() 表达，
            // 状态统一流转到 FINISHED。ERROR 专门留给技术异常。
            transitionTo(AgentState.FINISHED);
            return result;
        } catch (AgentException e) {
            // 策略已经明确抛出 AgentException：状态转 ERROR，原样重抛。
            transitionTo(AgentState.ERROR);
            throw e;
        } catch (Exception e) {
            // 策略抛出未预期的异常（NPE、RuntimeException 等）：
            // 这属于策略实现的 bug，包装为 AgentException 后重抛，
            // 保证上层只需要 catch 一种异常类型。
            transitionTo(AgentState.ERROR);
            throw new AgentException(
                    "Unexpected error during agent execution (name=" + name + ")", e);
        }
    }

    /**
     * 状态流转。唯一允许修改 {@link #state} 的入口。
     *
     * <p>私有且不暴露给子类/外部。流转合法性由
     * {@link AgentState#canTransitionTo(AgentState)} 校验，非法流转直接
     * 抛 {@link IllegalStateException}——这是框架的内部 bug，不应被上层
     * 当作业务错误吞掉。
     *
     * @param next 目标状态，非 null
     */
    private void transitionTo(AgentState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal state transition for Agent[" + name + "]: "
                            + state + " -> " + next);
        }
        this.state = next;
    }

    // ---------- 只读访问器 ----------

    /**
     * Agent 名称。
     */
    public String name() {
        return name;
    }

    /**
     * 当前生命周期状态。
     *
     * <p>只读。状态修改只能通过 {@link #run(String)} 间接触发。
     */
    public AgentState state() {
        return state;
    }

    /**
     * 是否处于终态。
     */
    public boolean isTerminal() {
        return state.isTerminal();
    }
}