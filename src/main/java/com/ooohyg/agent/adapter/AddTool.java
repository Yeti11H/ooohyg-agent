package com.ooohyg.agent.adapter;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

/**
 * 加法计算工具。
 *
 * <p>解析形如 {@code "1+2"} / {@code "3+4"} 的 arguments，返回两数之和。
 * 格式不合法时返回业务失败（failure），让模型看到原因后重试。
 *
 * <p><b>为什么解析"a+b"而不是 JSON：</b>工具语义固定为两数相加，
 * 纯文本格式最直接，也便于 {@code RuleBasedLlmClient} 无依赖构造参数。
 */
public final class AddTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "add",
            "计算两个整数的和。参数格式为 \"a+b\"，例如 \"1+2\"。",
            "{\"type\":\"object\",\"description\":\"形如 1+2 的加法表达式\"}"
    );

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(String arguments) {
        if (arguments == null) {
            return ToolResult.failure("arguments must not be null");
        }
        String trimmed = arguments.trim();
        int plusIdx = trimmed.indexOf('+');
        if (plusIdx <= 0 || plusIdx >= trimmed.length() - 1) {
            return ToolResult.failure("Unsupported format, expected \"a+b\", got: " + arguments);
        }
        try {
            long a = Long.parseLong(trimmed.substring(0, plusIdx).trim());
            long b = Long.parseLong(trimmed.substring(plusIdx + 1).trim());
            return ToolResult.success("计算结果：" + (a + b));
        } catch (NumberFormatException e) {
            return ToolResult.failure("Unsupported format, expected two integers: " + arguments);
        }
    }
}
