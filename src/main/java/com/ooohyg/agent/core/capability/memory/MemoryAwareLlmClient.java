package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.message.Message;

import java.util.List;
import java.util.Objects;

/**
 * 带记忆的模型客户端装饰器。
 *
 * <p>它在"调用真正的模型客户端"之前，先让 {@link ShortTermMemory}
 * 对消息列表做一次增强，然后用增强后的列表调用底层客户端。
 * 消息列表被压缩、裁剪或补上摘要，模型看到的是加工后的版本。
 *
 * <p><b>为什么用装饰器，而不是让策略直接调记忆：</b>
 * <ul>
 *   <li>策略只认识 {@link LlmClient} 接口。加不加记忆，策略代码
 *       一行都不用改；</li>
 *   <li>三个策略（ReAct、Plan、Reflexion）都会调 {@code chat}，
 *       装饰器一处生效，三处自动获得记忆能力。如果让策略显式调，
 *       三个策略都要 import 记忆包，都要在调模型前手动加工——重复且易漏；</li>
 *   <li>装配时决定"包不包记忆"：
 *       <pre>{@code
 *         // 不需要记忆
 *         LlmClient client = new OpenAiLlmClient(...);
 *
 *         // 需要记忆
 *         LlmClient client = new MemoryAwareLlmClient(
 *                 new OpenAiLlmClient(...),
 *                 shortTermMemory
 *         );
 *       }</pre>
 *       同一个策略，两种装配方式，运行期代码完全一样。</li>
 * </ul>
 *
 * <p><b>为什么放在 core/capability/memory 而不是 llm：</b>
 * <ul>
 *   <li>它是"记忆能力的一部分"，不是模型客户端的一部分；</li>
 *   <li>放在 memory 包下，与 {@link ShortTermMemory} 同包，
 *       读代码时"接口 + 装饰器"一起呈现，脉络清晰；</li>
 *   <li>它 import llm 包——memory 依赖 llm，反过来不成立。
 *       这是单向依赖，符合分层。</li>
 * </ul>
 *
 * <p><b>为什么装饰器只调一次 memory.enrich：</b>
 * <ul>
 *   <li>每次 {@code chat} 对应一次模型调用，也就对应一次"当前消息状态"。
 *       调两次没有额外语义，只会重复加工；</li>
 *   <li>如果记忆实现需要在"加工前后"做额外动作（比如记录日志），
 *       那是记忆实现的内部事，装饰器不需要为它留钩子。</li>
 * </ul>
 *
 * <p><b>tools 参数如何处理：</b>
 * 原样传给底层客户端。工具列表与记忆无关——记忆管"历史消息"，
 * 工具是"本次可用的能力"，两者互不影响。装饰器不碰 tools。
 *
 * <p><b>不修改传入的 messages：</b>
 * 装饰器把原 messages 交给记忆，记忆返回新列表；装饰器拿新列表
 * 调底层客户端。调用方传进来的 messages 列表本身不被修改——
 * 这是 {@link ShortTermMemory#enrich} 的契约，装饰器只是遵守它。
 *
 * <p><b>异常契约：</b>
 * <ul>
 *   <li>记忆加工失败（如摘要模型调用失败）→ 抛
 *       {@link com.ooohyg.agent.core.exception.AgentException}，
 *       由上层（策略 → BaseAgent）转 ERROR 状态；</li>
 *   <li>底层客户端调用失败 → 原样向上传播，装饰器不吞不改；</li>
 *   <li>装饰器自己不抛异常——它是纯转发层。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>本类无状态、线程安全。它依赖的两个组件（底层 LlmClient
 *       和 ShortTermMemory）各自需要保证线程安全——那是它们的契约；</li>
 *   <li>本类不得缓存入参到实例字段。每次 chat 的 messages 和 tools
 *       都是当次快照，不跨调用共享。</li>
 * </ul>
 */
public final class MemoryAwareLlmClient implements LlmClient {

    private final LlmClient llmClient;
    private final ShortTermMemory shortTermMemory;

    /**
     * 构造装饰器。
     *
     * @param delegate        被装饰的模型客户端，非 null。
     *                        可以是任意 LlmClient 实现——
     *                        真实客户端、或另一个装饰器（可嵌套）
     * @param shortTermMemory 短期记忆实现，非 null
     */
    public MemoryAwareLlmClient(LlmClient delegate, ShortTermMemory shortTermMemory) {
        this.llmClient = Objects.requireNonNull(delegate, "delegate must not be null");
        this.shortTermMemory = Objects.requireNonNull(shortTermMemory,
                "shortTermMemory must not be null");
    }

    @Override
    public LlmResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        Objects.requireNonNull(messages, "messages must not be null");

        // 记忆加工：返回新列表，不动入参。
        List<Message> enrichedMessages = shortTermMemory.enrich(messages);

        // tools 原样传递——记忆与工具无关。
        return llmClient.chat(enrichedMessages, tools);
    }
}