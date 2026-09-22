package com.ooohyg.agent.strategy;

import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.execution.DefaultAgentContext;
import com.ooohyg.agent.core.execution.ExecutionId;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.StrategyType;
import com.ooohyg.agent.strategy.reflexion.EvaluationResult;
import com.ooohyg.agent.strategy.reflexion.Evaluator;

import java.util.Objects;

/**
 * Reflexion 执行策略。
 *
 * <p>包裹型策略：它自己不执行任务，而是包裹一个子策略，评估其结果，
 * 不合格时生成修正建议并驱动子策略重跑。循环如下：
 * <pre>
 *   REEXECUTING   驱动子策略执行（第一次尝试无反馈，后续尝试带反馈）
 *   EVALUATING    调 Evaluator 判断结果是否达标
 *     ├── 达标    → ACCEPTED，返回结果
 *     └── 不达标  → REVISING，保存 feedback，进入下一轮
 *   循环耗尽      → EXHAUSTED，返回 failure
 * </pre>
 *
 * <p><b>为什么是包裹型，而不是又写一套执行逻辑：</b>
 * <ul>
 *   <li>Reflexion 的语义是"对已有执行结果的再加工"，它不需要知道
 *       被包裹策略的内部细节（是否用了工具、是否分了步）；</li>
 *   <li>复用让组合成为可能：
 *       {@code Reflexion(Plan(React))} 或 {@code Reflexion(React)} 都成立；
 *   <li>把"评估—修正—重跑"抽象成独立一层，符合
 *       {@link com.ooohyg.agent.strategy.reflexion.ReflexionState}
 *       的状态机描述。</li>
 * </ul>
 *
 * <p><b>为什么评估、子策略都从构造器注入：</b>
 * <ul>
 *   <li>评估标准是场景相关的（代码看编译、文案看调性），
 *       不可能硬编码——见 {@link Evaluator} 的 Javadoc；</li>
 *   <li>子策略可以是 ReAct、Plan，或未来别的实现。
 *       Reflexion 只依赖 {@link ExecutionStrategy} 接口；</li>
 *   <li>测试时二者都可以注入 mock，Reflexion 的循环逻辑被独立验证。</li>
 * </ul>
 *
 * <p><b>maxAttempts 与 ctx.maxSteps() 的关系：</b>
 * <ul>
 *   <li>{@code maxAttempts} 是"子策略最多被驱动几次"，由构造器传入，
 *       是 Reflexion 自身的配置；</li>
 *   <li>{@code ctx.maxSteps()} 是"每次驱动子策略时可用的步数预算"，
 *       原样传给子策略，Reflexion 不削减它；</li>
 *   <li>两者是不同维度：前者管"重试几次"，后者管"每次重试内部能用几步"。
 *       刻意不把二者耦合——耦合会让语义变模糊，且无法表达
 *       "重试 3 次、每次内部不超过 10 步"这种常见需求。</li>
 * </ul>
 *
 * <p><b>修正反馈怎么进入下一轮：</b>
 * 评估返回的 {@link EvaluationResult#feedback()} 会拼进下一轮子策略的
 * userInput，格式为：
 * <pre>
 *   原始任务：
 *   &lt;ctx.userInput()&gt;
 *
 *   上一次尝试存在的问题与修正建议：
 *   &lt;feedback&gt;
 *
 *   请基于以上建议重新完成任务。
 * </pre>
 * 第一次尝试没有 feedback，子策略直接收到原始任务。
 *
 * <p><b>返回结果的 strategyType 为什么是 REFLEXION，而不是子策略的类型：</b>
 * <ul>
 *   <li>从 BaseAgent 的角度看，这个结果由 Reflexion 策略产生——它是
 *       调用 {@code strategy.execute()} 的那个策略；</li>
 *   <li>子策略的类型是"实现细节"，就像 ReactStrategy 内部调了模型，
 *       但结果类型不是"LlmClient"一样；</li>
 *   <li>若需要追溯嵌套关系（"Reflexion 包裹了 Plan 包裹了 React"），
 *       那是日志 / Trace 的职责，不是 AgentResult 的职责。</li>
 * </ul>
 *
 * <p><b>接受失败结果意味着什么：</b>
 * 子策略可能返回 {@code success=false}（业务失败，例如工具未找到）。
 * Evaluator 若判定该失败已满足任务要求，也会返回 accept——
 * 例如用户的原始任务就是"尝试做 X 并报告结果"，那么"报告失败"
 * 就是合格答案。这种情况下 Reflexion 返回一个 failure 类型的
 * AgentResult，保留子策略的失败语义，只把 strategyType 换成 REFLEXION。
 *
 * <p><b>技术故障与业务失败的边界：</b>
 * <ul>
 *   <li>子策略或 Evaluator 抛 {@code AgentException} → 原样向上抛，
 *       由 BaseAgent 转 ERROR；</li>
 *   <li>评估判定不通过 → 走 {@link EvaluationResult#reject(String)}，
 *       进入下一轮或最终 EXHAUSTED；</li>
 *   <li>重试耗尽 → {@link AgentResult#failure(String, StrategyType)}。</li>
 * </ul>
 *
 * <p><b>可重入：</b>所有执行期数据（子 ctx、feedback、中间结果）都是
 * {@link #execute} 的局部变量，不放在实例字段上。同一个实例可以并发
 * 服务多个 execute 调用——前提是 evaluator、subStrategy 本身线程安全。
 */
