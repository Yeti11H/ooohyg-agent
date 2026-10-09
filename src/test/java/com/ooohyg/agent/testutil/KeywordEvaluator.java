package com.ooohyg.agent.testutil;

import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.reflexion.EvaluationResult;
import com.ooohyg.agent.strategy.reflexion.Evaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 【测试用途】基于关键词的 Evaluator 实现。
 *
 * <p>规则评估器：候选结果必须同时包含构造时给定的全部关键词才算通过；
 * 子策略业务失败（success=false）直接不通过。反馈信息面向模型可读。
 *
 * <p>纯函数、无副作用、线程安全。
 */
public final class KeywordEvaluator implements Evaluator {

    private final List<String> keywords;

    public KeywordEvaluator(String... keywords) {
        Objects.requireNonNull(keywords, "keywords must not be null");
        List<String> kws = new ArrayList<>(keywords.length);
        for (String kw : keywords) {
            Objects.requireNonNull(kw, "keyword must not be null");
            if (kw.isBlank()) {
                throw new IllegalArgumentException("keyword must not be blank");
            }
            kws.add(kw);
        }
        this.keywords = List.copyOf(kws);
    }

    @Override
    public EvaluationResult evaluate(String originalTask, AgentResult candidateResult) {
        Objects.requireNonNull(originalTask, "originalTask must not be null");
        Objects.requireNonNull(candidateResult, "candidateResult must not be null");

        if (!candidateResult.success()) {
            return EvaluationResult.reject(
                    "上一次尝试执行失败：" + candidateResult.failureMessage()
                            + "。请重新尝试完成任务。");
        }

        String output = candidateResult.output();
        List<String> missing = new ArrayList<>();
        for (String kw : keywords) {
            if (!output.contains(kw)) {
                missing.add(kw);
            }
        }

        if (missing.isEmpty()) {
            return EvaluationResult.pass();
        }
        return EvaluationResult.reject(
                "输出缺少以下关键内容：" + String.join("、", missing)
                        + "。请修正后重新给出完整答案。");
    }
}
