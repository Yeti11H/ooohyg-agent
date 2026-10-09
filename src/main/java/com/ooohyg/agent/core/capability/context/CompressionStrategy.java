package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.message.Message;

import java.util.List;

/**
 * 压缩策略。回答"怎么压缩"。
 *
 * <p>它接收一个消息列表，返回一个压缩后的新列表。
 * <b>不修改传入的列表</b>——这是纯函数契约。
 *
 * <p><b>为什么返回新列表而不是原地修改：</b>
 * <ul>
 *   <li>{@link Message} 是不可变 record，无法原地改；</li>
 *   <li>返回新列表让"输入 → 输出"的映射清晰，便于测试；</li>
 *   <li>原始列表保持不变，方便用户做对照或调试。</li>
 * </ul>
 *
 * <p><b>为什么需要 name()：</b>
 * 日志和监控需要知道"是哪一层压缩生效了"。如果只有一个 compress 方法，
 * 排查问题时无法定位具体是哪一层处理不当。
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>纯函数——相同输入返回相同输出；</li>
 *   <li>不修改传入的 messages 列表；</li>
 *   <li>线程安全；</li>
 *   <li>返回的列表非 null（可以是空列表）。</li>
 * </ul>
 */
public interface CompressionStrategy {

    /**
     * 对消息列表执行压缩。
     *
     * @param messages 待压缩的消息列表，非 null
     * @param context  压缩上下文，非 null
     * @return 压缩后的新列表，非 null
     */
    List<Message> compress(List<Message> messages, CompressionContext context);

    /**
     * 策略名称。用于日志和监控，比如 "ToolResultClearing"。
     *
     * @return 非 null、非空白的名称
     */
    String name();
}