public final class ReflexionStrategy implements ExecutionStrategy {

    /**
     * 默认的最大尝试次数。
     *
     * <p>3 是一个经验折中：足够覆盖"第一次思路不对、第二次修正、
     * 第三次收尾"的常见情形，又不会在注定失败的任务上耗费过多调用。
     */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final Evaluator evaluator;
    private final ExecutionStrategy subStrategy;
    private final int maxAttempts;

    /**
     * 用默认尝试次数（3 次）构造策略。
     *
     * @param evaluator   评估器，非 null
     * @param subStrategy 被包裹的执行策略（如 ReactStrategy），非 null
     */
    public ReflexionStrategy(Evaluator evaluator, ExecutionStrategy subStrategy) {
        this(evaluator, subStrategy, DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * 用自定义尝试次数构造策略。
     *
     * @param evaluator   评估器，非 null
     * @param subStrategy 被包裹的执行策略，非 null
     * @param maxAttempts 最大尝试次数，必须为正整数
     */
    public ReflexionStrategy(Evaluator evaluator,
                             ExecutionStrategy subStrategy,
                             int maxAttempts) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.subStrategy = Objects.requireNonNull(subStrategy, "subStrategy must not be null");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException(
                    "maxAttempts must be positive, got: " + maxAttempts);
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public StrategyType type() {
        return StrategyType.REFLEXION;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        String originalTask = ctx.userInput();
        String lastFeedback = null;
        AgentResult lastResult = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {

            // ---------- REEXECUTING ----------
            // 每次尝试都用新的 ExecutionId 和新的 AgentContext。
            // 子策略之间不共享上下文——它们是独立的执行单元。
            String subInput = buildSubInput(originalTask, lastFeedback);
            AgentContext subCtx = new DefaultAgentContext(
                    ExecutionId.generate(), subInput, ctx.maxSteps());
            lastResult = subStrategy.execute(subCtx);

            // ---------- EVALUATING ----------
            com.ooohyg.agent.strategy.reflexion.EvaluationResult evaluation = evaluator.evaluate(originalTask, lastResult);

            if (evaluation.accepted()) {
                // ---------- ACCEPTED ----------
                // 不返回子策略的 result 原样——那会让 strategyType 是子策略
                // 的类型。这里重新包装，让调用方看到"这是 Reflexion 的结论"。
                return wrapAsReflexionResult(lastResult);
            }

            // ---------- REVISING ----------
            // 保存反馈，进入下一轮。反馈在下一轮的 subInput 中被注入。
            lastFeedback = evaluation.feedback();
        }

        // ---------- EXHAUSTED ----------
        // 所有尝试都用完仍未通过。带上最后一次的反馈，便于排查。
        return AgentResult.failure(
                "Reflexion exhausted after " + maxAttempts
                        + " attempt(s). Last feedback: " + lastFeedback,
                StrategyType.REFLEXION);
    }

    /**
     * 拼装子策略的 userInput。
     *
     * <p>第一次尝试只传原始任务；后续尝试额外注入上一次的修正建议。
     * 格式与 {@code PlanAndExecuteStrategy} 的步骤输入保持风格一致：
     * 先讲原始上下文，再讲本轮指令。
     */
    private String buildSubInput(String originalTask, String feedback) {
        if (feedback == null) {
            return originalTask;
        }
        return "原始任务：\n" + originalTask
                + "\n\n上一次尝试存在的问题与修正建议：\n" + feedback
                + "\n\n请基于以上建议重新完成任务。";
    }

    /**
     * 把子策略的结果重新包装为 REFLEXION 类型。
     *
     * <p>保留子结果的 success/failure 语义，只替换 strategyType——
     * 见类级 Javadoc 对"接受失败结果"的说明。
     */
    private AgentResult wrapAsReflexionResult(AgentResult subResult) {
        if (subResult.success()) {
            return AgentResult.success(subResult.output(), StrategyType.REFLEXION);
        }
        return AgentResult.failure(subResult.failureMessage(), StrategyType.REFLEXION);
    }
}