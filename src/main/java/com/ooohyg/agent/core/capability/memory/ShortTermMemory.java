package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.message.Message;

import java.util.List;

/**
 * 短期记忆。在每次调用模型之前，对消息列表做增强。
 *
 * <p>短期记忆管理的是"当前这次执行中产生的消息"。随着 ReAct 多轮循环，
 * 消息列表会越来越长，最终超过模型的上下文窗口。短期记忆的职责是：
 * 在消息列表被送给模型之前，对它做一次加工——压缩、裁剪、或保留摘要，
 * 让它在预算内仍然承载最相关的信息。
 *
 * <p><b>为什么是接口，不是一个写死的实现：</b>
 * 短期记忆有多种真实策略，不是假想的扩展点：
 * <ul>
 *   <li>{@code SummarizingMemory}：调模型把旧消息压缩成一段摘要；</li>
 *   <li>{@code WindowMemory}：直接丢掉最旧的消息，保留最近 N 条；</li>
 *   <li>{@code TokenBudgetMemory}：按 token 数裁剪，超预算就丢最早的。</li>
 * </ul>
 * 三者不是同一个东西的不同写法，是三种不同策略。用户按场景选。
 * 没有接口，用户就只能用死一种。
 *
 * <p><b>为什么需要接口——装饰器要求：</b>
 * 短期记忆通常通过装饰 {@code LlmClient} 生效：
 * <pre>{@code
 *   LlmClient client = new MemoryAwareLlmClient(
 *           new OpenAiLlmClient(...),
 *           shortTermMemory
 *   );
 * }</pre>
 * 装饰器的字段类型如果是具体类，就绑死了只能配一种记忆。
 * 接口让"换记忆策略"不需要改装饰器代码。
 *
 * <p><b>为什么需要接口——测试要求：</b>
 * 端到端测试时，不想每次都调模型做摘要。注入一个"假记忆"，
 * {@link #enrich} 直接返回原列表即可。没接口就没法 mock。
 *
 * <p><b>为什么只接收 messages，不接收 AgentContext：</b>
 * <ul>
 *   <li>短期记忆只关心消息本身——它不看执行 id、不看 userInput、
 *       不看 maxSteps；</li>
 *   <li>给它 ctx 只是让实现多一个"能访问但用不上"的入口，
 *       是诱惑也是噪音；</li>
 *   <li>如果将来某个记忆策略确实需要额外信息（如用户 id），
 *       到那时再扩签名——有真实需求才改。</li>
 * </ul>
 *
 * <p><b>为什么返回 List&lt;Message&gt; 而不是 void：</b>
 * <ul>
 *   <li>{@link Message} 是不可变值对象，记忆无法"原地修改"它；</li>
 *   <li>返回新列表让"输入 → 输出"的关系清晰，调用方一眼看出
 *       记忆对消息做了什么；</li>
 *   <li>不修改传入的列表——调用方的 messages 不应该被记忆
 *       悄悄改掉。这是纯函数式的契约。</li>
 * </ul>
 *
 * <p><b>返回列表的语义约束（给实现者）：</b>
 * <ul>
 *   <li>返回的列表非 null、非空。若输入列表本身为空，原样返回空列表；</li>
 *   <li>列表第一条通常应当保留为 SYSTEM 消息——它是 Agent 的行为定义，
 *       属于"必须保留"的内容，压缩时应跳过；</li>
 *   <li>返回的 Message 元素可以是传入的实例（不强制拷贝），
 *       因为 Message 本身不可变，共享引用是安全的；</li>
 *   <li>返回顺序即对话顺序——调用方按列表顺序发送给模型。</li>
 * </ul>
 *
 * <p><b>实现建议：</b>
 * <ul>
 *   <li><b>判断阈值</b>：不是每次都要加工。常见做法是"当消息数量
 *       超过 N 条"或"当估算 token 超过 M"时才压缩，否则原样返回。
 *       具体阈值由实现决定，接口不规定；</li>
 *   <li><b>保留最近消息</b>：无论怎么压缩，最近几轮对话必须保留——
 *       模型需要当前任务的直接上下文；</li>
 *   <li><b>摘要的生成</b>：{@code SummarizingMemory} 需要调模型
 *       生成摘要，可以在构造时注入一个 LlmClient。注意这可能
 *       引入"记忆用模型、模型被记忆包裹"的循环——解法是
 *       摘要用的 LlmClient 不包记忆，直接调底层实现。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程下调用同一个记忆；</li>
 *   <li>实现不得修改传入的 messages 列表内容；</li>
 *   <li>实现不得把"每次调用的状态"放到实例字段——记忆的实现
 *       可以有内部状态（如缓存的摘要），但不得与单次调用绑定；</li>
 *   <li>调用底层模型失败、网络故障 → 抛 {@link AgentException}。</li>
 * </ul>
 */
public interface ShortTermMemory {

    /**
     * 对消息列表做一次增强，返回送给模型的新消息列表。
     *
     * <p>调用时机：每次调用 {@code LlmClient.chat} 之前。
     * 通常由 {@code MemoryAwareLlmClient} 装饰器调用。
     *
     * <p>本方法<b>不修改</b>传入的 messages 列表。返回的列表是新构造的，
     * 调用方的原列表保持不变。
     *
     * @param messages 当前的消息列表，非 null。允许为空列表
     * @return 增强后的消息列表，非 null、非空（若输入为空则返回空），
     *         顺序即对话顺序
     * @throws AgentException 记忆处理过程的技术故障
     *                        （如摘要模型调用失败）
     */
    List<Message> enrich(List<Message> messages);
}