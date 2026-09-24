package com.ooohyg.agent.core.capability.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link VectorStore} 的内存实现：用余弦相似度做暴力检索。
 *
 * <p>本类的定位与 {@link com.ooohyg.agent.core.capability.tool.builtin.EchoTool
 * EchoTool} 相同——<b>让用户能跑通一次完整的 RAG 流程，而不需要任何
 * 外部依赖</b>。它不追求性能、不支持持久化、不适合生产，但它是
 * 验证接口设计成立的最小可运行部件。
 *
 * <p><b>为什么放在 core 而不是 adapter：</b>
 * <ul>
 *   <li>它只依赖 JDK 标准库（{@link ConcurrentHashMap}、{@link Math}），
 *       不引入任何外部系统；</li>
 *   <li>它是"接口设计的真实跑通证明"——没有它，VectorStore 接口
 *       就没被任何实现验证过，设计缺陷可能在用户写 pgvector 适配时
 *       才暴露；</li>
 *   <li>用户可以拿它做单元测试，不需要起数据库。</li>
 * </ul>
 *
 * <p><b>检索方式：暴力线性扫描。</b>
 * <ul>
 *   <li>每次 search 遍历所有向量，逐个算余弦相似度，排序取 topK；</li>
 *   <li>时间复杂度 O(N * D)——N 是文档数、D 是维度。
 *       在几千条文档、几百维向量下几十毫秒级，测试够用；</li>
 *   <li>不做 HNSW、IVF 之类的近似最近邻索引——那是真实向量库
 *       的职责，在内存实现里做等于重新造一个向量库。</li>
 * </ul>
 *
 * <p><b>线程安全策略：</b>
 * <ul>
 *   <li>存储用 {@link ConcurrentHashMap}，读并发无锁；</li>
 *   <li>{@link #add} 用 {@code synchronized} 串行化——它需要做
 *       "维度一致性检查 + 首次设置维度"这种复合操作，用锁保护最简单；</li>
 *   <li>{@link #search} 不加锁——它是纯读操作，ConcurrentHashMap
 *       的弱一致性迭代对检索场景足够（读到 add 中途的状态不影响正确性，
 *       只是可能漏掉刚写入的那几条）。</li>
 * </ul>
 *
 * <p><b>同 id 覆盖语义：</b>重复 add 相同 id 的文档时，新文档覆盖旧文档。
 * 这让用户能通过"重新灌入"更新文档内容，不需要 delete-then-add。
 *
 * <p><b>零向量处理：</b>若 query 向量或文档向量为零向量（所有元素为 0），
 * 余弦相似度分母为 0。本实现将其得分定义为 0，不抛异常——
 * 零向量与任何向量都不相似，得 0 表达这个语义。真实模型不会输出零向量，
 * 这个兜底只是防御性措施。
 */
public final class InMemoryVectorStore implements VectorStore {

    /**
     * id → 条目。用 ConcurrentHashMap 保证多线程读写的安全性。
     */
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 首次 add 时确定的向量维度。
     *
     * <p>{@code volatile} 保证 search 线程能看到 add 线程写入的最新值。
     * 写操作在 add 的 synchronized 块内，读操作不加锁但能看到 volatile
     * 的可见性保证。
     *
     * <p>{@code null} 表示 store 为空，维度尚未确定。
     */
    private volatile Integer dimension;

    /**
     * 内部条目：文档 + 向量。record 保证引用不可变，
     * 向量在 add 时已经做了 List.copyOf，整体不可变。
     */
    private record Entry(Document document, List<Float> vector) {
    }

    @Override
    public synchronized void add(List<Document> documents, List<List<Float>> vectors) {
        Objects.requireNonNull(documents, "documents must not be null");
        Objects.requireNonNull(vectors, "vectors must not be null");

        if (documents.isEmpty()) {
            throw new IllegalArgumentException("documents must not be empty");
        }
        if (documents.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "documents and vectors must have the same size, got "
                            + documents.size() + " vs " + vectors.size());
        }

        for (int i = 0; i < documents.size(); i++) {
            Document doc = Objects.requireNonNull(documents.get(i),
                    "documents[" + i + "] must not be null");
            List<Float> vec = Objects.requireNonNull(vectors.get(i),
                    "vectors[" + i + "] must not be null");
            if (vec.isEmpty()) {
                throw new IllegalArgumentException(
                        "vectors[" + i + "] must not be empty");
            }

            // 维度一致性检查：首次 add 确定维度，之后必须一致。
            if (dimension == null) {
                dimension = vec.size();
            } else if (vec.size() != dimension) {
                throw new IllegalArgumentException(
                        "Vector dimension mismatch: expected " + dimension
                                + ", got " + vec.size()
                                + " at index " + i
                                + " (document id=" + doc.id() + ")");
            }

            // List.copyOf 防止外部修改传入的向量列表；
            // 同时拒绝 null 元素（copyOf 的契约）。
            store.put(doc.id(), new Entry(doc, List.copyOf(vec)));
        }
    }

    @Override
    public List<SearchResult> search(List<Float> queryVector, int topK) {
        Objects.requireNonNull(queryVector, "queryVector must not be null");
        if (queryVector.isEmpty()) {
            throw new IllegalArgumentException("queryVector must not be empty");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException(
                    "topK must be positive, got: " + topK);
        }

        // 维度校验：库非空时，query 维度必须与库中维度一致。
        // 库为空时无参照，跳过校验，直接返回空结果。
        Integer currentDimension = this.dimension;
        if (currentDimension == null) {
            return List.of();
        }
        if (queryVector.size() != currentDimension) {
            throw new IllegalArgumentException(
                    "Query vector dimension mismatch: store has "
                            + currentDimension + " dimensions, got "
                            + queryVector.size());
        }

        // 暴力扫描：对每个条目算余弦相似度。
        List<SearchResult> all = new ArrayList<>(store.size());
        for (Entry entry : store.values()) {
            double score = cosineSimilarity(queryVector, entry.vector());
            all.add(new SearchResult(entry.document(), score));
        }

        // 按 score 降序排列。score 相同时顺序不保证——
        // 框架不规定 secondary 排序，调用方不应依赖该顺序。
        all.sort(Comparator.comparingDouble(SearchResult::score).reversed());

        // 取 topK，避免 subList 的视图语义，做真正的拷贝。
        int n = Math.min(topK, all.size());
        return List.copyOf(all.subList(0, n));
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * <p>公式：{@code dot(a, b) / (||a|| * ||b||)}，结果在 [-1, 1]。
     * 与 {@link SearchResult} 的"越大越相似"约定一致。
     *
     * <p>累加使用 {@code double}——float 在 768 次乘加下会累积舍入
     * 误差。这与"向量元素用 float（模型原生格式），相似度用 double
     * （计算结果）"的类型选择一致。
     *
     * <p>零向量返回 0，不抛异常——见类级 Javadoc。
     */
    private static double cosineSimilarity(List<Float> a, List<Float> b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.size(); i++) {
            float ai = a.get(i);
            float bi = b.get(i);
            dot += (double) ai * bi;
            normA += (double) ai * ai;
            normB += (double) bi * bi;
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        if (denom == 0.0) {
            return 0.0;
        }
        return dot / denom;
    }
}