package com.ooohyg.agent.testutil;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.message.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 【测试用途】脚本化 LlmClient 实现。
 *
 * <p>不接真实 LLM。按构造时给定的响应队列依次返回；队列耗尽时：
 * <ul>
 *   <li>{@code repeatLast=true}：无限重复最后一个响应（用于测试 ReAct 步数耗尽场景）；</li>
 *   <li>{@code repeatLast=false}：抛 {@link AgentException}（技术故障路径）。</li>
 * </ul>
 *
 * <p>线程安全（synchronized），符合 {@link LlmClient} 接口契约。
 */
public final class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> script;
    private final List<ChatCall> calls = new ArrayList<>();
    private final boolean repeatLast;
    private LlmResponse last;

    /**
     * @param script 响应脚本，非 null；为空时任何调用都会抛 AgentException
     */
    public ScriptedLlmClient(List<LlmResponse> script) {
        this(script, false);
    }

    public ScriptedLlmClient(List<LlmResponse> script, boolean repeatLast) {
        Objects.requireNonNull(script, "script must not be null");
        this.script = new ArrayDeque<>(script);
        this.repeatLast = repeatLast;
    }

    @Override
    public synchronized LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be null or empty");
        }
        calls.add(new ChatCall(
                List.copyOf(messages),
                tools == null ? List.of() : List.copyOf(tools)));

        LlmResponse next = script.poll();
        if (next == null) {
            if (repeatLast && last != null) {
                return last;
            }
            throw new AgentException(
                    "ScriptedLlmClient script exhausted after " + calls.size() + " call(s)");
        }
        if (repeatLast) {
            last = next;
        }
        return next;
    }

    /** 已收到的全部调用记录（不可变快照），供断言使用。 */
    public synchronized List<ChatCall> calls() {
        return List.copyOf(calls);
    }

    /** 一次 chat 调用的入参快照。 */
    public record ChatCall(List<Message> messages, List<ToolDefinition> tools) {
    }
}
