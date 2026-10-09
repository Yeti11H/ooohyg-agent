package com.ooohyg.agent.core.capability.context;

import com.ooohyg.agent.core.message.Message;

import java.util.List;

/**
 * 压缩触发器。回答"什么时候该压缩"。
 *
 * <p>它和 {@link CompressionStrategy} 是配套的：触发器决定"要不要压"，
 * 策略决定"怎么压"。把两者拆开，用户就能自由组合——
 * 比如"每轮都清理工具结果"和"token 超 80% 才做摘要"用的是同一个
 * 触发器接口，但触发条件不同。
 *
 * <p><b>为什么是函数式接口：</b>
 * 因为它只有一个方法，且实现通常是简单的一行判断（如
 * {@code ctx -> ctx.usageRatio() > 0.8}）。函数式接口让用户可以用
 * lambda 表达式定义触发器，不用新建一个类。
 *
 * <p><b>为什么接收 messages 和 context 两个参数：</b>
 * <ul>
 *   <li>{@code messages} —— 有些触发器需要看消息内容（比如"检测到
 *       重复的工具调用"）；</li>
 *   <li>{@code context} —— 有些触发器只需要看指标（比如"token 使用率"）。</li>
 * </ul>
 * 两个都给，让实现者自己选择用哪个。如果只给一个，会限制表达力。
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>必须是纯函数——相同输入永远返回相同结果，不能有副作用；</li>
 *   <li>不得修改 messages 列表；</li>
 *   <li>线程安全——可能在多线程下被调用。</li>
 * </ul>
 */
@FunctionalInterface
public interface CompressionTrigger {

    /**
     * 判断当前是否应该执行压缩。
     *
     * @param messages 当前消息列表，非 null
     * @param context  压缩上下文，非 null
     * @return 需要压缩返回 true，否则 false
     */
    boolean shouldCompress(List<Message> messages, CompressionContext context);
}
