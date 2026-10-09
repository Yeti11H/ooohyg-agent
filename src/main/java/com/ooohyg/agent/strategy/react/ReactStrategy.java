package com.ooohyg.agent.strategy.react;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.Decision;
import com.ooohyg.agent.core.capability.tool.PermissionDecider;
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

    /** 权限决策器。可以为 null——为 null 时不检查权限。 */
    private final PermissionDecider permissionDecider;

    /** 最简单的构造器：不带权限检查。 */
    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, null);
    }

    /** 带自定义 prompt，不带权限检查。 */
    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this(llmClient, toolRegistry, systemPrompt, null);
    }

    /** 带权限检查，用默认 prompt。 */
    public ReactStrategy(LlmClient llmClient,
                         ToolRegistry toolRegistry,
                         PermissionDecider permissionDecider) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, permissionDecider);
    }

    /** 全参构造器。 */
    public ReactStrategy(LlmClient llmClient,
                         ToolRegistry toolRegistry,
                         String systemPrompt,
                         PermissionDecider permissionDecider) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt;
        this.permissionDecider = permissionDecider;

        log.debug("ReactStrategy created, availableTools={}, permissionDecider={}",
                toolRegistry.definitions().size(),
                permissionDecider != null ? "enabled" : "disabled");
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

    /**
     * 执行一个工具调用——含权限检查。
     */
    private ToolResult executeTool(ToolCall call) {
        // ========== 第 1 步：查找工具 ==========
        Optional<Tool> tool = toolRegistry.find(call.name());
        if (tool.isEmpty()) {
            log.debug("Tool not found: {}", call.name());
            return ToolResult.failure("Tool not found: " + call.name());
        }
        Tool t = tool.get();

        // ========== 第 2 步：权限检查 ==========
        if (permissionDecider != null) {
            Decision decision = permissionDecider.decide(
                    t,                       // 工具
                    call.arguments(),        // 参数
                    call.id()                // 工具调用 id
            );

            switch (decision) {
                case DENY -> {
                    log.debug("Tool '{}' denied by permission decider", call.name());
                    return ToolResult.failure(
                            "Permission denied: tool '" + call.name()
                                    + "' is not allowed.");
                }
                case REQUIRE_APPROVAL -> {
                    log.debug("Tool '{}' requires approval", call.name());
                    return ToolResult.failure(
                            "Tool '" + call.name() + "' requires user approval. "
                                    + "Please tell the user this action needs confirmation.");
                }
                case ALLOW -> {
                    // 继续往下执行
                }
            }
        }

        // ========== 第 3 步：执行工具 ==========
        return t.execute(call.arguments());
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