import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.execution.DefaultAgentContext;
import com.ooohyg.agent.core.execution.ExecutionId;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.StrategyType;
import com.ooohyg.agent.strategy.reflexion.EvaluationResult;
import com.ooohyg.agent.strategy.reflexion.Evaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>Reflexion 的语义是"对已有执行结果的再加工"；</li>
 *   <li>复用让组合成为可能：
 *       {@code Reflexion(Plan(React))} 或 {@code Reflexion(React)} 都成立；</li>
 *   <li>把"评估—修正—重跑"抽象成独立一层。</li>
 * </ul>
 *
 * <p><b>为什么评估、子策略都从构造器注入：</b>
 * <ul>
 *   <li>评估标准是场景相关的，不可能硬编码；</li>
 *   <li>子策略可以是 ReAct、Plan，或未来别的实现；</li>
 *   <li>测试时二者都可以注入 mock。</li>
 * </ul>
 *
 * <p><b>maxAttempts 与 ctx.maxSteps() 的关系：</b>
 * 两者是不同维度：前者管"重试几次"，后者管"每次重试内部能用几步"。
 * 刻意不耦合。
 *
 * <p><b>修正反馈怎么进入下一轮：</b>
 * 评估返回的 feedback 会拼进下一轮子策略的 userInput。
 *
 * <p><b>返回结果的 strategyType 是 REFLEXION。</b>
 */
public final class ReflexionStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReflexionStrategy.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final Evaluator evaluator;
    private final ExecutionStrategy subStrategy;
    private final int maxAttempts;

    public ReflexionStrategy(Evaluator evaluator, ExecutionStrategy subStrategy) {
        this(evaluator, subStrategy, DEFAULT_MAX_ATTEMPTS);
    }

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

        log.debug("ReflexionStrategy created, subStrategy={}, maxAttempts={}",
                subStrategy.type(), maxAttempts);
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
        AgentResult lastResult;

        log.debug("Reflexion execution started, executionId={}, maxAttempts={}",
                ctx.executionId(), maxAttempts);

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            log.debug("Reflexion attempt {}/{}, executionId={}, withFeedback={}",
                    attempt + 1, maxAttempts, ctx.executionId(), lastFeedback != null);

            String subInput = buildSubInput(originalTask, lastFeedback);
            AgentContext subCtx = new DefaultAgentContext(
                    ExecutionId.generate(), subInput, ctx.maxSteps());

            long startNanos = System.nanoTime();
            lastResult = subStrategy.execute(subCtx);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

            log.debug("Reflexion attempt {}: subStrategy returned success={}, elapsedMs={}",
                    attempt + 1, lastResult.success(), elapsedMillis);

            EvaluationResult evaluation = evaluator.evaluate(originalTask, lastResult);

            if (evaluation.accepted()) {
                log.info("Reflexion accepted at attempt {}/{}, executionId={}",
                        attempt + 1, maxAttempts, ctx.executionId());
                return wrapAsReflexionResult(lastResult);
            }

            log.debug("Reflexion attempt {}: rejected, feedback={}",
                    attempt + 1, evaluation.feedback());
            lastFeedback = evaluation.feedback();
        }

        log.warn("Reflexion exhausted after {} attempt(s), executionId={}, lastFeedback={}",
                maxAttempts, ctx.executionId(), lastFeedback);
        return AgentResult.failure(
                "Reflexion exhausted after " + maxAttempts
                        + " attempt(s). Last feedback: " + lastFeedback,
                StrategyType.REFLEXION);
    }

    private String buildSubInput(String originalTask, String feedback) {
        if (feedback == null) {
            return originalTask;
        }
        return "原始任务：\n" + originalTask
                + "\n\n上一次尝试存在的问题与修正建议：\n" + feedback
                + "\n\n请基于以上建议重新完成任务。";
    }

    private AgentResult wrapAsReflexionResult(AgentResult subResult) {
        if (subResult.success()) {
            return AgentResult.success(subResult.output(), StrategyType.REFLEXION);
        }
        return AgentResult.failure(subResult.failureMessage(), StrategyType.REFLEXION);
    }
}