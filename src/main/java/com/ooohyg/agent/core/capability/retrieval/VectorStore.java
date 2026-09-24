package com.ooohyg.agent.core.capability.retrieval;

import java.util.List;

/**
 * 向量存储。保存文档向量，并支持按相似度检索。
 *
 * <p>VectorStore 是 RAG 的存储层。它接收"文档 + 对应向量"的配对，
 * 之后用查询向量做相似度搜索，返回带得分的命中结果。
 *
 * <p><b>为什么 add 的参数是两个平行列表，而不是一个包装类型：</b>
 * <ul>
 *   <li>Document 由用户构造、向量由 {@link EmbeddingClient} 产出，
 *       两者来源不同。把它们绑成一个新类型，只是增加一层"为了传参
 *       而造壳"的间接性；</li>
 *   <li>框架不为"防调用方传错"额外造类型——长度不一致时由实现
 *       直接抛 {@link IllegalArgumentException}，错误立刻暴露；</li>
 *   <li>调用方按索引对齐（{@code documents.get(i)} 对应
 *       {@code vectors.get(i)}）已经是最自然的契约，不需要额外包装。</li>
 * </ul>
 *
 * <p><b>为什么是接口而不是直接给实现：</b>
 * <ul>
 *   <li>真实向量库是可替换的——pgvector、Milvus、Elasticsearch、
 *       Redis、内存实现，各有各的接入方式；</li>
 *   <li>core 不能依赖任何一个，只能定义契约；</li>
 *   <li>测试和快速验证用一个内存实现就能跑通，不需要起数据库。</li>
 * </ul>
 *
 * <p><b>为什么不提供 metadata 过滤：</b>
 * <ul>
 *   <li>过滤能力是向量库的实现细节——pgvector 用 SQL WHERE、
 *       Milvus 用 expressions、Elasticsearch 用 query DSL，
 *       抽象不出统一形式；</li>
 *   <li>强行抽象一个通用的过滤表达式，会限制每个库的原生能力；</li>
 *   <li>常见的"按 metadata 过滤"可以在用户自己的 Retriever 里做：
 *       多取一些候选，再按 metadata 筛。这对大多数场景够用。</li>
 * </ul>
 *
 * <p><b>为什么不提供 delete / update：</b>
 * <ul>
 *   <li>第一版没有真实调用方——框架目前只做"灌入 + 检索"，
 *       不做知识库的运行时维护；</li>
 *   <li>知识库更新通常走"离线重跑整批 + 替换索引"的流程，
 *       不在运行时逐条删除；</li>
 *   <li>等出现真实需求（例如"用户删除文档要同步移出索引"），
 *       再加 delete，那时有明确语义。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程下读写同一个 store；</li>
 *   <li>实现必须保证同一 store 内所有向量维度一致。不一致时
 *       由实现抛异常，不能静默接受；</li>
 *   <li>{@link #search} 返回的列表必须按 score 降序排列
 *       （"越大越相似"，与 {@link SearchResult} 的约定一致）；</li>
 *   <li>实现不得修改传入的 documents 或 vectors 列表内容——
 *       需要缓存时应做拷贝。</li>
 * </ul>
 */
public interface VectorStore {

    /**
     * 写入一批文档及其对应向量。
     *
     * <p>两个列表按索引一一对应：{@code documents.get(i)} 的向量是
     * {@code vectors.get(i)}。调用方必须保证长度一致——长度不一致
     * 时实现直接抛异常，不做"取较短长度"之类的自动截断。
     *
     * <p>允许重复调用。若写入的 Document {@code id} 已存在，
     * 由实现决定语义（覆盖或报错）——框架不强制。第一版推荐
     * 实现采用"覆盖"语义，让用户能通过重复灌入更新内容。
     *
     * @param documents 待写入的文档，非 null、非空，元素非 null
     * @param vectors   与 documents 一一对应的向量，非 null、非空，
     *                  元素非 null 非空，长度必须等于 documents 长度
     * @throws IllegalArgumentException 两个列表长度不等，或任一为空
     * @throws NullPointerException     任一参数为 null，或含 null 元素
     */
    void add(List<Document> documents, List<List<Float>> vectors);

    /**
     * 按相似度检索最相似的 topK 个文档。
     *
     * <p>返回列表按 score 降序排列，{@code result.get(0)} 是最相似的。
     * 实际返回数量可能小于 topK——当库中内容不足 topK 条时。
     *
     * @param queryVector 查询向量，非 null、非空
     * @param topK        期望返回的最大数量，必须为正整数
     * @return 按相似度降序排列的结果列表，可能为空，永不为 null
     * @throws IllegalArgumentException topK 非正，或 queryVector 为空
     * @throws NullPointerException     queryVector 为 null
     */
    List<SearchResult> search(List<Float> queryVector, int topK);
}