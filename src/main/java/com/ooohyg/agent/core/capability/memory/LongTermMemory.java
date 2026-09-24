package com.ooohyg.agent.core.capability.memory;

import com.ooohyg.agent.core.exception.AgentException;

import java.util.List;

/**
 * 长期记忆。保存跨会话的经验、事实与用户偏好，并支持按语义检索。
 *
 * <p>长期记忆与短期记忆的分工：
 * <ul>
 *   <li>{@link ShortTermMemory} 管"当前这次执行的消息列表"——
 *       自动、每次调模型前触发、不跨会话；</li>
 *   <li>{@code LongTermMemory} 管"跨越多次执行的经验积累"——
 *       由模型主动决定何时写入、何时检索。</li>
 * </ul>
 *
 * <p><b>为什么长期记忆不通过装饰器自动触发：</b>
 * <ul>
 *   <li>检索需要 query。装饰器无法判断"这次调用该用什么 query 去回忆"——
 *       可能模型正在处理工具返回，压根不需要回忆；</li>
 *   <li>"记住什么"需要语义判断——什么时候该把一段对话存进记忆？
 *       这需要模型参与，不是装饰器能决定的；</li>
 *   <li>不相关的记忆塞进上下文会干扰模型。每次自动检索等于强制
 *       模型看一堆可能无关的过往，反而降低效果。</li>
 * </ul>
 * 所以长期记忆通过 {@code Tool} 包装器接入——模型在 ReAct 循环里
 * 主动调"回忆"或"记住"工具。这与 RAG 的接入方式完全同构：
 * 外部知识检索和过往经验检索，本质是同一种动作。
 *
 * <p><b>为什么 remember 接收完整的 MemoryRecord：</b>
 * <ul>
 *   <li>id 和 metadata 是记忆的组成部分，应该由调用方决定。
 *       接口内部生成 id 会让"同一条记忆被重复写入"难以控制——
 *       调用方无法用业务键去重；</li>
 *   <li>metadata 携带语义信息（来源、分类、时间），
 *       接口无法替调用方猜；</li>
 *   <li>接口保持"接受一个完整对象，存起来"的简单形态，
 *       不掺和"生成 id / 补时间戳"这类可能因实现而异的逻辑。</li>
 * </ul>
 *
 * <p><b>为什么 recall 接收 query 字符串，而不是向量：</b>
 * <ul>
 *   <li>接口层不暴露"记忆是否用了向量检索"这个实现细节——
 *       有的实现可能用向量库，有的可能用关键词索引，
 *       有的可能用时间排序；</li>
 *   <li>String query 是所有实现都能消费的统一输入；</li>
 *   <li>与 {@link com.ooohyg.agent.core.capability.retrieval.VectorStore
 *       VectorStore} 的区别：VectorStore 是"向量存储"，接收向量；
 *       本接口是"记忆系统"，接收自然语言 query——内部怎么把
 *       query 变成检索动作，是实现的自由。</li>
 * </ul>
 *
 * <p><b>为什么不提供 delete / update / clear：</b>
 * <ul>
 *   <li>第一版没有真实调用方——框架目前只需要"记住 + 回忆"两个动作；</li>
 *   <li>更新记忆通常通过"用相同 id 重新 remember"实现（覆盖语义），
 *       不需要独立的 update 方法；</li>
 *   <li>删除记忆是隐私相关的敏感操作，等到有明确场景和合规需求时再加。</li>
 * </ul>
 *
 * <p><b>实现建议（参考方向）：</b>
 * <ul>
 *   <li><b>向量实现</b>：复用
 *       {@link com.ooohyg.agent.core.capability.retrieval.EmbeddingClient
 *       EmbeddingClient} +
 *       {@link com.ooohyg.agent.core.capability.retrieval.VectorStore
 *       VectorStore}。remember 时把 content 嵌入后存入，
 *       recall 时嵌入 query 后检索。这是最自然的实现方式——
 *       也是为什么框架把 EmbeddingClient 和 VectorStore 定义成
 *       通用能力，而不是 RAG 专用；</li>
 *   <li><b>数据库实现</b>：用 MySQL 存储，recall 时按时间倒序
 *       或按 metadata 过滤。适用于"记忆不需要语义检索"的场景；</li>
 *   <li><b>混合实现</b>：向量检索候选 + 规则过滤。用户可以
 *       按场景组合。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程下读写同一个记忆；</li>
 *   <li>{@link #remember} 遇到相同 id 时应覆盖——这让调用方
 *       能用"重新写入"更新记忆，不需要独立的 update 方法；</li>
 *   <li>{@link #recall} 返回的列表按 score 降序排列
 *       （"越大越相关"，与 {@link LongMemorySearchResult} 的约定一致）；</li>
 *   <li>调用底层模型（嵌入）失败、存储故障 → 抛
 *       {@link AgentException}。"没检索到任何相关记忆"是
 *       正常业务结果，返回空列表，不抛异常。</li>
 * </ul>
 */
public interface LongTermMemory {

    /**
     * 记住一条记忆。
     *
     * <p>若存储中已存在相同 id 的记录，覆盖旧记录。
     * 调用方可以用这个语义更新已有记忆——不需要独立的 update 方法。
     *
     * <p>本方法不返回任何内容。调用方通常不关心"记住"的结果——
     * 它是一次写入动作，成功即完成。若需要确认，用 {@link #recall}
     * 检索回来验证。
     *
     * @param record 待记住的记忆，非 null
     * @throws NullPointerException record 为 null
     * @throws AgentException       写入过程的技术故障（存储不可用、
     *                              嵌入调用失败等）
     */
    void remember(LongMemoryRecord record);

    /**
     * 按语义检索与 query 最相关的 topK 条记忆。
     *
     * <p>返回列表按 score 降序排列，{@code result.get(0)} 是最相关的。
     * 实际返回数量可能小于 topK——当记忆库中没有足够记录时。
     *
     * <p>返回空列表是正常结果：表示"没有找到相关记忆"。
     * 调用方（通常是 Tool 包装器）应把"没找到"作为正常信息
     * 返回给模型，而不是当作失败。
     *
     * @param query 查询语句，非 null、非空白。
     *              通常是模型构造的自然语言问题或关键词
     * @param topK  期望返回的最大数量，必须为正整数
     * @return 按相关度降序排列的记忆列表，可能为空，永不为 null
     * @throws IllegalArgumentException query 为空白或 topK 非正
     * @throws AgentException           检索过程的技术故障
     */
    List<LongMemorySearchResult> recall(String query, int topK);
}