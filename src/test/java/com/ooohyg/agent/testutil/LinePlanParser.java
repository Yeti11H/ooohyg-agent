package com.ooohyg.agent.testutil;

import com.ooohyg.agent.strategy.plan.PlanParser;
import com.ooohyg.agent.strategy.plan.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 【测试用途】按行解析的 PlanParser 实现。
 *
 * <p>约定模型输出格式：每行一个步骤（与测试用 ScriptedLlmClient 的脚本约定一致）。
 * <ul>
 *   <li>空行/纯空白行跳过；</li>
 *   <li><b>全有或全无</b>：任意非空行以 "#" 开头（视为格式不合规）时，
 *       整个解析返回空列表——遵守 PlanParser 的契约；</li>
 *   <li>index 由解析器从 0 连续分配。</li>
 * </ul>
 *
 * <p>纯函数、无副作用、线程安全。
 */
public final class LinePlanParser implements PlanParser {

    @Override
    public List<PlanStep> parse(String rawModelOutput) {
        Objects.requireNonNull(rawModelOutput, "rawModelOutput must not be null");

        String[] lines = rawModelOutput.split("\n");
        List<String> steps = new ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("#")) {
                // 全有或全无：发现不合规行，整体判定为解析失败
                return List.of();
            }
            steps.add(trimmed);
        }

        List<PlanStep> result = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            result.add(PlanStep.pending(i, steps.get(i)));
        }
        return List.copyOf(result);
    }
}
