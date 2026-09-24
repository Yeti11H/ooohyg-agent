package com.ooohyg.agent.core.capability.retrieval;

import com.ooohyg.agent.core.exception.AgentException;

import java.util.List;

/**
 * 嵌入客户端。把文本转换为向量。
 *
 * <p>EmbeddingClient 是 RAG 检索的基础能力。{@link VectorStore}
 * 通过它把文档和查询都转换为同维度的向量，之后在向量空间中
 * 比较相似度。
 *
 * <p><b>为什么是接口：</b>
 * <ul>
 *   <li>嵌入模型是可替换的——OpenAI text-embedding-3、BGE、
 *       通义 embedding、本地模型，各有各的接入方式；</li>
 *   <li>core 不能依赖任何模型 SDK，所以这里只能定义契约；</li>
 *   <li>测试时可以用确定性 mock（比如按文本 hash 生成假向量），
 *       不需要真实网络调用。</li>
 * </ul>
 *
 * <p><b>为什么返回 List&lt;Float&gt; 而不是 float[]：</b>
 * <ul>
 *   <li>不同嵌入模型的维度不同（1536、1024、768、384……），
 *       框架不规定维度；List 天然表达"长度由实现决定"，
 *       而数组在 API 契约里暗示"长度固定"；</li>
 *   <li>List 是不可变集合的良好载体，便于在 record / 值对象中
 *       安全传递；</li>
 *   <li>float[] 的可变性和 equals/hashCode 语义在跨模块传递时
 *       容易踩坑，而 RAG 的向量比较不要求极致性能——
 *       真正的开销在网络调用和相似度计算，不在装箱。</li>
 * </ul>
 *
 * <p><b>为什么不规定维度：</b>
 * <ul>
 *   <li>维度是嵌入模型的属性，不是框架的属性；</li>
 *   <li>框架若规定"必须 1536 维"，等于锁死只能用 OpenAI
 *       某个模型，直接违反可插拔目标；</li>
 *   <li>维度一致性由 {@link VectorStore} 保证——它要求
 *       写入的向量和查询的向量维度相同，否则搜索没有意义。
 *       不一致时由 VectorStore 实现抛异常。</li>
 * </ul>
 *
 * <p><b>批量失败语义（关键决策）：</b>
 * <b>全成功或整体失败。</b>任一文本嵌入失败，整个
 * {@link #embedBatch} 调用抛 {@link AgentException}，
 * 不返回部分结果。
 * <ul>
 *   <li>嵌入数据的完整性是后续所有操作的前提。一条缺失会导致
 *       文档库和向量库索引错位，检索结果对不上号；</li>
 *   <li>部分成功比彻底失败更危险——彻底失败立刻可见，
 *       部分成功可能悄悄污染向量库，几天后才发现；</li>
 *   <li>批量重试成本可控。整批失败就整批重试，不需要
 *       "记录哪些成功哪些失败再对齐"的复杂逻辑。</li>
 * </ul>
 * 拒绝返回 {@code List<Optional<...>>} 或"部分结果 + 失败计数"——
 * 那些"更灵活"的返回会强迫每个调用方都写一遍对齐逻辑，
 * 而正确对齐的比例远低于出错的比例。
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程下调用同一个 client；</li>
 *   <li>同一个文本，两次调用必须返回相同向量（除非底层模型本身
 *       有随机性——那时实现需要文档说明）；</li>
 *   <li>实现不得缓存跨调用的状态到实例字段——除非缓存本身是
 *       设计的一部分，且线程安全（如 Caffeine）；</li>
 *   <li>网络故障、限流、响应格式非法 → 抛 {@link AgentException}。</li>
 * </ul>
 */
public interface EmbeddingClient {

    /**
     * 把单个文本转换为向量。
     *
     * <p>检索时最常用的方法：把用户 query 嵌入为向量，再交给
     * {@link VectorStore#search} 做相似度搜索。
     *
     * @param text 待嵌入文本，非 null、非空白
     * @return 嵌入向量，非 null、非空。维度由实现决定
     * @throws IllegalArgumentException text 为 null 或空白
     * @throws AgentException           嵌入调用技术故障
     */
    List<Float> embed(String text);

    /**
     * 批量把多个文本转换为向量。
     *
     * <p>灌入文档时使用：用户可能有几千条 {@link Document} 要入
     * {@link VectorStore}，逐个调用 {@link #embed} 会付出
     * 几千次网络往返。批量调用允许底层 adapter 利用嵌入服务的
     * 批量能力（OpenAI / BGE / 通义等服务端都支持多输入）。
     *
     * <p><b>返回顺序与输入严格一致。</b>{@code result.get(i)} 是
     * {@code texts.get(i)} 的嵌入，调用方不需要自己做索引对齐。
     *
     * <p><b>失败语义：全成功或整体失败。</b>任一文本嵌入失败，
     * 本方法抛 {@link AgentException}，不返回部分结果。
     * 见类级 Javadoc 的详细说明。
     *
     * @param texts 待嵌入文本列表，非 null、非空，元素非 null 非空白
     * @return 与输入一一对应的向量列表，非 null、长度等于输入长度
     * @throws IllegalArgumentException texts 为 null、空，或含 null/空白元素
     * @throws AgentException           嵌入调用技术故障
     */
    List<List<Float>> embedBatch(List<String> texts);
}