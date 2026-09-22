package com.ooohyg.agent.core.capability.tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ToolRegistry} 的默认实现：把工具放在内存里的不可变注册表。
 *
 * <p><b>为什么叫 Default 而不是 InMemory：</b>
 * <ul>
 *   <li>"InMemory"暗示存储介质，容易和 Agent 的"记忆系统（Memory）"
 *       在中文语境里混淆——两者毫无关系；</li>
 *   <li>"Default"表达"默认实现"，与项目中已有的
 *       {@link com.ooohyg.agent.core.execution.DefaultAgentContext}
 *       命名风格一致；</li>
 *   <li>未来若出现 CachedToolRegistry / RemoteToolRegistry，
 *       "Default"这个前缀不暗示任何具体机制，不会过时。</li>
 * </ul>
 *
 * <p><b>不可变性是本实现的核心设计：</b>
 * <ul>
 *   <li>构造时一次性接收所有工具，之后不再修改；</li>
 *   <li>没有 register / unregister 方法——第一版没有"运行时动态注册"
 *       的真实需求；</li>
 *   <li>构造后字段全 final，天然线程安全，不需要锁；</li>
 *   <li>{@link #definitions()} 返回的是构造时就算好的不可变快照，
 *       调用方无法通过它修改内部状态。</li>
 * </ul>
 *
 * <p><b>为什么在构造时建索引和快照：</b>
 * <ul>
 *   <li>工具数量少、构造后不变，构造期一次性完成比运行时懒加载更简单；</li>
 *   <li>避免每次 {@link #definitions()} 调用都重新遍历 + 分配；</li>
 *   <li>把"重名检测"提前到构造期，让错误在使用前就暴露。</li>
 * </ul>
 *
 * <p><b>刻意不做的事：</b>
 * <ul>
 *   <li>不提供 {@code register} / {@code unregister}——构造后不可变；</li>
 *   <li>不做懒加载——工具在构造时就要全部就绪，避免"用的时候才发现
 *       缺工具"这种延迟失败；</li>
 *   <li>不做名字的模糊匹配——精确匹配，避免"模糊匹配选出意外工具"
 *       这种难以排查的 bug。</li>
 * </ul>
 */
public final class DefaultToolRegistry implements ToolRegistry {

    /**
     * name → Tool 的索引。构造后不再修改，读取不需要同步。
     */
    private final Map<String, Tool> toolByName;

    /**
     * 所有工具的 definition 快照。构造后不再修改。
     *
     * <p>预计算的原因是 {@link #definitions()} 的调用方（策略）会在
     * 每次调模型前调用它，重复遍历列表做映射是浪费。
     */
    private final List<ToolDefinition> definitions;

    /**
     * 用一组工具构造注册表。
     *
     * <p>构造时完成三件事：
     * <ol>
     *   <li>校验入参非 null、元素非 null；</li>
     *   <li>检测重名——发现两个工具的 definition().name() 相同时，
     *       直接抛 {@link IllegalArgumentException}；</li>
     *   <li>构建索引和 definition 快照。</li>
     * </ol>
     *
     * @param tools 工具列表，非 null。允许空列表（表示无工具可用）。
     *              列表元素不得为 null，name 不得重复。
     * @throws NullPointerException     tools 为 null 或含 null 元素
     * @throws IllegalArgumentException 两个工具 name 相同
     */
    public DefaultToolRegistry(List<Tool> tools) {
        Objects.requireNonNull(tools, "tools must not be null");

        Map<String, Tool> index = new HashMap<>();
        List<ToolDefinition> defs = new ArrayList<>(tools.size());

        for (Tool tool : tools) {
            Objects.requireNonNull(tool, "tool must not be null");
            ToolDefinition def = tool.definition();
            String name = def.name();

            // putIfAbsent 的返回值：如果之前已存在，返回旧值（非 null）；
            // 否则插入新值并返回 null。据此检测重名。
            Tool existing = index.putIfAbsent(name, tool);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate tool name: '" + name + "'. "
                                + "ToolRegistry requires unique names.");
            }

            defs.add(def);
        }

        this.toolByName = Map.copyOf(index);
        this.definitions = List.copyOf(defs);
    }

    @Override
    public Optional<Tool> find(String name) {
        // name 为 null 时 Map.get(null) 在 HashMap 上返回 null，
        // 但 Map.copyOf 返回的不可变 Map 对 null key 的行为是实现细节，
        // 这里显式判空，语义明确：null 等价于"没找到"。
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolByName.get(name));
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions;
    }
}