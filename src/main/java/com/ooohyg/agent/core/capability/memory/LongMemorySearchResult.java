package com.ooohyg.agent.core.capability.memory;

import java.util.Objects;

/**
 * 一次长期记忆检索的结果：命中的记忆 + 相关度得分。
 *
 * <p>本类对应 RAG 中的
 * {@link com.ooohyg.agent.core.capability.retrieval.SearchResult SearchResult}，
 * 只是承载的单元从 {@code Document} 换成了 {@link MemoryRecord}。
 * 两个类结构对称，语义不同：
 * <ul>
 *   <li>{@code SearchResult} —— "检索到哪段外部知识"；</li>
 *   <li>{@code MemorySearchResult} —— "回忆起哪条过往经历"。</li>
 * </ul>
 *
 * <p><b>为什么 score 独立于 MemoryRecord：</b>
 * <ul>
 *   <li>MemoryRecord 是"存储单元"——写入时没有 score，
 *       score 也不该被持久化；</li>
 *   <li>同一条记忆在不同 query 下的 score 不同，
 *       把 score 绑进 MemoryRecord 会让每次 recall 都要
 *       重新构造记录，语义混乱；</li>
 *   <li>score 只在这一刻有意义，由本类承载最合适。</li>
 * </ul>
 *
 * <p><b>为什么 score 用 double：</b>
 * 与 {@code SearchResult} 一致。score 是"计算后的标量"，
 * 累加过程用 double 保留精度；向量元素才是 float
 * （模型输出的原始格式）。这个类型选择在
 * {@link com.ooohyg.agent.core.capability.retrieval.SearchResult
 * SearchResult} 的 Javadoc 里有详细说明。
 *
 * <p><b>score 语义约定（与 SearchResult 一致）：</b>
 * <ul>
 *   <li><b>越大越相关</b>是推荐方向。余弦相似度天然满足；
 *       欧氏距离实现应取负值或倒数，统一到"越大越相关"；</li>
 *   <li>框架不强制校验 score 的取值范围——任意 double 合法；</li>
 *   <li>是否需要阈值过滤由调用方决定。长期记忆检索通常
 *       在 Tool 包装器里做——低于阈值的记忆不返回给模型，
 *       避免噪声污染上下文。</li>
 * </ul>
 *
 * <p><b>为什么长期记忆需要 score，而不是像短期记忆那样简单返回列表：</b>
 * <ul>
 *   <li>长期记忆的存储规模可能很大——几百上千条记忆，
 *       一次 recall 需要按相关度排序取前 K 条；</li>
 *   <li>score 让调用方（通常是被包成 Tool 的召回逻辑）
 *       能基于相关度做过滤、融合、展示；</li>
 *   <li>与 RAG 的检索形态完全一致——这也是为什么长期记忆
 *       可以直接复用 {@code VectorStore} 基础设施。</li>
 * </ul>
 */
public record LongMemorySearchResult(
        LongMemoryRecord record,
        double score
) {

    /**
     * 紧凑构造器：只校验 record 非 null。
     *
     * <p>score 不做校验——理由与 {@code SearchResult} 相同：
     * 任意 double 都是合法值，包括负数和 0。
     */
    public LongMemorySearchResult {
        Objects.requireNonNull(record, "record must not be null");
    }
}