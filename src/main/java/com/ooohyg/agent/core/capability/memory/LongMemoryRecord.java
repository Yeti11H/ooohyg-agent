package com.ooohyg.agent.core.capability.memory;

import java.util.Map;
import java.util.Objects;

/**
 * 一条长期记忆。
 *
 * <p>MemoryRecord 是长期记忆的最小数据单元，对应 RAG 中的
 * {@link com.ooohyg.agent.core.capability.retrieval.Document Document}。
 * 它表达"记住了一件什么事"——例如"用户偏好简洁回答"、
 * "上次会话讨论了训练计划"、"用户对某种食物过敏"。
 *
 * <p><b>它和 Document 的区别：</b>
 * <ul>
 *   <li>Document 来自外部知识库，MemoryRecord 来自 Agent 自己的经历；</li>
 *   <li>两者结构一致（id + content + metadata），但语义不同——
 *       因此不共用同一个类型，避免概念混淆；</li>
 *   <li>Document 由用户预先灌入，MemoryRecord 由 Agent 在会话过程中
 *       主动写入（或由用户预先写入）。</li>
 * </ul>
 *
 * <p><b>为什么 id 必须存在：</b>
 * <ul>
 *   <li>去重依赖 id。同一条记忆被重复 remember 时，实现可据 id 覆盖；</li>
 *   <li>更新、删除操作都依赖 id 定位；</li>
 *   <li>调试时可据 id 追溯"这条记忆是什么时候、从哪条对话来的"。</li>
 * </ul>
 * id 的生成方式由实现决定——可以是时间戳 + 随机、UUID、
 * 或用户提供的业务键。
 *
 * <p><b>为什么 metadata 是 Map&lt;String, String&gt;：</b>
 * 与 {@link com.ooohyg.agent.core.capability.retrieval.Document Document}
 * 的 metadata 保持一致。常见用途：
 * <ul>
 *   <li>{@code "createdAt"}：记忆写入时间；</li>
 *   <li>{@code "source"}：来源（"user-stated"、"inferred"、"tool-output"）；</li>
 *   <li>{@code "sessionId"}：从哪次会话产生的；</li>
 *   <li>{@code "category"}：记忆分类（"preference"、"fact"、"experience"）。</li>
 * </ul>
 * 用 String 而非 Object，理由与 Document 相同：第一版不需要结构化元数据。
 *
 * <p><b>为什么不包含 score：</b>
 * <ul>
 *   <li>score 是"检索结果"的属性，不是"记忆本身"的属性；</li>
 *   <li>同一条记忆在不同 query 下的 score 不同，把它绑进记录里
 *       每次 recall 都要重新构造 MemoryRecord，浪费且语义混乱；</li>
 *   <li>检索结果的 score 由
 *       {@link MemorySearchResult} 承载——与 RAG 的
 *       {@link com.ooohyg.agent.core.capability.retrieval.SearchResult
 *       SearchResult} 对称。</li>
 * </ul>
 *
 * <p><b>为什么不包含时间戳字段：</b>
 * <ul>
 *   <li>不是所有记忆实现都关心时间——有的按语义检索，不按时间排序；</li>
 *   <li>时间戳是 metadata 的一种，放 metadata 里足够；
 *       框架不硬编码"记忆必有时间"这个假设；</li>
 *   <li>如果将来时间戳成为强需求（如"只回忆最近 7 天"），
 *       那时再考虑独立字段——有真实调用方才加。</li>
 * </ul>
 *
 * <p><b>不可变性：</b>record 天然不可变；metadata 在紧凑构造器里
 * 做 {@link Map#copyOf} 拷贝，调用方无法通过外部引用修改内部状态。
 */
public record LongMemoryRecord(
        String id,
        String content,
        Map<String, String> metadata
) {

    /**
     * 紧凑构造器：校验字段合法性，并对 metadata 做不可变规范化。
     */
    public LongMemoryRecord {
        Objects.requireNonNull(id, "id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        metadata = Map.copyOf(metadata);
    }

    /**
     * 便捷工厂：创建无元数据的记忆。
     *
     * <p>适用场景：快速测试、或记忆本身不携带元数据。
     * 生产环境推荐带 metadata，至少记录写入时间。
     */
    public static LongMemoryRecord of(String id, String content) {
        return new LongMemoryRecord(id, content, Map.of());
    }
}