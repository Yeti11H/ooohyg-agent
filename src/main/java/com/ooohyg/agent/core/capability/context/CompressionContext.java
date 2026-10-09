package com.ooohyg.agent.core.capability.context;

/**
 * 压缩上下文。给触发器和策略提供判断依据。
 *
 * <p>它承载的是"压缩决策需要知道的当前状态"——
 * 比如还有多少 token 预算、当前消息数、压缩轮次等。
 * 它不承载"业务数据"，只承载"压缩算法需要的指标"。
 *
 * <p><b>为什么是 record：</b>纯数据，构造后不变。每次压缩时新建一个，
 * 承载当次调用的快照。
 */
public record CompressionContext(
        int tokenBudget,
        int estimatedTokens,
        int messageCount,
        int compressionRound
) {

    /**
     * 紧凑构造器：校验字段合法性。
     */
    public CompressionContext {
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException(
                    "tokenBudget must be positive, got: " + tokenBudget);
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException(
                    "estimatedTokens must not be negative, got: " + estimatedTokens);
        }
        if (messageCount < 0) {
            throw new IllegalArgumentException(
                    "messageCount must not be negative, got: " + messageCount);
        }
        if (compressionRound < 0) {
            throw new IllegalArgumentException(
                    "compressionRound must not be negative, got: " + compressionRound);
        }
    }

    /**
     * 当前 token 使用率（0.0 到 1.0 之间）。
     *
     * <p>触发器常用它做判断，比如 "使用率超过 0.8 才压缩"。
     */
    public double usageRatio() {
        return (double) estimatedTokens / tokenBudget;
    }
}
