package com.ooohyg.agent.strategy.react;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.Decision;
import com.ooohyg.agent.core.capability.tool.PermissionDecider;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolRegistry;
import com.ooohyg.agent.core.capability.tool.ToolResult;
import com.ooohyg.agent.core.exception.AgentException;
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
    private final PermissionDecider permissionDecider;

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, null);
    }

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this(llmClient, toolRegistry, systemPrompt, null);
    }

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, PermissionDecider permissionDecider) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, permissionDecider);
    }

    public ReactStrategy(LlmClient llmClient, ToolRegistry toolRegistry, String systemPrompt, PermissionDecider permissionDecider) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        Objects.requireNonNull(systemPrompt, "systemPrompt must not be null");
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("systemPrompt must not be blank");
        }
        this.systemPrompt = systemPrompt;
        this.permissionDecider = permissionDecider;
    }

    @Override
    public StrategyType type() {
        return StrategyType.REACT;
    }

    // =========================================================
    // 改动一：引入显式状态管理对象
    // =========================================================
    private static class ReactState {
        final List<Message> messages;
        int step;

        ReactState(String systemPrompt, String userInput) {
            this.messages = new ArrayList<>();
            this.messages.add(Message.system(systemPrompt));
            this.messages.add(Message.user(userInput));
            this.step = 0;
        }
    }

    @Override
    public AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        // 初始化状态
        ReactState state = new ReactState(systemPrompt, ctx.userInput());

        log.debug("ReAct execution started, executionId={}, maxSteps={}", ctx.executionId(), ctx.maxSteps());

        for (; state.step < ctx.maxSteps(); state.step++) {
            log.debug("ReAct step {}/{}, messages={}", state.step + 1, ctx.maxSteps(), state.messages.size());

            // 1. 调模型
            LlmResponse response = llmClient.chat(state.messages, toolRegistry.definitions());

            // 2. 判断模型意图
            if (response.hasToolCalls()) {
                log.debug("ReAct step {}: model requested {} tool call(s)", state.step + 1, response.toolCalls().size());
                state.messages.add(Message.assistantWithToolCalls(response.content(), response.toolCalls()));
            } else {
                log.debug("ReAct step {}: model returned final answer", state.step + 1);
                return AgentResult.success(response.content(), StrategyType.REACT);
            }

            // 3. 执行工具（带强制孤儿修复）
            for (ToolCall call : response.toolCalls()) {
                try {
                    ToolResult result = executeTool(call);
                    String content = result.success() ? result.output() : "Error: " + result.errorMessage();
                    state.messages.add(Message.tool(call.id(), content));

                } catch (Exception e) {
                    // =========================================================
                    // 改动二：强制"孤儿工具"修复
                    // 如果工具执行抛出异常，我们必须先补一个假的 TOOL 消息，
                    // 保持消息配对完整，然后再把异常抛给上层。
                    // =========================================================
                    log.error("Tool execution failed with exception, synthesizing fake tool result", e);

                    // 塞一条占位消息（注意：content 不能为空白字符串，否则会触发 Message 类的校验异常）
                    state.messages.add(Message.tool(call.id(), "[System Error] Tool execution aborted."));

                    // 包装并向上抛出，由 BaseAgent 接管将状态转为 ERROR
                    if (e instanceof AgentException) {
                        throw (AgentException) e;
                    } else {
                        throw new AgentException("Unexpected error during tool execution", e);
                    }
                }
            }
        }

        log.warn("ReAct reached maxSteps={} without final answer, starting wrap-up call", ctx.maxSteps());
        return wrapUp(state.messages);
    }

    private ToolResult executeTool(ToolCall call) {
        Optional<Tool> tool = toolRegistry.find(call.name());
        if (tool.isEmpty()) {
            return ToolResult.failure("Tool not found: " + call.name());
        }
        Tool t = tool.get();

        // 权限检查
        if (permissionDecider != null) {
            Decision decision = permissionDecider.decide(t, call.arguments(), call.id());
            switch (decision) {
                case DENY -> {
                    return ToolResult.failure("Permission denied for tool '" + call.name() + "'.");
                }
                case REQUIRE_APPROVAL -> {
                    return ToolResult.failure("Tool '" + call.name() + "' requires approval.");
                }
                case ALLOW -> { /* 继续执行 */ }
            }
        }

        return t.execute(call.arguments());
    }

    private AgentResult wrapUp(List<Message> messages) {
        // 注意：这里为了不污染原始 state，最好拷贝一份或者直接追加
        messages.add(Message.user(WRAP_UP_PROMPT));
        LlmResponse response = llmClient.chat(messages, null);

        if (response.isPlainAnswer()) {
            return AgentResult.success(response.content(), StrategyType.REACT);
        }
        return AgentResult.failure("Reached max steps without a final answer.", StrategyType.REACT);
    }
}