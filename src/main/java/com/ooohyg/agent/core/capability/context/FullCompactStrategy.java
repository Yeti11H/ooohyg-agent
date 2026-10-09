package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全量压缩策略。调用 LLM 把中间一段旧对话总结成摘要，替换原始消息。
 *
 * <p>对应 Claude Code 的第 5 层（AutoCompact）。这是所有压缩手段中
 * <b>最重、也是最后</b>的一层——只在其他轻量策略都无法把 token
 * 压到预算内时才启用（通常触发条件是 token 使用率 ≥ 95%）。
 *
 * <p><b>有损，不可逆：</b>
 * 摘要一旦生成，原始消息就被永久替换。用户如果需要保留原始对话，
 * 应该在装配时把本策略放在最后，并让前面的轻量策略先处理。
 *
 * <p><b>与 ReadTimeProjectionStrategy 互斥：</b>
 * 读时投影是可逆的临时视图，本策略是不可逆的永久替换。
 * 两者应该用互斥的触发条件——比如：
 * <ul>
 *   <li>读时投影：90% ≤ ratio < 95%</li>
 *   <li>全量压缩：ratio ≥ 95%</li>
 * </ul>
 * 这样全量压缩触发时，读时投影不触发，本策略拿到的是<b>原始消息</b>，
 * 而不是已经被折叠成占位符的视图。
 *
 * <p><b>熔断保护：</b>
 * 连续 {@link #failureThreshold} 次调用 LLM 失败后，本策略进入熔断
 * 状态——后续调用直接跳过。这防止 LLM 服务不可用时反复失败、
 * 浪费时间和配额。
 *
 * <p><b>摘要 prompt 必须由用户提供：</b>
 * 摘要保留什么内容高度场景相关——代码场景要保留文件路径和函数名，
 * 客服场景要保留用户诉求和待办事项。框架不该替用户拍板。
 */
public final class FullCompactStrategy implements CompressionStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(FullCompactStrategy.class);

    private static final int DEFAULT_KEEP_RECENT_TURNS = 3;
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;

    /** 用来生成摘要的模型客户端。 */
    private final LlmClient llmClient;

    /** 摘要提示词。用户提供，场景相关。 */
    private final String summaryPrompt;

    /** 保留最近几轮对话。 */
    private final int keepRecentTurns;

    /** 连续失败多少次后熔断。 */
    private final int failureThreshold;

    /** 当前连续失败次数。 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 用默认参数构造：保留最近 3 轮，连续失败 3 次熔断。
     *
     * @param llmClient     用来生成摘要的模型客户端，非 null
     * @param summaryPrompt 摘要提示词，非 null、非空白
     */
    public FullCompactStrategy(LlmClient llmClient, String summaryPrompt) {
        this(llmClient, summaryPrompt,
                DEFAULT_KEEP_RECENT_TURNS, DEFAULT_FAILURE_THRESHOLD);
    }

    /**
     * 全参构造。
     *
     * @param llmClient        模型客户端，非 null
     * @param summaryPrompt    摘要提示词，非 null、非空白
     * @param keepRecentTurns  保留最近几轮，必须为正
     * @param failureThreshold 连续失败多少次后熔断，必须为正
     */
    public FullCompactStrategy(LlmClient llmClient, String summaryPrompt,
                               int keepRecentTurns, int failureThreshold) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient must not be null");
        Objects.requireNonNull(summaryPrompt, "summaryPrompt must not be null");
        if (summaryPrompt.isBlank()) {
            throw new IllegalArgumentException("summaryPrompt must not be blank");
        }
        if (keepRecentTurns <= 0) {
            throw new IllegalArgumentException(
                    "keepRecentTurns must be positive, got: " + keepRecentTurns);
        }
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException(
                    "failureThreshold must be positive, got: " + failureThreshold);
        }
        this.summaryPrompt = summaryPrompt;
        this.keepRecentTurns = keepRecentTurns;
        this.failureThreshold = failureThreshold;
    }

    @Override
    public List<Message> compress(List<Message> messages, CompressionContext context) {
        // 熔断检查
        if (consecutiveFailures.get() >= failureThreshold) {
            log.warn("FullCompact circuit-broken after {} consecutive failures",
                    failureThreshold);
            return messages;
        }

        int systemEnd = findSystemEnd(messages);
        int tailStart = findTailStartAtUserBoundary(messages);

        if (tailStart <= systemEnd) {
            return messages;
        }

        List<Message> toSummarize = messages.subList(systemEnd, tailStart);

        try {
            String summary = generateSummary(toSummarize);
            consecutiveFailures.set(0);

            List<Message> result = new ArrayList<>();
            result.addAll(messages.subList(0, systemEnd));
            result.add(Message.system("[Conversation summary]\n" + summary));
            result.addAll(messages.subList(tailStart, messages.size()));

            log.debug("FullCompact applied: summarized {} messages into {} chars",
                    toSummarize.size(), summary.length());
            return result;

        } catch (Exception e) {
            log.error("FullCompact summary generation failed", e);
            consecutiveFailures.incrementAndGet();
            return messages;
        }
    }

    /** 调用 LLM 生成摘要。 */
    private String generateSummary(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            sb.append(m.role()).append(": ");
            if (m.content() != null) {
                sb.append(m.content());
            }
            sb.append('\n');
        }

        List<Message> prompt = List.of(
                Message.system(summaryPrompt),
                Message.user(sb.toString())
        );
        LlmResponse response = llmClient.chat(prompt, null);
        return response.content();
    }

    private static int findSystemEnd(List<Message> messages) {
        int i = 0;
        while (i < messages.size() && messages.get(i).role() == MessageRole.SYSTEM) {
            i++;
        }
        return i;
    }

    private int findTailStartAtUserBoundary(List<Message> messages) {
        int turns = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == MessageRole.USER) {
                turns++;
                if (turns == keepRecentTurns) {
                    return i;
                }
            }
        }
        return 0;
    }

    @Override
    public String name() {
        return "FullCompact";
    }
}