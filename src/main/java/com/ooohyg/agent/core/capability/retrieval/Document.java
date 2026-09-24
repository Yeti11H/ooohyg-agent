package com.ooohyg.agent.core.capability.retrieval;

import java.util.Map;
import java.util.Objects;

/**
 * 一段可被检索的文本及其元数据。
 *
 * <p>Document 是 RAG 检索系统的最小数据单元。它有两个使用场景：
 * <ul>
 *   <li><b>写入时</b>：用户把业务文本切成若干 Document 灌入
 *       {@link VectorStore}，此时每个 Document 的内容是静态的；</li>
 *   <li><b>检索时</b>：{@link VectorStore#search} 返回相关的 Document，
 *       其 content 会被拼入 prompt，供模型引用。</li>
 * </ul>
 *
 * <p><b>为什么 id 必须由用户提供，框架不自动生成：</b>
 * <ul>
 *   <li>id 是“业务视角下的身份”——比如文档 URL、数据库主键、
 *       内容 hash。框架不知道也不该猜这个身份；</li>
 *   <li>去重、更新、删除都依赖 id。用户提供 id 才能保证同一份内容
 *       重复灌入时不会变成两条；</li>
 *   <li>如果框架自动生成，用户无法用业务 id 反查 Document，
 *       检索结果的来源追踪会断掉。</li>
 * </ul>
 *
 * <p><b>为什么 metadata 是 Map&lt;String, String&gt; 而不是 Map&lt;String, Object&gt;：</b>
 * <ul>
 *   <li>{@code Object} 会引入序列化 / 类型判断 / 反序列化的复杂度，
 *       而这些复杂度在第一版没有真实调用方需要；</li>
 *   <li>String 足够覆盖常见元数据：来源 URL、标题、页码、
 *       时间戳、标签、章节路径；</li>
 *   <li>如果将来出现“需要存结构化元数据”的真实场景
 *       （比如嵌套的 JSON），再考虑加一个可选的结构化字段，
 *       不影响现有语义。</li>
 * </ul>
 *
 * <p><b>为什么 score 不放在 Document 里：</b>
 * <ul>
 *   <li>Document 是“存储单元”，score 是“检索结果”——
 *       存储时没有 score，检索时才有；</li>
 *   <li>把 score 塞进 Document，写入阶段就得填一个假值（0 或 null），
 *       污染语义；</li>
 *   <li>score 由 {@code SearchResult} 承载（下一步交付），
 *       Document 保持纯粹。</li>
 * </ul>
 *
 * <p><b>不可变性：</b>record 天然不可变；metadata 在紧凑构造器里
 * 做 {@link Map#copyOf} 拷贝，调用方无法通过外部引用修改内部状态。
 */
public record Document(
        String id,
        String content,
        Map<String, String> metadata
) {

    /**
     * 紧凑构造器：校验字段合法性，并对 metadata 做不可变规范化。
     */
    public Document {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        // 不可变拷贝，防止外部通过原 Map 修改 Document 内部状态。
        // Map.copyOf 同时拒绝 null key/value——若用户传了 null，
        // 在这里直接失败，好过在检索时才发现。
        metadata = Map.copyOf(metadata);
    }

    /**
     * 便捷工厂：创建无元数据的 Document。
     *
     * <p>适用场景：快速测试、或业务上确实不需要元数据。
     * 生产环境推荐带 metadata，检索结果的可追溯性依赖它。
     */
    public static Document of(String id, String content) {
        return new Document(id, content, Map.of());
    }
}