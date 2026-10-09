package com.ooohyg.agent.core.capability.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SubAgentRegistry} 的默认实现：构造后不可变，重名在构造期检测。
 *
 * <p>与 {@link com.ooohyg.agent.core.capability.tool.DefaultToolRegistry}
 * 采用完全相同的实现策略——这保证用户在两个 Registry 之间切换时，
 * 语义、边界处理、错误信息风格都一致，降低学习成本。
 */
public final class DefaultSubAgentRegistry implements SubAgentRegistry {

    /** 名字 → 定义 的索引。构造后不再修改。 */
    private final Map<String, SubAgentDefinition> byName;

    /** 所有定义的快照。构造后不再修改。 */
    private final List<SubAgentDefinition> definitions;

    /**
     * 用一组子 Agent 定义构造注册表。
     *
     * @param definitions 子 Agent 定义列表，非 null。允许空列表。
     *                    元素不得为 null，name 不得重复。
     * @throws NullPointerException     definitions 为 null 或含 null 元素
     * @throws IllegalArgumentException 两个子 Agent 名字相同
     */
    public DefaultSubAgentRegistry(List<SubAgentDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");

        Map<String, SubAgentDefinition> index = new HashMap<>();
        List<SubAgentDefinition> snapshot = new ArrayList<>(definitions.size());

        for (SubAgentDefinition def : definitions) {
            Objects.requireNonNull(def, "definition must not be null");
            SubAgentDefinition existing = index.putIfAbsent(def.name(), def);
            if (existing != null) {
                throw new IllegalArgumentException(
                        "Duplicate sub-agent name: '" + def.name()
                                + "'. SubAgentRegistry requires unique names.");
            }
            snapshot.add(def);
        }

        this.byName = Map.copyOf(index);
        this.definitions = List.copyOf(snapshot);
    }

    @Override
    public Optional<SubAgentDefinition> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public List<SubAgentDefinition> definitions() {
        return definitions;
    }
}