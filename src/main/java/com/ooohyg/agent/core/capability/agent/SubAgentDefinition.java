package com.ooohyg.agent.core.capability.agent;

import com.ooohyg.agent.core.capability.tool.PermissionDecider;
import com.ooohyg.agent.core.capability.tool.ToolRegistry;
import com.ooohyg.agent.strategy.ExecutionStrategy;

import java.util.Objects;

/**
 * 子 Agent 的定义。
 *
 * <p>它描述一个子 Agent 的"身份"和"能力"：
 * <ul>
 *   <li><b>身份</b>（name + description）——主 Agent 据此判断"有没有它、什么时候用它"；</li>
 *   <li><b>能力</b>（strategy + toolRegistry）——决定它"怎么干、能用什么"；</li>
 *   <li><b>权限</b>（permissionDecider）——子 Agent 内部自己的权限规则；</li>
 *   <li><b>预算</b>（maxSteps）——防止它跑飞。</li>
 * </ul>
 *
 * <p><b>为什么 name 和 description 必填：</b>
 * 主 Agent 通过工具描述选择子 Agent。{@link SubAgentTool} 会把所有
 * 子 Agent 的 name 和 description 拼进它的工具描述里，模型据此做
 * "要不要派 + 派哪个"的一次性判断。没有这两个字段，模型根本不知道
 * 有哪些子 Agent 可选。
 *
 * <p><b>为什么 strategy 和 toolRegistry 要一起传：</b>
 * 二者必须一致——策略拿着一个工具集去找工具，执行时必须用同一个。
 * 如果传两个不同的 registry，会出现"策略看到一个工具集、
 * 执行时用另一个"的诡异 bug。
 *
 * <p><b>为什么 permissionDecider 允许为 null：</b>
 * 为 null 表示"子 Agent 内部不做权限检查"。这是一个显式的选择——
 * 由装配者决定。默认不建议为 null，但如果用户想简化，可以显式跳过。
 *
 * <p><b>为什么 maxSteps 应该比主 Agent 小：</b>
 * 子 Agent 被派去完成的是子任务，它的预算不该超过整个主任务。
 * 常见的经验值是主 Agent 的 1/2 到 1/3。
 */
public record SubAgentDefinition(
        String name,
        String description,
        ExecutionStrategy strategy,
        ToolRegistry toolRegistry,
        PermissionDecider permissionDecider,
        int maxSteps
) {

    /**
     * 紧凑构造器：全部字段校验。
     */
    public SubAgentDefinition {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        // permissionDecider 允许为 null
        if (maxSteps <= 0) {
            throw new IllegalArgumentException(
                    "maxSteps must be positive, got: " + maxSteps);
        }
    }
}