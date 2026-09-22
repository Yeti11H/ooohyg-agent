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
 *   <li>复用已有代码。ReAct 已经实现了工具调用、TOOL 消息组装等逻辑，
 *       Plan 不需要重复实现；</li>
 *   <li>验证"策略可以嵌套"这个抽象——Reflexion 包裹 Plan 包裹 React
 *       是框架的推荐组合；</li>
 *   <li>每步内部的工具循环对 Plan 完全透明。Plan 只负责"做什么"，
 *       ReAct 负责"怎么做"。</li>
 * </ul>
 *
 * <p><b>子策略由构造器注入，不硬编码 ReactStrategy：</b>
 * <ul>
 *   <li>满足依赖倒置——Plan 依赖 {@link ExecutionStrategy} 接口，
 *       不依赖具体实现；</li>
 *   <li>测试时可以注入 mock 的子策略，不需要真实 ReactStrategy；</li>
 *   <li>未来如果出现"用别的执行器跑步骤"的需求，不需要改 Plan 代码。</li>
 * </ul>
 *
 * <p><b>步数预算分配：</b>
 * <ul>
 *   <li>生成计划本身消耗 1 步模型调用；</li>
 *   <li>剩余 {@code maxSteps - 1} 步平分给 N 个步骤，每步至少 1 步；</li>
 *   <li>如果步骤数超过剩余预算，直接返回 failure——不执行任何步骤，
 *       避免"计划跑一半才发现预算不够"；</li>
 *   <li>每个步骤拿到的是一个全新的 {@link AgentContext}，其
 *       {@code maxSteps} 是每步子预算，{@code userInput} 是拼好的步骤指令。</li>
 * </ul>
 *
 * <p><b>步骤间上下文传递：只传上一步的输出，不传全部历史。</b>
 * <ul>
 *   <li>传全部会撑爆上下文，且步骤越多越糟；</li>
 *   <li>传一步已经足够让模型理解"前因后果"——多数计划步骤的依赖
 *       都是局部的；</li>
 *   <li>原始任务会一并注入，模型始终能看到大目标。</li>
 * </ul>
 *
 * <p><b>失败处理：任何一步业务失败即终止，返回 AgentResult.failure。</b>
 * <ul>
 *   <li>第一版 Plan 不做重试、不做重规划——那是 Reflexion 的职责；</li>
 *   <li>把 Reflexion 包在 Plan 外层，就自然获得"失败重试"能力；</li>
 *   <li>Plan 自己再做重试会让两层职责重叠，语义变模糊。</li>
 * </ul>
 *
 * <p><b>技术故障与业务失败的边界：</b>
 * <ul>
 *   <li>{@link LlmClient#chat} 或子策略抛 {@code AgentException}
 *       → 原样向上抛，由 BaseAgent 转 ERROR；</li>
 *   <li>计划解析失败、计划步骤数超预算、某步业务失败
 *       → 走 {@link AgentResult#failure(String, StrategyType)}，不走异常。</li>
 * </ul>
 *
 * <p><b>可重入：</b>本类所有执行期数据（messages、步骤列表、临时结果）
 * 都是 {@link #execute} 的局部变量，不放在实例字段上。同一个实例可以
 * 并发服务多个 execute 调用，前提是依赖组件（llmClient、parser、
 * subStrategy）本身线程安全——那是它们的契约。
 */
public final class PlanAndExecuteStrategy implements ExecutionStrategy {

    /**
     * 内置的默认 system prompt。用于 PLANNING 阶段。
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个任务规划专家。你的职责是把用户的复杂任务分解为
            有序的、可独立执行的步骤。

            要求：
            - 每一步都应当是具体的、可执行的动作，而不是抽象目标
            - 步骤之间按执行顺序排列
            - 步骤数量尽量精简：能 3 步解决就不要拆成 5 步
            - 只输出计划本身，不要输出解释、前言或结语
            """;

    /**
     * 默认的计划生成指令，追加在用户任务之后。
     *
     * <p>格式约定为 JSON 字符串数组——与项目推荐的
     * {@code PlanParser} 实现（JSON 解析）配套。使用自定义 parser 时，
     * 应当同时传入与之匹配的自定义 system prompt。
     */
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

    /**
     * 用默认 system prompt 和默认计划指令构造策略。
     *
     * @param llmClient   模型客户端（用于生成计划），非 null
     * @param planParser  计划解析器，非 null
     * @param subStrategy 每步的执行器（推荐传 {@code ReactStrategy}），非 null
     */
    public PlanAndExecuteStrategy(LlmClient llmClient,
                                  PlanParser planParser,
                                  ExecutionStrategy subStrategy) {
        this(llmClient, planParser, subStrategy,
                DEFAULT_SYSTEM_PROMPT, DEFAULT_PLAN_INSTRUCTION);
    }

    /**
     * 用自定义 prompt 构造策略。
     *
     * <p>自定义 system prompt 时，应当同时传入与 {@code planParser}
     * 匹配的 {@code planInstruction}——两者共同约定模型返回的格式。
     *
     * @param llmClient      模型客户端，非 null
     * @param planParser     计划解析器，非 null
     * @param subStrategy    每步的执行器，非 null
     * @param systemPrompt   系统提示词，非 null、非空白
     * @param planInstruction 计划生成指令（追加在用户任务之后），非 null、非空白
     */
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
    }

    @Override
    public StrategyType type() {
        return StrategyType.PLAN_AND_EXECUTE;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        // ---------- PLANNING ----------
        List<PlanStep> steps = generatePlan(ctx.userInput());
        if (steps.isEmpty()) {
            return AgentResult.failure(
                    "Plan generation failed: model did not return a parseable plan.",
                    StrategyType.PLAN_AND_EXECUTE);
        }

        // ---------- 预算校验 ----------
        int totalBudget = ctx.maxSteps();
        int remainingBudget = totalBudget - 1;   // 计划本身占 1 步
        if (steps.size() > remainingBudget) {
            return AgentResult.failure(
                    "Plan has " + steps.size() + " steps but only "
                            + remainingBudget + " steps remain in the budget (maxSteps="
                            + totalBudget + ").",
                    StrategyType.PLAN_AND_EXECUTE);
        }
        int perStepBudget = remainingBudget / steps.size();   // >= 1，已由上面的校验保证

        // ---------- EXECUTING ----------
        String lastOutput = null;
        for (PlanStep step : steps) {
            String stepInput = buildStepInput(ctx.userInput(), step, lastOutput);
            AgentContext subCtx = new DefaultAgentContext(
                    ExecutionId.generate(), stepInput, perStepBudget);

            AgentResult subResult = subStrategy.execute(subCtx);

            if (!subResult.success()) {
                // 任何一步业务失败即终止。失败信息带上步骤序号，
                // 便于上层排查"卡在哪一步"。
                return AgentResult.failure(
                        "Plan step " + step.index() + " failed: "
                                + subResult.failureMessage(),
                        StrategyType.PLAN_AND_EXECUTE);
            }
            lastOutput = subResult.output();
        }

        // ---------- COMPLETED ----------
        // 最后一步的输出就是最终结果：按照 prompt 的约定，计划的最后一步
        // 应当产出面向用户的最终答案。不再做额外整合——那会多一次模型
        // 调用，且模糊"计划-执行"的边界。
        return AgentResult.success(lastOutput, StrategyType.PLAN_AND_EXECUTE);
    }

    /**
     * PLANNING 阶段：调一次模型生成计划，并交给 parser 解析。
     *
     * <p>本阶段<b>不提供工具</b>——生成计划是纯文本推理任务，
     * 不需要工具介入。传 {@code null} 表达"无工具可用"。
     *
     * <p>如果模型仍然返回了工具调用（不听话），视为计划生成失败：
     * 返回空列表，由调用方统一转为 failure。
     */
    private List<PlanStep> generatePlan(String userInput) {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(userInput + planInstruction));

        LlmResponse response = llmClient.chat(messages, null);

        if (!response.isPlainAnswer()) {
            // 模型返回了工具调用——但本轮没给它工具，说明模型不听话。
            // 不做额外处理，返回空列表让上层判定为失败。
            return List.of();
        }
        return planParser.parse(response.content());
    }

    /**
     * 拼装单个步骤的 userInput。
     *
     * <p>格式：
     * <pre>
     *   原始任务：&lt;ctx.userInput()&gt;
     *
     *   上一步结果：&lt;上一段的 output&gt;       （第一步无此段）
     *
     *   当前步骤：&lt;step.description()&gt;
     * </pre>
     *
     * <p>只传上一步输出，不传全部历史——见类级 Javadoc 的说明。
     */
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