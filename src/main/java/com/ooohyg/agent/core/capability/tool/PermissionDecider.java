package com.ooohyg.agent.core.capability.tool;

/**
 * 权限决策器。判断某个工具调用是否被允许。
 *
 * <p>它是一个独立的类，不放在工具内部，也不放在 Registry 里。
 * 职责分离——工具负责"怎么执行"，决策器负责"能不能执行"。
 *
 * <p><b>调用时机：</b>ReactStrategy 在 executeTool 里，执行前先问一次。
 *
 * <p><b>为什么参数不带 AgentContext：</b>
 * 第一版不加——目前的决策规则（按工具名、按能力类型）不需要上下文。
 * 等出现"同一工具在不同场景下权限不同"的真实需求再加。
 */
public interface PermissionDecider {

    /**
     * 判断某个工具调用是否被允许。
     *
     * @param tool       要执行的工具，非 null
     * @param arguments  工具的参数（原始 JSON 字符串），非 null
     * @param toolCallId 本次调用的 id，非 null。用于日志关联
     * @return 决策结果，非 null
     */
    com.ooohyg.agent.core.capability.tool.Decision decide(Tool tool, String arguments, String toolCallId);
}