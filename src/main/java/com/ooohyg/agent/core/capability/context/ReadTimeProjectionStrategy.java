package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 读时投影策略。把中间一段旧对话折叠成一条符号占位消息。
 *
 * <p>对应 Claude Code 的 Context Collapse。核心特点是<b>不修改原始消息</b>——
 * 折叠只发生在这轮调用，原始列表完整保留在内存里。下次调用可以重新投影。
 *
 * <p><b>可逆，与 FullCompactStrategy 互斥：</b>
 * 本策略是可逆的临时视图，不需要调 LLM。
 * 当 token 使用率进一步升高（≥95%）时，应该由
 * {@link FullCompactStrategy} 做有损的 LLM 摘要，而不是本策略。
 * 两层应该互斥触发——见装配说明。
 *
 * <p><b>折叠边界对齐 USER 消息：</b>
 * 从末尾往前数 {@link #keepRecentTurns} 轮对话时，以 USER 消息为边界。
 * 这保证尾部以 USER 开头，内部的 ASSISTANT + TOOL 消息配对完整，
 * 不会出现孤立的 TOOL 消息。
 *
 * <p><b>折叠内容用符号占位：</b>
 * 被折叠的中间段用一条 SYSTEM 消息替代，内容是
 * {@code [Folded N earlier message(s)]}。原消息仍在内存里，
 * 需要时可重新投影。用户想要 LLM 摘要，用 FullCompactStrategy。
 */
public final class ReadTimeProjectionStrategy implements CompressionStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(ReadTimeProjectionStrategy.class);

    /** 保留最近几轮对话（一轮 = USER + 后续的 ASSISTANT/TOOL）。 */
    private final int keepRecentTurns;

    /**
     * 构造策略。
     *
     * @param keepRecentTurns 保留最近几轮对话，必须为正
     */
    public ReadTimeProjectionStrategy(int keepRecentTurns) {
        if (keepRecentTurns <= 0) {
            throw new IllegalArgumentException(
                    "keepRecentTurns must be positive, got: " + keepRecentTurns);
        }
        this.keepRecentTurns = keepRecentTurns;
    }

    @Override
    public List<Message> compress(List<Message> messages, CompressionContext context) {
        if (messages.isEmpty()) {
            return messages;
        }

        int systemEnd = findSystemEnd(messages);
        int tailStart = findTailStartAtUserBoundary(messages);

        if (tailStart <= systemEnd) {
            return messages;   // 中间没有可折叠的内容
        }

        List<Message> folded = messages.subList(systemEnd, tailStart);
        String placeholder = "[Folded " + folded.size() + " earlier message(s)]";

        List<Message> view = new ArrayList<>();
        view.addAll(messages.subList(0, systemEnd));              // 保留开头 SYSTEM
        view.add(Message.system(placeholder));                     // 中间折叠成占位
        view.addAll(messages.subList(tailStart, messages.size())); // 保留尾部

        log.debug("ReadTimeProjection applied: folded {} messages into 1 placeholder",
                folded.size());
        return view;
    }

    /** 找到开头连续 SYSTEM 消息的结束位置。 */
    private static int findSystemEnd(List<Message> messages) {
        int i = 0;
        while (i < messages.size() && messages.get(i).role() == MessageRole.SYSTEM) {
            i++;
        }
        return i;
    }

    /**
     * 从末尾往前数 keepRecentTurns 轮对话，返回尾部起始位置。
     *
     * <p>以 USER 消息为边界——每遇到一个 USER 就增加一轮计数。
     * 达到 keepRecentTurns 时，这个 USER 的位置就是 tailStart。
     *
     * <p>这样保证尾部以 USER 开头，ASSISTANT + TOOL 配对完整。
     */
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
        return 0;   // 不够 keepRecentTurns 轮，全部保留
    }

    @Override
    public String name() {
        return "ReadTimeProjection";
    }
}