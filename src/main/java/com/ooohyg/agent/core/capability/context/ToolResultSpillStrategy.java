package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具结果落盘策略。单条 TOOL 消息内容超限时，完整内容写入磁盘，
 * 上下文里只留预览 + 文件路径。
 *
 * <p>对应 Claude Code 的"工具结果预算"。它防的是"一次工具调用就把
 * 上下文撑爆"——比如模型读了一个几十万字符的日志文件。
 *
 * <p><b>和其他三层的区别：</b>
 * 前三层处理的是"已经积累的问题"（多条旧消息、整体过长），
 * 本策略从<b>源头</b>限制单条大小。
 *
 * <p><b>和 FileReadTool 形成闭环：</b>
 * 落盘的内容，模型可以通过已有的 FileReadTool 按需读回。
 * 不需要为此新增工具。
 *
 * <p><b>为什么默认关闭：</b>
 * 它有副作用——往磁盘写文件。框架不该默认做有副作用的事。
 */
public final class ToolResultSpillStrategy implements CompressionStrategy {

    private static final Logger log =
            LoggerFactory.getLogger(ToolResultSpillStrategy.class);

    /** 单条工具结果的最大字符数。超过就落盘。 */
    private final int maxInlineChars;

    /** 上下文里保留的预览字符数。 */
    private final int previewChars;

    /** 落盘目录。 */
    private final Path spillDir;

    /**
     * 全参构造。
     *
     * @param maxInlineChars 单条结果最大字符数，必须为正，推荐 2000
     * @param previewChars   预览字符数，必须为正且 ≤ maxInlineChars，推荐 1000
     * @param spillDir       落盘目录，非 null
     */
    public ToolResultSpillStrategy(int maxInlineChars, int previewChars, Path spillDir) {
        if (maxInlineChars <= 0) {
            throw new IllegalArgumentException(
                    "maxInlineChars must be positive, got: " + maxInlineChars);
        }
        if (previewChars <= 0 || previewChars > maxInlineChars) {
            throw new IllegalArgumentException(
                    "previewChars must be in (0, maxInlineChars], got: " + previewChars);
        }
        if (spillDir == null) {
            throw new IllegalArgumentException("spillDir must not be null");
        }
        this.maxInlineChars = maxInlineChars;
        this.previewChars = previewChars;
        this.spillDir = spillDir.toAbsolutePath().normalize();
    }

    /** 用默认参数构造：2KB 上限，1KB 预览。 */
    public static ToolResultSpillStrategy withDefaults(Path spillDir) {
        return new ToolResultSpillStrategy(2000, 1000, spillDir);
    }

    @Override
    public List<Message> compress(List<Message> messages, CompressionContext context) {
        try {
            Files.createDirectories(spillDir);
        } catch (Exception e) {
            log.warn("Failed to create spill dir: {}", spillDir, e);
            return messages;
        }

        List<Message> result = new ArrayList<>(messages.size());
        int spilledCount = 0;

        for (Message msg : messages) {
            if (msg.role() != MessageRole.TOOL) {
                result.add(msg);
                continue;
            }
            String content = msg.content();
            if (content == null || content.length() <= maxInlineChars) {
                result.add(msg);
                continue;
            }

            try {
                Path file = spillDir.resolve(sanitize(msg.toolCallId()) + ".txt");
                Files.writeString(file, content,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

                String preview = content.substring(0, previewChars);
                String replacement = preview
                        + "\n\n[... " + (content.length() - previewChars)
                        + " more chars written to " + file + ". "
                        + "Use file_read to read full content if needed.]";

                result.add(Message.tool(msg.toolCallId(), replacement));
                spilledCount++;

            } catch (Exception e) {
                log.warn("Failed to spill tool result for id={}", msg.toolCallId(), e);
                result.add(msg);
            }
        }

        if (spilledCount > 0) {
            log.debug("ToolResultSpill applied: {} tool result(s) spilled to {}",
                    spilledCount, spillDir);
        }
        return result;
    }

    /** 把 toolCallId 转成安全的文件名。 */
    private static String sanitize(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return "unknown_" + System.currentTimeMillis();
        }
        return toolCallId.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    @Override
    public String name() {
        return "ToolResultSpill";
    }
}