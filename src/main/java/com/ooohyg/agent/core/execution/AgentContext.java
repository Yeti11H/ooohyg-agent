package com.ooohyg.agent.core.execution;

/**
 * Agent 执行上下文。
 *
 * <p>这是策略执行期间唯一被允许访问的"执行环境"。策略需要的环境信息
 * （本次执行 id、用户输入、步数预算）都从这里获取。
 * <b>策略不直接访问 BaseAgent 的字段</b>，只通过这个接口。
 *
 * <p>为什么是接口而不是类：
 * <ul>
 *   <li>core 层只定义契约，不提供实现；</li>
 *   <li>实现由 runtime 模块提供（例如 {@code DefaultAgentContext}），
 *       这样 core 不需要知道上下文怎么被构造和持有；</li>
 *   <li>测试时可以 mock，不必启动整个 runtime。</li>
 * </ul>
 *
 * <p>为什么只有三个方法：
 * <ul>
 *   <li>这三个是"执行开始前就确定、执行期间不变"的环境字段；</li>
 *   <li>执行过程中产生的进度信息（消息历史、RAG 结果、工具结果）
 *       由策略自己维护，不属于环境；</li>
 *   <li>将来如果要加"不是所有策略都需要的扩展字段"（如 TraceId、
 *       租户 id），再引入一个 attributes() 逃生舱，不在此版本。</li>
 * </ul>
 *
 * <p>本接口所有方法都是只读的。实现必须保证线程安全。
 */
public interface AgentContext {

    /**
     * 本次执行的唯一 id。
     *
     * <p>用于日志关联、事件发布、Checkpoint 索引。整个执行周期内不变。
     *
     * @return 非 null
     */
    ExecutionId executionId();

    /**
     * 用户原始输入。
     *
     * <p>这是本次任务的起点文本。执行过程中策略可以读取它，但不会修改它。
     *
     * @return 非 null、非空白
     */
    String userInput();

    /**
     * 本次执行的步数预算。
     *
     * <p>由 BaseAgent 传入，具体怎么用由策略决定：
     * <ul>
     *   <li>ReAct：作为 think-act-observe 循环的上限；</li>
     *   <li>Plan-and-Execute：作为计划步骤数的上限；</li>
     *   <li>Reflexion：作为重试次数参考（也可以忽略，用自己的重试配置）。</li>
     * </ul>
     *
     * <p>这是"策略级预算"，不是"硬性限制"。策略可以低于它执行完毕；
     * 只有达到上限仍未完成时，才应返回 {@code AgentResult.failure(...)}。
     *
     * @return 步数预算，必须为正整数
     */
    int maxSteps();
}
