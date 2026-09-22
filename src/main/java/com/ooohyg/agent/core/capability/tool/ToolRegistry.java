package com.ooohyg.agent.core.capability.tool;

import java.util.List;
import java.util.Optional;

/**
 * 工具注册表。
 *
 * <p>Registry 是策略与"一组工具"之间的边界。策略不直接持有 List&lt;Tool&gt;，
 * 而是通过 Registry 做两件事：
 * <ol>
 *   <li>调模型之前，拿 {@link #definitions()} 拼 tools 参数发给模型；</li>
 *   <li>ACTING 阶段拿到模型的 {@code ToolCall}，用 {@link #find(String)}
 *       查出对应 Tool 来执行。</li>
 * </ol>
 *
 * <p><b>为什么是接口而不是 Map：</b>
 * <ul>
 *   <li>core 层只定义契约。Registry 的构造、索引方式、是否支持热加载，
 *       都是 adapter 层的事；</li>
 *   <li>测试时可以注入假的 Registry，不需要真实工具；</li>
 *   <li>策略只依赖本接口，替换 Registry 实现不改策略代码。</li>
 * </ul>
 *
 * <p><b>为什么 find 返回 Optional 而不是抛异常：</b>
 * <ul>
 *   <li>模型返回一个不存在的工具名，是 ReAct 里常见的真实情况——
 *       模型幻觉、或者工具被临时移除。这属于"模型能通过重试修正"
 *       的业务失败，不是技术故障；</li>
 *   <li>返回 Optional 把"没找到"表达为一种正常结果，策略可以把它转成
 *       {@link ToolResult#failure(String)} 喂回模型；</li>
 *   <li>如果这里抛异常，ReAct 循环会直接中断，模型失去修正机会，
 *       违背 ReAct 的本质。</li>
 * </ul>
 *
 * <p><b>刻意不提供的操作：</b>
 * <ul>
 *   <li>{@code register(Tool)}——第一版所有工具在构造 Registry 时一次性
 *       注册。等出现"运行时插件热加载"的真实需求再加；</li>
 *   <li>{@code contains(String)}——调用方用 {@code find(name).isPresent()}
 *       即可，不需要单独入口；</li>
 *   <li>{@code size()}——目前没有真实调用方；</li>
 *   <li>{@code findOrThrow(String)}——和 {@code find} 二选一即可，
 *       多一个入口就多一种处理方式，违反"不为假想需求设计"。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程环境下调用；</li>
 *   <li>实现必须保证同一个实例内 name 唯一。构造时发现重名应当直接
 *       抛 {@link IllegalArgumentException}，而不是覆盖或静默保留其中一个；</li>
 *   <li>{@link #definitions()} 返回的列表必须是不可变快照，
 *       调用方无法通过它修改 Registry 内部状态。</li>
 * </ul>
 */
public interface ToolRegistry {

    /**
     * 按名字查找工具。
     *
     * <p>名字的来源是模型返回的 {@link com.ooohyg.agent.core.message.ToolCall#name()}。
     * 匹配规则是实现细节，但必须与 {@link ToolDefinition#name()} 的大小写
     * 敏感性保持一致——推荐精确匹配。
     *
     * @param name 工具名，非 null。允许空串（结果必然是 empty），
     *             不抛异常——空串是"没找到"的一种特殊情况
     * @return 找到返回 {@code Optional.of(tool)}，否则返回
     *         {@code Optional.empty()}
     */
    Optional<Tool> find(String name);

    /**
     * 所有已注册工具的对外描述，供策略拼 tools 参数发给模型。
     *
     * <p>返回顺序即 Registry 的注册顺序，策略与 adapter 不应依赖顺序。
     *
     * @return 不可变列表，可能为空（无任何工具），永不为 null
     */
    List<ToolDefinition> definitions();
}