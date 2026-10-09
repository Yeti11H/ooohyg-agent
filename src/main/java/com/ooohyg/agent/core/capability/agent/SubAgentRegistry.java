package com.ooohyg.agent.core.capability.agent;

import java.util.List;
import java.util.Optional;

/**
 * 子 Agent 注册表。
 *
 * <p>和 {@link com.ooohyg.agent.core.capability.tool.ToolRegistry} 同构：
 * 构造时一次性接收所有子 Agent 定义，之后不可变；
 * 按名字查找返回 {@link Optional}，避免抛异常。
 *
 * <p><b>为什么 find 返回 Optional 而不是抛异常：</b>
 * 模型可能在 {@code subagent_name} 里写错名字——这是"模型能通过
 * 重试修正"的业务失败，不是技术故障。返回 Optional 让
 * {@link SubAgentTool} 把"找不到"转成 {@code ToolResult.failure}
 * 喂回主 Agent，主 Agent 看到错误后能换一个名字重试。
 *
 * <p><b>为什么不做 register / unregister：</b>
 * 第一版没有"运行时动态增删子 Agent"的真实需求。构造时一次性
 * 装配，之后只读，天然线程安全。
 */
public interface SubAgentRegistry {

    /**
     * 按名字查找子 Agent 定义。
     *
     * @param name 子 Agent 名字，非 null。允许空串——结果必然是 empty
     * @return 找到返回 {@code Optional.of(definition)}，否则返回 empty
     */
    Optional<SubAgentDefinition> find(String name);

    /**
     * 所有已注册子 Agent 的定义列表，供 {@link SubAgentTool} 拼描述用。
     *
     * @return 不可变列表，可能为空，永不为 null
     */
    List<SubAgentDefinition> definitions();
}