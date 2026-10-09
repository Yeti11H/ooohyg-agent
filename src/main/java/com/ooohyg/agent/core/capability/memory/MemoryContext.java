package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.capability.search.SearchContext;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 记忆库的上下文边界：在通用 {@link SearchContext} 之上叠加记忆特有的元数据约束。
 *
 * <p>通用检索框架解决"怎么查"，本类解决"查什么"——记忆被强制分为
 * user / feedback / project / reference 四种类型，每条记忆以带 YAML
 * frontmatter 的 Markdown 文件存储。
 */
public record MemoryContext(
        SearchContext searchContext,
        Set<String> allowedTypes,
        Map<String, String> frontmatterSchema
) {

    /** 记忆的四种强制类型。 */
    public static final Set<String> TYPES = Set.of("user", "feedback", "project", "reference");

    /**
     * 以记忆根目录创建默认记忆上下文。
     *
     * @param memoryRoot 记忆文件存放目录
     */
    public static MemoryContext of(Path memoryRoot) {
        return new MemoryContext(
                SearchContext.forMemory(memoryRoot),
                TYPES,
                Map.of("name", "标识", "description", "一句话描述", "type", "四种类型之一")
        );
    }
}
