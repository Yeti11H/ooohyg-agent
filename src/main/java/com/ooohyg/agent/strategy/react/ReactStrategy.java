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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><b>循环次数以"模型调用次数"计。</b>每一步（step）= 一次模型调用，
 * 加上随后的若干次工具执行。达到 {@link AgentContext#maxSteps()} 时
 * 不再开新循环，而是做一次收尾。
 *
 * <p><b>策略可重入。</b>{@code messages} 是 {@link #execute} 方法的局部变量，
 * 不放在实例字段上。同一个 ReactStrategy 实例可以并发服务多个
 * {@code execute} 调用——只要 {@link LlmClient} 和 {@link ToolRegistry}
 * 的实现是线程安全的（那是它们的契约）。
 *
 * <p><b>System prompt 为什么在策略里，而不是 BaseAgent：</b>
 * <ul>
 *   <li>ReAct、Plan、Reflexion 三个策略的 prompt 内容完全不同；</li>
 *   <li>BaseAgent 刻意不 import message 包。它负责状态流转和异常处理，
 *       不参与"怎么和模型对话"；</li>
 *   <li>prompt 必须可定制。所以本类提供两个构造器。</li>
 * </ul>
 *
 * <p><b>模型返回多个 toolCalls 时串行执行。</b>每个 ToolCall 依次查表、
 * 执行、追加一条 TOOL 消息。顺序与 toolCalls 顺序一致。
 *
 * <p><b>工具名不存在的处理：</b>把 {@code "Tool not found: <name>"} 作为
 * 失败的 {@link ToolResult}，转成 TOOL 消息喂回模型，让它改。
 *
 * <p><b>步数耗尽的处理：</b>循环结束后再调一次模型，明确告诉它"步数
 * 已用尽，请基于已有观察给出最终答案"，且不再提供工具。
 */
public final class ReactStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReactStrategy.class);

    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个使用 ReAct（思考-行动-观察）模式的智能体。

            工作方式：
            - 需要外部信息或操作时，调用合适的工具
            - 收到工具返回后，基于真实结果继续推理，不要臆测
            - 已经能回答用户时，直接给出最终答案，不要再调用工具
            - 工具执行失败时，根据错误信息调整策略：重试、换参数、或换工具
            """;

    private static final String WRAP_UP_PROMPT =
            "你已经用完了所有可用的步骤。请立即基于已有的观察结果，"
                    + "给出对用户问题的最终答案。不要再调用任何工具。";

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String systemPrompt;

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT);
    }

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt;

        log.debug("ReactStrategy created, availableTools={}",
                toolRegistry.definitions().size());
    }

    @Override
    public StrategyType type() {
        return StrategyType.REACT;
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        messages.add(Message.user(ctx.userInput()));

        log.debug("ReAct execution started, executionId={}, maxSteps={}",
                ctx.executionId(), ctx.maxSteps());

        for (int step = 0; step < ctx.maxSteps(); step++) {
            log.debug("ReAct step {}/{}, messages={}",
                    step + 1, ctx.maxSteps(), messages.size());

            LlmResponse response = llmClient.chat(messages, toolRegistry.definitions());

            if (response.hasToolCalls()) {
                log.debug("ReAct step {}: model requested {} tool call(s)",
                        step + 1, response.toolCalls().size());
                messages.add(Message.assistantWithToolCalls(
                        response.content(), response.toolCalls()));
            } else {
                log.debug("ReAct step {}: model returned final answer", step + 1);
                messages.add(Message.assistant(response.content()));
                return AgentResult.success(response.content(), StrategyType.REACT);
            }

            for (ToolCall call : response.toolCalls()) {
                log.debug("Executing tool: name={}, toolCallId={}",
                        call.name(), call.id());
                ToolResult result = executeTool(call);
                log.debug("Tool {} completed: success={}", call.name(), result.success());

                String content = result.success()
                        ? result.output()
                        : "Error: " + result.errorMessage();

                messages.add(Message.tool(call.id(), content));
            }
        }

        log.warn("ReAct reached maxSteps={} without final answer, executionId={}, "
                        + "starting wrap-up call",
                ctx.maxSteps(), ctx.executionId());
        return wrapUp(messages);
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = toolRegistry.find(call.name());
        if (tool.isEmpty()) {
            log.debug("Tool not found: {}", call.name());
            return ToolResult.failure("Tool not found: " + call.name());
        }
        return tool.get().execute(call.arguments());
    }

    private AgentResult wrapUp(List<Message> messages) {
        messages.add(Message.user(WRAP_UP_PROMPT));

        LlmResponse response = llmClient.chat(messages, null);

        if (response.isPlainAnswer()) {
            log.debug("ReAct wrap-up call returned final answer");
            return AgentResult.success(response.content(), StrategyType.REACT);
        }

        log.warn("ReAct wrap-up call also returned a tool call, treating as failure");
        return AgentResult.failure(
                "Reached max steps without a final answer; "
                        + "wrap-up call also returned a tool call.",
                StrategyType.REACT);
    }
}