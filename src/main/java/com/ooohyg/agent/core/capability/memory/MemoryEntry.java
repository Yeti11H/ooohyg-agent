package com.ooohyg.agent.core.capability.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条记忆的运行时表示（不落盘形态，供注入/老化计算使用）。
 *
 * <p>{@code savedAt} 记录记忆保存时间，用于老化警告：
 * 距离保存时间越久，记忆越可能已过时，注入时应提示模型主动验证。
 */
public record MemoryEntry(
        String name,
        String content,
        Instant savedAt
) {

    /**
     * 紧凑构造器：name 与 content 非空校验。
     */
    public MemoryEntry {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        Objects.requireNonNull(savedAt, "savedAt must not be null");
    }
}
