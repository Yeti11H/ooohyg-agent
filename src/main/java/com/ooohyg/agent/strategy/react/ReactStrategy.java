package com.ooohyg.agent.strategy.react;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolRegistry;
import com.ooohyg.agent.core.capability.tool.ToolResult;
import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.ToolCall;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.StrategyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ReAct 执行策略。
 *
 * <p>ReAct = Reasoning + Acting。策略交替进行"模型思考"和"工具执行"：
 * <pre>
 *   THINKING   把当前消息历史发给模型
 *     ├── 模型给出纯文本  → COMPLETED，返回 AgentResult.success
 *     └── 模型请求工具    → ACTING
 *   ACTING     逐个执行工具（串行）
 *   OBSERVING  把工具结果作为 TOOL 消息追加到历史
 *     └── 回到 THINKING
 * </pre>
 *
 * <p><b>循环次数以"模型调用次数"计。每一步（step）= 一次模型调用，
 * 加上随后的若干次工具执行。达到 {@link AgentContext#maxSteps()} 时
 * 不再开新循环，而是做一次收尾（见下文"步数耗尽"）。</b>
 *
 * <p><b>策略可重入。</b>{@code messages} 是 {@link #execute} 方法的局部变量，
 * 不放在实例字段上。同一个 ReactStrategy 实例可以并发服务多个
 * {@code execute} 调用——只要 {@link LlmClient} 和 {@link ToolRegistry}
 * 的实现是线程安全的（那是它们的契约）。
 *
 * <p><b>System prompt 为什么在策略里，而不是 BaseAgent：</b>
 * <ul>
 *   <li>ReAct、Plan、Reflexion 三个策略的 prompt 内容完全不同——ReAct
 *       要讲 think-act-observe，Plan 要讲 JSON 计划格式。放在 BaseAgent
 *       就必须按 {@link StrategyType} 分支，而 StrategyType 的 Javadoc
 *       明确禁止按类型分支；</li>
 *   <li>BaseAgent 刻意不 import message 包。它负责状态流转和异常处理，
 *       不参与"怎么和模型对话"；</li>
 *   <li>但 prompt 必须可定制。所以本类提供两个构造器：默认用内置 prompt，
 *       或调用方传入自定义 prompt。</li>
 * </ul>
 *
 * <p><b>模型返回多个 toolCalls 时串行执行。</b>每个 ToolCall 依次查表、
 * 执行、追加一条 TOOL 消息。顺序与 toolCalls 顺序一致——这样模型看到
 * TOOL 消息的顺序和它请求的顺序相同，便于对照。串行是第一版的简化；
 * 需要并行时再引入并发，那时会有明确性能依据。
 *
 * <p><b>工具名不存在的处理：</b>把 {@code "Tool not found: <name>"} 作为
 * 失败的 {@link ToolResult}，转成 TOOL 消息喂回模型，让它改。这是"模型
 * 能通过重试修正"的业务失败，不是技术故障。
 *
 * <p><b>步数耗尽的处理：</b>循环结束后再调一次模型，明确告诉它"步数
 * 已用尽，请基于已有观察给出最终答案"，且不再提供工具。这一次收尾调用
 * 不算在 maxSteps 内——它不是为了继续探索，而是为了让模型把已有的
 * 观察整合成答案，避免"明明信息够了却因为步数限制拿不到答案"。
 * 收尾调用若仍返回工具调用（模型没听指令），返回
 * {@code AgentResult.failure("...")}，因为这就是业务失败：模型无法在
 * 预算内给出答案。
 *
 * <p><b>技术故障与业务失败的边界：</b>
 * <ul>
 *   <li>{@link LlmClient#chat} 抛 {@link com.ooohyg.agent.core.exception.AgentException}
 *       → 原样向上抛，由 BaseAgent 转 ERROR；</li>
 *   <li>{@link Tool#execute} 抛 AgentException → 同上；</li>
 *   <li>工具业务失败、工具名找不到、步数耗尽 → 全部走
 *       {@link AgentResult#failure(String, StrategyType)} 或 ToolResult，不走异常。</li>
 * </ul>
 *
 * <p><b>本类不做的事：</b>
 * <ul>
 *   <li>不发事件、不打日志——第一版没有可观测性模块；</li>
 *   <li>不做重试——重试是 Reflexion 策略或未来的可靠性模块的职责；</li>
 *   <li>不读写记忆——记忆模块还没做；</li>
 *   <li>不做超时——超时是 runtime 层的事，本策略只关心逻辑步骤。</li>
 * </ul>
 */
public final class ReactStrategy implements ExecutionStrategy {

    /**
     * 内置的默认 system prompt。
     *
     * <p>写得短、直白、可执行。不用"你是一个专业的……"这类客套话——
     * 模型理解能力足够，冗余描述浪费 Token 且可能干扰判断。
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个使用 ReAct（思考-行动-观察）模式的智能体。

            工作方式：
            - 需要外部信息或操作时，调用合适的工具
            - 收到工具返回后，基于真实结果继续推理，不要臆测
            - 已经能回答用户时，直接给出最终答案，不要再调用工具
            - 工具执行失败时，根据错误信息调整策略：重试、换参数、或换工具
            """;

    /**
     * 收尾调用的用户消息。当步数耗尽时追加到 messages 末尾，再调一次模型。
     *
     * <p>措辞用"请立即"是为了让模型不再发起工具调用；明确说"不再提供工具"
     * 是为了让它知道工具列表已空，避免它发起注定失败的调用。
     */
    private static final String WRAP_UP_PROMPT =
            "你已经用完了所有可用的步骤。请立即基于已有的观察结果，"
                    + "给出对用户问题的最终答案。不要再调用任何工具。";

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String systemPrompt;

    /**
     * 用内置默认 system prompt 构造策略。
     *
     * @param llmClient    模型客户端，非 null
     * @param toolRegistry 工具注册表，非 null
     */
    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT);
    }

    /**
     * 用自定义 system prompt 构造策略。
     *
     * @param llmClient    模型客户端，非 null
     * @param toolRegistry 工具注册表，非 null
     * @param systemPrompt 自定义 system prompt，非 null、非空白
     */
    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt;
    }

    @Override
    public StrategyType type() {
        return StrategyType.REACT;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        // messages 是局部变量——保证 execute 可重入，不在实例上留状态。
        // 初始两条：SYSTEM 定义行为，USER 是任务起点。
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(ctx.userInput()));

        // ---------- 主循环：至多 maxSteps 步 ----------
        for (int step = 0; step < ctx.maxSteps(); step++) {
            LlmResponse response = llmClient.chat(messages, toolRegistry.definitions());

            // 把模型的回复追加到历史。hasToolCalls 时用带工具调用的
            // 工厂方法（content 可空），否则用纯文本工厂方法。
            if (response.hasToolCalls()) {
                messages.add(Message.assistantWithToolCalls(
                        response.content(), response.toolCalls()));
            } else {
                messages.add(Message.assistant(response.content()));
                // 纯文本回答 = 模型的最终答案。ReAct 循环结束。
                return AgentResult.success(response.content(), StrategyType.REACT);
            }

            // ---------- ACTING + OBSERVING：执行每个工具调用 ----------
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = executeTool(call);

                // 失败的 ToolResult 加 "Error: " 前缀——让模型在 TOOL 消息里
                // 一眼识别这是失败，而不是把错误信息当成普通输出。
                String content = result.success()
                        ? result.output()
                        : "Error: " + result.errorMessage();

                // TOOL 消息通过 call.id() 关联到上一条 ASSISTANT 消息中的 ToolCall。
                messages.add(Message.tool(call.id(), content));
            }

            // 回到 THINKING：下一轮循环再调模型，让它看工具结果继续推理。
        }

        // ---------- 步数耗尽：收尾调用 ----------
        return wrapUp(messages);
    }

    /**
     * 查找并执行一个工具调用。
     *
     * <p>工具名不存在时返回 {@link ToolResult#failure(String)}，
     * 不抛异常——这是模型能通过换工具名修正的业务失败。
     *
     * <p>工具自身抛的技术异常（{@code AgentException}）不在这里捕获，
     * 原样向上传播到 BaseAgent。
     */
    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = toolRegistry.find(call.name());
        return tool
                .map(t -> t.execute(call.arguments()))
                .orElseGet(() -> ToolResult.failure("Tool not found: " + call.name()));
    }

    /**
     * 步数耗尽时的收尾：再调一次模型，要求它基于已有观察给出最终答案。
     *
     * <p>收尾调用<b>不再提供工具</b>——提供工具只会让模型继续"再调一次"，
     * 违背收尾的目的。传 {@code null} 表达"无工具可用"。
     *
     * <p>如果模型仍然返回工具调用（不听话），视为业务失败：
     * 预算内没有给出答案，且明确提示后仍不给出。
     */
    private AgentResult wrapUp(List<Message> messages) {
        messages.add(Message.user(WRAP_UP_PROMPT));

        LlmResponse response = llmClient.chat(messages, null);

        if (response.isPlainAnswer()) {
            return AgentResult.success(response.content(), StrategyType.REACT);
        }
        return AgentResult.failure(
                "Reached max steps without a final answer; "
                        + "wrap-up call also returned a tool call.",
                StrategyType.REACT);
    }
}