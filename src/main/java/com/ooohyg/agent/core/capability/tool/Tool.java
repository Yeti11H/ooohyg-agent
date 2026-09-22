package com.ooohyg.agent.core.capability.tool;

/**
 * 一个可被模型调用的工具。
 *
 * <p>Tool 是"能执行的工具本体"，{@link ToolDefinition} 是"给模型看的描述"，
 * 二者分开的原因：
 * <ul>
 *   <li>描述要发给模型，本体只在本地执行——职责不同；</li>
 *   <li>同一个工具可以注册到多个策略，description 由使用方按场景自定义
 *       （例如同一个 HTTP 工具，在"查天气"场景下描述和在"调 API"场景下
 *       描述可以不同）；</li>
 *   <li>模型只认识 name / description / parametersSchema，不需要知道
 *       实现语言、依赖、线程安全性等。</li>
 * </ul>
 *
 * <p><b>为什么本接口不暴露 name()：</b>
 * <ul>
 *   <li>名字的唯一权威来源是 {@link #definition()}，多一个入口就多一个
 *       不一致的可能；</li>
 *   <li>{@link ToolRegistry} 建索引时从 {@code definition().name()} 缓存，
 *       调用方不需要从 Tool 上再读一次。</li>
 * </ul>
 *
 * <p><b>execute 的错误契约：</b>
 * <ul>
 *   <li>业务失败（参数不合法、资源不存在、业务规则不满足）
 *       → 返回 {@link ToolResult#failure(String)}。
 *       这类失败会转成 TOOL 消息喂回模型，让它修正；</li>
 *   <li>技术故障（底层 SDK 崩溃、依赖服务 5xx、工具内部 NPE）
 *       → 抛 {@link com.ooohyg.agent.core.exception.AgentException}。
 *       这是"模型无论怎么改参数都解决不了"的问题；</li>
 *   <li>判据：模型换参数能不能解决？能 → 业务失败；不能 → 技术故障。</li>
 * </ul>
 *
 * <p><b>arguments 是 String 而不是 Map：</b>
 * <ul>
 *   <li>与 {@link com.ooohyg.agent.core.message.ToolCall#arguments()} 的
 *       表示保持一致——core 不引入 JSON 解析依赖；</li>
 *   <li>工具实现自己解析 JSON。解析失败时<b>返回
 *       {@link ToolResult#failure(String)}</b>，不抛异常——因为模型
 *       输出畸形 JSON 属于模型能通过重试修正的问题。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程环境下调用；</li>
 *   <li>{@code execute} 不得修改 {@link #definition()} 返回的对象；</li>
 *   <li>{@code execute} 不得有隐藏副作用（例如写全局静态变量）。</li>
 * </ul>
 */
public interface Tool {

    /**
     * 本工具的对外描述。
     *
     * <p>调用方必须能依赖 {@code definition().name()} 作为唯一标识。
     * 同一个 {@link ToolRegistry} 中不允许出现两个 name 相同的 Tool。
     *
     * @return 非 null
     */
    ToolDefinition definition();

    /**
     * 执行一次工具调用。
     *
     * <p>入参是本工具 {@link ToolDefinition#parametersSchema()} 所描述的
     * JSON 实例，以 String 形式传入。
     *
     * @param arguments 模型返回的原始 JSON 字符串，非 null。允许空串，
     *                  表示"无参数调用"。
     * @return 执行结果，永不为 null
     * @throws com.ooohyg.agent.core.exception.AgentException 技术故障
     */
    ToolResult execute(String arguments);
}