package com.ooohyg.agent.core.capability.memory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 记忆注入器：负责把记忆内容包装成注入到模型上下文的片段，并做时间感知处理。
 *
 * <p>核心设计来自 Claude Code 记忆机制："记忆说 X 存在"不等于"X 现在存在"。
 * 记忆保存超过一定天数后必须附带老化警告，逼模型在行动前主动验证其准确性。
 */
public final class MemoryInjector {

    /** 记忆保存超过该天数后视为可能过时，注入时附加 stale 警告。 */
    public static final long STALE_AFTER_DAYS = 2;

    private MemoryInjector() {
    }

    /**
     * 把一条记忆包装为注入文本，并根据保存时长附加老化警告。
     *
     * <p>返回格式：
     * <pre>{@code
     * <system-reminder>
     * This memory was saved N days ago. Verify it's still accurate before acting on it.
     * <记忆内容>
     * </system-reminder>
     * }</pre>
     *
     * @param entry 要注入的记忆条目
     * @return 可直接拼接到 system prompt 的注入文本
     */
    public static String injectMemoryWithAge(MemoryEntry entry) {
        long daysOld = ChronoUnit.DAYS.between(entry.savedAt(), Instant.now());
        String warning = "";
        if (daysOld >= STALE_AFTER_DAYS) {
            warning = "This memory was saved " + daysOld
                    + " days ago. Verify it's still accurate before acting on it.\n";
        }
        return "<system-reminder>\n" + warning + entry.content() + "\n</system-reminder>";
    }
}
