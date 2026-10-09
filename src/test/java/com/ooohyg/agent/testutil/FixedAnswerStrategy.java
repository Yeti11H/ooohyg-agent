package com.ooohyg.agent.testutil;

import com.ooohyg.agent.core.execution.AgentContext;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.StrategyType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 【测试用途】固定回答的子策略替身。
 *
 * <p>每次执行返回固定答案，并记录收到的 userInput（供断言 feedback 注入等场景）。
 * 用于隔离验证 Plan/Reflexion 的编排逻辑，不依赖任何 LLM。
 */
public final class FixedAnswerStrategy implements ExecutionStrategy {

    private final String answer;
    private final List<String> receivedInputs = new ArrayList<>();

    public FixedAnswerStrategy(String answer) {
        Objects.requireNonNull(answer, "answer must not be null");
        this.answer = answer;
    }

    @Override
    public StrategyType type() {
        // 测试替身：不属于任何真实策略，借用 REACT 标签
        return StrategyType.REACT;
    }

    @Override
    public synchronized AgentResult execute(AgentContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        receivedInputs.add(ctx.userInput());
        return AgentResult.success(answer, StrategyType.REACT);
    }

    /** 收到过的全部 userInput（不可变快照）。 */
    public synchronized List<String> receivedInputs() {
        return List.copyOf(receivedInputs);
    }
}
