package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 带上下文压缩的模型客户端装饰器。
 *
 * <p>它在每次调用真正的 LlmClient 之前，按顺序检查用户配置的
 * {@link CompressionRule}——触发器返回 true 就执行对应的压缩策略，
 * 然后用压缩后的消息列表调用底层客户端。
 *
 * <p><b>为什么用装饰器：</b>
 * 策略层（ReAct / Plan / Reflexion）只认识 {@link LlmClient} 接口。
 * 加不加压缩，策略代码一行不用改。用户通过"包不包这一层"决定是否启用。
 *
 * <p><b>和 MemoryAwareLlmClient 的关系：</b>
 * 两者都是 LlmClient 的装饰器，可以叠加：
 * <pre>{@code
 *   LlmClient real = new OpenAiLlmClient(...);
 *   LlmClient withMemory = new MemoryAwareLlmClient(real, shortTermMemory);
 *   LlmClient withBoth = new CompressionAwareLlmClient(withMemory, rules, tokenBudget);
 * }</pre>
 * 顺序可以灵活调整——先压缩再记忆加工，或反过来，由用户决定。
 *
 * <p><b>每次 chat 都会重新压缩：</b>
 * 消息列表在每轮循环后都会增长，所以压缩也要每轮做一次。
 * 触发器（比如"token 超 80%"）会动态判断这一轮需不需要压。
 */
public final class CompressionAwareLlmClient implements LlmClient {

    private static final Logger log =
            LoggerFactory.getLogger(CompressionAwareLlmClient.class);

    /** 被装饰的真实客户端。 */
    private final LlmClient llmClient;

    /** 用户配置的压缩规则，按顺序执行。 */
    private final List<CompressionRule> rules;

    /**
     * 模型的上下文窗口大小（token 数）。
     * 用于估算当前使用率，供触发器判断。
     */
    private final int tokenBudget;

    /**
     * 构造装饰器。
     *
     * @param llmClient  被装饰的客户端，非 null
     * @param rules      压缩规则列表，非 null、非空
     * @param tokenBudget 模型上下文窗口大小，必须为正
     */
    public CompressionAwareLlmClient(LlmClient llmClient,
                                     List<CompressionRule> rules,
                                     int tokenBudget) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException(
                    "tokenBudget must be positive, got: " + tokenBudget);
        }
        this.rules = List.copyOf(rules);
        this.tokenBudget = tokenBudget;
        log.debug("CompressionAwareLlmClient created with {} rule(s), tokenBudget={}",
                rules.size(), tokenBudget);
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Objects.requireNonNull(messages, "messages must not be null");

        // 1. 压缩
        List<Message> compressed = applyCompression(messages);

        // 2. 转发给真实客户端
        return llmClient.chat(compressed, tools);
    }

    /**
     * 按顺序应用所有压缩规则。
     *
     * <p>每一条规则的输出是下一条的输入——这样多条规则可以叠加生效。
     * 比如规则1清理工具结果，规则2再做读时投影，
     * 规则2看到的已经是清理后的消息。
     */
    private List<Message> applyCompression(List<Message> messages) {
        List<Message> current = messages;

        for (CompressionRule rule : rules) {
            CompressionContext context = buildContext(current);

            if (!rule.trigger().shouldCompress(current, context)) {
                log.debug("Compression rule '{}' skipped (trigger returned false)",
                        rule.strategy().name());
                continue;
            }

            int before = current.size();
            current = rule.strategy().compress(current, context);
            int after = current.size();

            log.debug("Compression rule '{}' applied: {} → {} messages",
                    rule.strategy().name(), before, after);
        }

        return current;
    }

    /**
     * 构造压缩上下文，给触发器和策略用。
     *
     * <p><b>token 估算：</b>core 层不引入 tokenizer 依赖，
     * 所以这里用一个粗糙但够用的估算：每个字符约等于 0.25 个 token，
     * 或者按消息条数 × 50 估算。具体值可以由用户通过继承覆盖。
     *
     * <p>生产环境建议用户自己接一个真实的 tokenizer——本类只提供默认值。
     */
    private CompressionContext buildContext(List<Message> messages) {
        int estimatedTokens = estimateTokens(messages);
        return new CompressionContext(
                tokenBudget,
                estimatedTokens,
                messages.size(),
                0   // compressionRound：单次 chat 内不做多轮压缩，固定为 0
        );
    }

    /**
     * 粗估消息列表的 token 数。
     *
     * <p>公式：所有消息的 content 字符数 × 0.25 + 消息条数 × 10。
     * 这是个经验值，误差可能有 20% 左右。够触发器做判断用。
     */
    private static int estimateTokens(List<Message> messages) {
        int totalChars = 0;
        for (Message msg : messages) {
            if (msg.content() != null) {
                totalChars += msg.content().length();
            }
        }
        return (int) (totalChars * 0.25) + messages.size() * 10;
    }
}
