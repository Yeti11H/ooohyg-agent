package com.ooohyg.agent.core.capability.tool;

/**
 * 权限决策结果。
 *
 * <p>三种结果覆盖所有可能：直接放行、直接拒绝、挂起等审批。
 *
 * <p><b>REQUIRE_APPROVAL 的第一版处理：</b>
 * 项目目前没有状态持久化，无法真正"挂起等待"。所以第一版把它
 * 转成 {@code ToolResult.failure}，让模型向用户说明"这个动作需要审批"。
 * 真正的异步审批属于 runtime 层的能力。
 */
public enum Decision {

    /** 允许执行。 */
    ALLOW,

    /** 拒绝执行。转成 ToolResult.failure 喂回模型。 */
    DENY,

    /** 需要用户审批。第一版简化为转成失败，让模型向用户说明。 */
    REQUIRE_APPROVAL
}