package com.ooohyg.agent.testutil;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

/**
 * 【测试用途】加法工具：arguments 格式 "1+2"（两个整数，+ 分隔）。
 * 非法格式返回业务失败（ToolResult.failure），不抛技术异常。
 */
public final class AddTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "add",
            "计算两个整数的和。参数格式：\"a+b\"，例如 \"1+2\"。",
            "{\"type\":\"object\",\"description\":\"整数加法\"}"
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
        String[] parts = arguments.trim().split("\\+");
        if (parts.length != 2) {
            return ToolResult.failure("Invalid add arguments: " + arguments
                    + " (expected format: a+b)");
        }
        try {
            int a = Integer.parseInt(parts[0].trim());
            int b = Integer.parseInt(parts[1].trim());
            return ToolResult.success("Result: " + (a + b));
        } catch (NumberFormatException e) {
            return ToolResult.failure("Invalid integer in add arguments: " + arguments);
        }
    }
}
