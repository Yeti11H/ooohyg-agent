package com.ooohyg.agent.adapter;

import com.ooohyg.agent.strategy.plan.PlanParser;
import com.ooohyg.agent.strategy.plan.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按行解析的计划解析器。
 *
 * <p>把模型返回的计划文本按行拆成步骤：去掉空行，去掉行首的
 * 序号/项目符号（如 {@code 1.}、{@code -}、{@code #}），
 * 剩余内容作为 {@link PlanStep} 的 description。
 *
 * <p><b>契约遵守：</b>返回不可变列表；解析失败（空文本 / 无有效行）
 * 时返回空列表（全有或全无，由 PlanParser 契约约定）；步骤 index
 * 从 0 开始连续递增。
 */
public final class LinePlanParser implements PlanParser {

    @Override
    public List<PlanStep> parse(String planText) {
        Objects.requireNonNull(planText, "planText must not be null");

        List<PlanStep> steps = new ArrayList<>();
        int index = 0;

        for (String rawLine : planText.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String description = stripPrefix(line);
            if (description.isEmpty()) {
                continue;
            }
            steps.add(PlanStep.pending(index, description));
            index++;
        }

        if (steps.isEmpty()) {
            return List.of();
        }
        return List.copyOf(steps);
    }

    /** 去掉行首的序号/项目符号前缀：{@code 1.}、{@code 1、}、{@code -}、{@code *}、{@code #}。 */
    private static String stripPrefix(String line) {
        String s = line;
        while (true) {
            if (s.startsWith("# ")) {
                s = s.substring(2).trim();
            } else if (s.startsWith("- ") || s.startsWith("* ")) {
                s = s.substring(2).trim();
            } else if (s.length() > 1 && Character.isDigit(s.charAt(0))
                    && (s.charAt(1) == '.' || s.charAt(1) == '、')) {
                s = s.substring(2).trim();
            } else {
                break;
            }
        }
        return s;
    }
}
