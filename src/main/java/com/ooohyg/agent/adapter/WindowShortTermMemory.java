package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.capability.memory.ShortTermMemory;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 固定窗口短期记忆。
 *
 * <p>保留对话最近 {@code windowSize} 条消息，并且<b>始终保留第一条
 * SYSTEM 消息</b>（系统指令不应被挤出窗口）。超出窗口的旧消息被丢弃。
 *
 * <p><b>契约遵守：</b>{@link ShortTermMemory#enrich} 返回新列表，
 * 不修改入参。返回的列表为不可变拷贝，调用方无法通过外部引用改动内部状态。
 */
public final class WindowShortTermMemory implements ShortTermMemory {

    private final int windowSize;

    /**
     * @param windowSize 窗口大小（包含首条 SYSTEM 消息），必须 ≥ 2
     */
    public WindowShortTermMemory(int windowSize) {
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be >= 2, got: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    @Override
    public List<Message> enrich(List<Message> messages) {
        Objects.requireNonNull(messages, "messages must not be null");

        List<Message> head = new ArrayList<>();
        List<Message> tail = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == MessageRole.SYSTEM && head.isEmpty()) {
                head.add(msg);
            } else {
                tail.add(msg);
            }
        }

        int keepTail = Math.max(0, windowSize - head.size());
        List<Message> keptTail = tail.subList(
                Math.max(0, tail.size() - keepTail), tail.size());

        List<Message> result = new ArrayList<>(head.size() + keptTail.size());
        result.addAll(head);
        result.addAll(keptTail);
        return List.copyOf(result);
    }
}
