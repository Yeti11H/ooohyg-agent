package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.reflexion.EvaluationResult;
import com.ooohyg.agent.strategy.reflexion.Evaluator;

import java.util.List;
import java.util.Objects;

/**
 * 关键词评估器。
 *
 * <p>检查 Agent 输出是否包含全部指定关键词。全部包含 → 通过；
 * 缺任何一个 → 失败，并在 feedback 里列出缺失的关键词。
 * 典型用途：Reflexion 策略中验证输出是否满足"必须提到 X"的约束。
 *
 * <p><b>契约遵守：</b>业务失败走 {@link EvaluationResult#reject} 返回，
 * 不抛异常。
 */
public final class KeywordEvaluator implements Evaluator {

    private final List<String> requiredKeywords;

    /**
     * @param requiredKeywords 输出必须包含的关键词，非 null
     */
    public KeywordEvaluator(List<String> requiredKeywords) {
        Objects.requireNonNull(requiredKeywords, "requiredKeywords must not be null");
        this.requiredKeywords = List.copyOf(requiredKeywords);
    }

    @Override
    public EvaluationResult evaluate(String originalTask, AgentResult candidateResult) {
        Objects.requireNonNull(originalTask, "originalTask must not be null");
        Objects.requireNonNull(candidateResult, "candidateResult must not be null");

        String output = candidateResult.output() == null ? "" : candidateResult.output();

        List<String> missing = requiredKeywords.stream()
                .filter(kw -> !output.contains(kw))
                .toList();

        if (missing.isEmpty()) {
            return EvaluationResult.pass();
        }
        return EvaluationResult.reject(
                "输出缺少以下关键内容：" + String.join("、", missing)
                        + "。请重新完成任务，并在回答中包含这些关键词。");
    }
}
