package com.ooohyg.agent.core.capability.retrieval;

import java.util.Objects;

/**
 * 一次向量检索的结果：命中的文档 + 相似度得分。
 *
 * <p>SearchResult 是 {@link VectorStore#search} 的返回单元。它把
 * "命中了什么"（{@link #document}）和"有多相似"（{@link #score}）
 * 分开承载，而不是把 score 塞进 Document。
 *
 * <p><b>为什么 score 独立于 Document：</b>
 * <ul>
 *   <li>Document 是"存储单元"——写入时没有 score，score 也不该被持久化；</li>
 *   <li>SearchResult 是"检索结果"——score 只在这一刻有意义，
 *       同一条 Document 用不同 query 检索，score 不同；</li>
 *   <li>把 score 放 Document 里，写入阶段就得填假值，污染语义。</li>
 * </ul>
 *
 * <p><b>为什么 score 用 double 而不是 float：</b>
 * <ul>
 *   <li>向量相似度计算（余弦、点积）通常以 double 累加，
 *       最后才截断。用 double 保留精度，不引入无谓的转换；</li>
 *   <li>Java 标准库的数值计算、集合排序默认围绕 double 设计
 *       （{@code Math}、{@code Double.compare}）；</li>
 *   <li>float 省下的内存在这个数量级下可忽略，不值得为它引入
 *       类型转换和精度损失的讨论。</li>
 * </ul>
 *
 * <p><b>为什么 score 允许为负数：</b>
 * <ul>
 *   <li>不同实现返回的 score 语义不同：余弦相似度 ∈ [-1, 1]、
 *       内积无界、欧氏距离恒非负——但"距离越小越相似"与
 *       "分数越大越相似"方向相反；</li>
 *   <li>框架<b>不规定</b> score 的具体含义，只要求"实现自己保持一致"：
 *       同一个 VectorStore 返回的 score 必须可比较、可排序；</li>
 *   <li>是否需要阈值过滤，由用户在自己的 Retriever 里做——
 *       框架只提供数据，不做判断。</li>
 * </ul>
 *
 * <p><b>score 的语义约定（给实现者的建议）：</b>
 * <ul>
 *   <li><b>越大越相似</b>是推荐的约定。余弦相似度天然满足；
 *       欧氏距离实现应取负值或取倒数，统一到"越大越相似"；</li>
 *   <li>这样用户混合检索时不必关心每个 VectorStore 的方向，
 *       直接用 RRF 或加权融合即可；</li>
 *   <li>约定是建议不是强制——框架不校验 score 的具体数值，
 *       只要求同一实现内一致。</li>
 * </ul>
 */
public record SearchResult(
        Document document,
        double score
) {

    /**
     * 紧凑构造器：只校验 document 非 null。
     *
     * <p>score 不做校验——任意 double 都是合法值（包括负数和 0）。
     * 拒绝 NaN / Infinity 也留给实现——实现如果产生这些值，
     * 是它自身逻辑的问题，框架不必替它兜底。
     */
    public SearchResult {
        Objects.requireNonNull(document, "document must not be null");
    }
}