package com.ooohyg.agent.strategy;


import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.result.AgentResult;

/**
 * Agent 执行策略。
 *
 * <p>这是框架的核心契约：三种执行范式（ReAct / Plan-and-Execute / Reflexion）
 * 都是它的实现。BaseAgent 只依赖本接口，不依赖任何具体策略。
 *
 * <p>关键设计决策：
 * <ul>
 *   <li><b>循环由策略自己驱动。</b>BaseAgent 只调用一次 {@code execute}，
 *       策略内部自己循环（ReAct 循环 think-act-observe，Plan 循环
 *       plan-execute-step，Reflexion 循环 evaluate-revise-reexecute）。
 *       这样组合场景下，Reflexion 可以直接调 Plan 的 execute，
 *       不需要 BaseAgent 参与。</li>
 *   <li><b>入参只有 AgentContext。</b>循环次数、上一次结果等状态由策略自己
 *       维护。ctx 提供"环境信息"（执行 id、请求、预算），不提供"进度信息"。</li>
 *   <li><b>返回值统一为 AgentResult。</b>成功/失败都通过返回值表达，
 *       技术异常通过抛出传递。见下方"错误契约"。</li>
 * </ul>
 *
 * <p><b>错误契约：</b>
 * <pre>
 *   技术异常（模型 5xx、工具崩溃、OOM）
 *     → 抛出异常，由 BaseAgent 捕获并转为 ERROR 状态
 *
 *   业务失败（达到 maxSteps、模型判定无解、用户输入不合法）
 *     → 返回 AgentResult.failure(...)，BaseAgent 正常接收
 *
 *   可重试 / 不可重试
 *     → 由异常类型区分（后续在可靠性模块实现
 *       RetryableException / NonRetryableException）
 * </pre>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须是可重入的（同一个策略实例可能被多次调用）；</li>
 *   <li>实现不得依赖线程本地状态（并发场景下会串）；</li>
 *   <li>实现不得直接修改 AgentContext 之外的状态（副作用要显式）。</li>
 * </ul>
 */
public interface ExecutionStrategy {

    /**
     * 执行一次完整的 Agent 任务。
     *
     * <p>方法返回时，无论成功还是业务失败，执行都已结束。技术异常通过
     * 抛出表达，调用方（BaseAgent）负责捕获并转为失败状态。
     *
     * @param ctx 执行上下文，非 null
     * @return 执行结果，永不为 null
     * @throws RuntimeException 技术异常（例如模型调用失败、工具崩溃）
     */
    AgentResult execute(AgentContext ctx);

    /**
     * 策略类型。
     *
     * <p>用于日志、事件、监控和序列化。BaseAgent 不需要根据它做判断——
     * 如果需要"根据类型做不同的事"，说明抽象泄漏了。
     */
    StrategyType type();
}