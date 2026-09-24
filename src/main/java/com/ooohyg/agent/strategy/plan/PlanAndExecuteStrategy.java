package com.ooohyg.agent.strategy.plan;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.execution.DefaultAgentContext;
import com.ooohyg.agent.core.execution.ExecutionId;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plan-and-Execute 执行策略。
 *
 * <p>先规划，再逐步执行：
 * <pre>
 *   PLANNING   调一次模型，生成 List&lt;PlanStep&gt;
 *     ├── 解析失败 / 空计划 → AgentResult.failure
 *     └── 有步骤            → READY → EXECUTING
 *   EXECUTING  逐个步骤委派给子策略（通常是 ReactStrategy）
 *     ├── 某步业务失败      → 终止，AgentResult.failure
 *     └── 所有步成功        → 返回最后一步的输出
 * </pre>
 *
 * <p><b>为什么把执行委派给子策略，而不是 Plan 自己调工具：</b>
 * <ul>
 *   <li>复用已有代码。ReAct 已经实现了工具调用等逻辑；</li>
 *   <li>验证"策略可以嵌套"这个抽象；</li>
 *   <li>每步内部的工具循环对 Plan 完全透明。</li>
 * </ul>
 *
 * <p><b>子策略由构造器注入，不硬编码 ReactStrategy：</b>
 * <ul>
 *   <li>满足依赖倒置——Plan 依赖 {@link ExecutionStrategy} 接口；</li>
 *   <li>测试时可以注入 mock 的子策略；</li>
 *   <li>未来如果出现"用别的执行器跑步骤"的需求，不需要改 Plan 代码。</li>
 * </ul>
 *
 * <p><b>步数预算分配：</b>生成计划占 1 步，剩余
 * {@code maxSteps - 1} 步平分给 N 个步骤。步骤数超预算时直接失败。
 *
 * <p><b>步骤间上下文传递：只传上一步的输出。</b>
 *
 * <p><b>失败处理：任何一步业务失败即终止。</b>重试 / 重规划是 Reflexion 的职责。
 */
public final class PlanAndExecuteStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PlanAndExecuteStrategy.class);

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责是把用户的复杂任务分解为
            有序的、可独立执行的步骤。

            要求：
            - 每一步都应当是具体的、可执行的动作，而不是抽象目标
            - 步骤之间按执行顺序排列
            - 步骤数量尽量精简：能 3 步解决就不要拆成 5 步
            - 只输出计划本身，不要输出解释、前言或结语
            """;

    private static final String DEFAULT_PLAN_INSTRUCTION = """

            请把上面的任务分解为若干步骤，以 JSON 字符串数组的形式返回，
            例如：
            ["第一步描述", "第二步描述", "第三步描述"]

            不要输出 JSON 以外的任何内容。
            """;

    private final LlmClient llmClient;
    private final PlanParser planParser;
    private final ExecutionStrategy subStrategy;
    private final String systemPrompt;
    private final String planInstruction;

    public PlanAndExecuteStrategy(LlmClient llmClient,
                                  PlanParser planParser,
                                  ExecutionStrategy subStrategy) {
        this(llmClient, planParser, subStrategy,
                DEFAULT_SYSTEM_PROMPT, DEFAULT_PLAN_INSTRUCTION);
    }

    public PlanAndExecuteStrategy(LlmClient llmClient,
                                  PlanParser planParser,
                                  ExecutionStrategy subStrategy,
                                  String systemPrompt,
                                  String planInstruction) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.planParser = Objects.requireNonNull(planParser, "planParser must not be null");
        this.subStrategy = Objects.requireNonNull(subStrategy, "subStrategy must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        Objects.requireNonNull(planInstruction, "planInstruction must not be null");
        if (planInstruction.isBlank()) {
            throw new IllegalArgumentException("planInstruction must not be blank");
        }
        this.systemPrompt = systemPrompt;
        this.planInstruction = planInstruction;

        log.debug("PlanAndExecuteStrategy created, subStrategy={}", subStrategy.type());
    }

    @Override
    public StrategyType type() {
        return StrategyType.PLAN_AND_EXECUTE;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        log.debug("Plan execution started, executionId={}, maxSteps={}",
                ctx.executionId(), ctx.maxSteps());

        List<PlanStep> steps = generatePlan(ctx.userInput());
        if (steps.isEmpty()) {
            log.warn("Plan generation returned empty plan, executionId={}",
                    ctx.executionId());
            return AgentResult.failure(
                    "Plan generation failed: model did not return a parseable plan.",
                    StrategyType.PLAN_AND_EXECUTE);
        }

        log.info("Plan generated: {} step(s), executionId={}",
                steps.size(), ctx.executionId());

        int totalBudget = ctx.maxSteps();
        int remainingBudget = totalBudget - 1;
        if (steps.size() > remainingBudget) {
            log.warn("Plan has {} steps but only {} step(s) remain in budget "
                            + "(maxSteps={}), executionId={}",
                    steps.size(), remainingBudget, totalBudget, ctx.executionId());
            return AgentResult.failure(
                    "Plan has " + steps.size() + " steps but only "
                            + remainingBudget + " steps remain in the budget (maxSteps="
                            + totalBudget + ").",
                    StrategyType.PLAN_AND_EXECUTE);
        }
        int perStepBudget = remainingBudget / steps.size();

        String lastOutput = null;
        for (PlanStep step : steps) {
            log.debug("Executing plan step {}/{}, perStepBudget={}",
                    step.index() + 1, steps.size(), perStepBudget);

            String stepInput = buildStepInput(ctx.userInput(), step, lastOutput);
            AgentContext subCtx = new DefaultAgentContext(
                    ExecutionId.generate(), stepInput, perStepBudget);

            AgentResult subResult = subStrategy.execute(subCtx);

            if (!subResult.success()) {
                log.warn("Plan step {} failed, executionId={}, reason={}",
                        step.index(), ctx.executionId(), subResult.failureMessage());
                return AgentResult.failure(
                        "Plan step " + step.index() + " failed: "
                                + subResult.failureMessage(),
                        StrategyType.PLAN_AND_EXECUTE);
            }

            log.debug("Plan step {} completed successfully", step.index());
            lastOutput = subResult.output();
        }

        log.info("Plan execution completed, executionId={}, steps={}",
                ctx.executionId(), steps.size());
        return AgentResult.success(lastOutput, StrategyType.PLAN_AND_EXECUTE);
    }

    private List<PlanStep> generatePlan(String userInput) {
        log.debug("Generating plan");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(userInput + planInstruction));

        LlmResponse response = llmClient.chat(messages, null);

        if (!response.isPlainAnswer()) {
            log.warn("Plan generation: model returned tool calls but no tools were provided");
            return List.of();
        }
        return planParser.parse(response.content());
    }

    private String buildStepInput(String originalTask, PlanStep step, String previousOutput) {
        StringBuilder sb = new StringBuilder();
        sb.append("原始任务：").append(originalTask).append("\n\n");
        if (previousOutput != null) {
            sb.append("上一步结果：\n").append(previousOutput).append("\n\n");
        }
        sb.append("当前步骤：").append(step.description());
        return sb.toString();
    }
}