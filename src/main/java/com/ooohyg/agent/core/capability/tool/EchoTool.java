package com.ooohyg.agent.core.capability.tool;

import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;

/**
 * 回显工具。把模型传入的 arguments 原样返回。
 *
 * <p>它的存在只有一个目的：<b>让 ReactStrategy 能跑通一次完整的
 * think-act-observe 循环，而不需要真实外部依赖</b>。用它可以写第一个
 * 集成测试：模型说要调 echo，策略调它，把结果喂回模型，
 * 模型给出最终答案。
 *
 * <p><b>为什么放在 core 而不是 adapter：</b>
 * <ul>
 *   <li>它不依赖任何外部技术——没有 HTTP、没有数据库、没有 JSON 库；</li>
 *   <li>它只用 Java 标准库，符合 core 层的硬约束；</li>
 *   <li>它是"最小可运行示例"，放在 core 便于被测试直接引用；</li>
 *   <li>真实工具（HttpTool、SearchTool、SqlTool）才需要放 adapter，
 *       因为它们必然引入外部依赖。</li>
 * </ul>
 *
 * <p><b>为什么 execute 不解析 arguments：</b>
 * <ul>
 *   <li>core 不引入 JSON 解析依赖（硬约束）；</li>
 *   <li>EchoTool 的语义就是"回显"，不关心结构——原样返回最诚实；</li>
 *   <li>parametersSchema 里说明"任意对象"，模型传什么就回什么。</li>
 * </ul>
 *
 * <p><b>为什么不加构造参数：</b>没有可配置项。名字固定、描述固定、
 * schema 固定。等出现第一个需要配置的工具（如 HttpTool 的 baseUrl），
 * 再给那个工具加构造器，不给 EchoTool 加。
 *
 * <p><b>为什么 definition 缓存为静态常量：</b>{@link ToolDefinition}
 * 是不可变值对象，EchoTool 的 definition 每次调用结果都相同，
 * 缓存避免重复分配。静态常量在类加载时构造一次，线程安全。
 */
public final class EchoTool implements Tool {

    /**
     * EchoTool 的对外描述，类加载时构造一次。
     *
     * <p>schema 写成"任意对象"，因为本工具不解析参数结构。
     * 写具体的 schema（如要求 message 字段）反而会误导模型——
     * 明明不解析，就不该假装有结构。
     */
    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "echo",
            "回显传入的参数。用于测试 Agent 循环、验证工具调用链路是否连通。"
                    + "输入什么，就原样返回什么，不做任何处理。",
            "{\"type\":\"object\",\"description\":\"任意参数，会被原样回显\"}"
    );

    /**
     * 无参构造。EchoTool 不需要任何配置。
     */
    public EchoTool() {
        // 无字段可初始化。
    }

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    /**
     * 原样回显。
     *
     * <p>加 {@code "Echo: "} 前缀是为了让模型在 TOOL 消息里一眼能看出
     * 这是回显结果，而不是别的内容。前缀本身不改变语义。
     *
     * @param arguments 模型返回的原始字符串，非 null（由 Tool 接口契约保证）
     */
    @Override
    public ToolResult execute(String arguments) {
        return ToolResult.success("Echo: " + arguments);
    }
}