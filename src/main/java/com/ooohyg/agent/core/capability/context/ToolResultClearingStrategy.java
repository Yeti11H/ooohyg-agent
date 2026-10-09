package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果清理策略。把旧的 TOOL 消息内容替换为占位符，只保留最近 N 条。
 *
 * <p>对应 Claude Code 的 MicroCompact。清理的是"可重复获取"的工具结果
 * （比如读文件、搜索、命令输出）——因为它们可以通过重新调工具获得。
 *
 * <p><b>纯函数、无状态：</b>
 * 本策略不做任何"是否该清理"的判断——那是 {@link CompressionTrigger}
 * 的职责。"何时清理"和"怎么清理"分开，各司其职：
 * <ul>
 *   <li>触发器回答：这次 chat 该不该清理？</li>
 *   <li>策略回答：清理时保留哪些、清哪些？</li>
 * </ul>
 *
 * <p><b>为什么用占位符而不是清空：</b>
 * 与 OpenAI API 的消息配对规则有关。{@code ASSISTANT(toolCalls=[call_1])}
 * 必须有一条 {@code TOOL(call_1, ...)} 与之配对。若直接删消息，
 * 配对断裂，API 会报错。所以用占位符替换 content，保留 toolCallId，
 * 消息结构完整。
 *
 * <p>占位符不能是空字符串——{@link Message#tool} 的紧凑构造器
 * 强制 content 非空白。
 *
 * <p><b>保留最近 N 条的语义：</b>
 * 从最新的 TOOL 消息往前数 N 条保留，更早的替换为占位符。
 * N 由调用方配置——不同场景的合理值不同（读文件 vs 命令输出，
 * 生命周期差别很大）。
 */
public final class ToolResultClearingStrategy implements CompressionStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(ToolResultClearingStrategy.class);

    /** 被清理的消息内容替换成这个占位符。不能为空——Message.tool 强制非空白。 */
    private static final String CLEARED_PLACEHOLDER = "[Old tool result cleared]";

    /** 保留最近 N 条 TOOL 消息不清。 */
    private final int keepRecentCount;

    /**
     * 构造策略。
     *
     * @param keepRecentCount 保留最近 N 条工具结果，必须非负。
     *                        0 表示所有工具结果都清理（不推荐）。
     */
    public ToolResultClearingStrategy(int keepRecentCount) {
        if (keepRecentCount < 0) {
            throw new IllegalArgumentException(
                    "keepRecentCount must not be negative, got: " + keepRecentCount);
        }
        this.keepRecentCount = keepRecentCount;
    }

    @Override
    public List<Message> compress(List<Message> messages, CompressionContext context) {
        // 1. 找出所有 TOOL 消息的位置
        List<Integer> toolIndexes = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == MessageRole.TOOL) {
                toolIndexes.add(i);
            }
        }

        // 2. 需要清理的数量 = 总数 - 保留数
        int toClear = toolIndexes.size() - keepRecentCount;
        if (toClear <= 0) {
            return messages;   // 不需要清理，直接返回原列表
        }

        // 3. 构造新列表：前 toClear 条 TOOL 消息替换 content
        List<Message> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            int toolOrder = toolIndexes.indexOf(i);
            if (toolOrder >= 0 && toolOrder < toClear) {
                // 这条 TOOL 消息需要清理：替换 content，保留 toolCallId
                result.add(Message.tool(msg.toolCallId(), CLEARED_PLACEHOLDER));
            } else {
                result.add(msg);
            }
        }

        log.debug("ToolResultClearing applied: cleared {} of {} tool results (kept {})",
                toClear, toolIndexes.size(), keepRecentCount);
        return result;
    }

    @Override
    public String name() {
        return "ToolResultClearing";
    }
}