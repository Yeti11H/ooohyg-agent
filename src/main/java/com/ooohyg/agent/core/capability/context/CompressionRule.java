package com.ooohyg.agent.core.capability.context;

import java.util.Objects;

/**
 * 压缩规则。把"什么时候压"和"怎么压"绑在一起。
 *
 * <p>用户配置压缩时，只需要给一组 CompressionRule。框架按顺序检查
 * 每条规则：如果触发器返回 true，就执行对应的策略。
 *
 * <p><b>为什么用 record 而不是让用户直接传两个参数：</b>
 * <ul>
 *   <li>绑定后便于管理——一个规则是一个完整单元，
 *       可以单独启用、禁用、排序；</li>
 *   <li>避免调用方忘记配对——如果分别传 trigger 列表和 strategy 列表，
 *       极易出现"顺序错位"的 bug；</li>
 *   <li>record 天然不可变，配置后不会被意外修改。</li>
 * </ul>
 *
 * <p><b>规则按顺序执行：</b>
 * 框架会按 list 里的顺序依次检查。前一条规则压缩后的结果，
 * 会传给下一条规则作为输入。所以顺序很重要——
 * 通常"轻量的清理"放前面，"重量的摘要"放后面。
 */
public record CompressionRule(
        CompressionTrigger trigger,
        CompressionStrategy strategy
) {

    /**
     * 紧凑构造器：校验两个字段非 null。
     */
    public CompressionRule {
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
    }
}
