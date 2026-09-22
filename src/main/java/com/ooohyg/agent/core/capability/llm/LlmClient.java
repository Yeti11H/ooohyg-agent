package com.ooohyg.agent.core.capability.llm;

import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.message.Message;

import java.util.List;

/**
 * LLM 客户端。
 *
 * <p>这是策略与模型之间的唯一契约。ReAct、Plan-and-Execute、Reflexion
 * 三种策略都通过本接口调用模型，<b>不直接依赖任何具体厂商 SDK</b>。
 * 具体实现（OpenAI、Anthropic、通义千问、Ollama、本地 mock）
 * 放在 adapter 模块，由依赖注入提供。
 *
 * <p><b>为什么是接口而不是抽象类：</b>
 * <ul>
 *   <li>core 层只定义契约，不提供实现——这是硬约束；</li>
 *   <li>测试时可以用 mock 替换，不需要真实网络调用；</li>
 *   <li>策略只依赖本接口，替换模型提供方不改策略代码。</li>
 * </ul>
 *
 * <p><b>为什么方法直接列参数，不封装 ChatRequest：</b>
 * <ul>
 *   <li>目前只有两个参数，封装成对象只增加一层“为了将来可能加字段”
 *       的间接性，违反“不为假想需求设计”；</li>
 *   <li>等出现第三个参数（如 temperature、stop、responseFormat）
 *       再考虑封装，那时的封装有真实依据；</li>
 *   <li>直接列参数让方法签名即文档，读代码时不用跳进 ChatRequest
 *       看有什么字段。</li>
 * </ul>
 *
 * <p><b>为什么 tools 可空：</b>
 * <ul>
 *   <li>有些场景下策略不需要工具——例如纯文本总结、最终回答阶段；</li>
 *   <li>{@code null} 和空 list 表达同一语义“无工具可用”，
 *       本接口约定两者都是合法输入，实现不得因此抛异常；</li>
 *   <li>调用方不必为了“传空”特意构造一个空 list，减少噪音。</li>
 * </ul>
 *
 * <p><b>messages 的约束：</b>
 * <ul>
 *   <li>必须非 null、非空、至少 1 条；</li>
 *   <li>调用方传入后不应再修改该 list；实现也不应缓存它——每次调用
 *       的入参都是当次快照，不能放进实例字段；</li>
 *   <li>违反约束时抛 {@link IllegalArgumentException}——这属于调用方
 *       误用，不是执行中的技术故障。</li>
 * </ul>
 *
 * <p><b>错误契约：</b>
 * <ul>
 *   <li>技术故障（网络超时、5xx、限流、响应格式非法）
 *       → 抛 {@link AgentException} 或其子类。
 *       子类（如 {@code ModelCallException}）留到可靠性模块再建；</li>
 *   <li>模型返回的业务层信息（拒绝回答、上下文超长、无匹配工具）
 *       → <b>不抛异常</b>，通过 {@link LlmResponse} 返回，
 *       由策略判断如何处理。策略若判定“无法继续”，
 *       返回 {@code AgentResult.failure(...)}。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能从多个线程调用同一个 client；</li>
 *   <li>实现不得持有每次调用的状态（messages、tools 等入参不能缓存在实例字段）；</li>
 *   <li>实现不得吞掉异常——技术故障必须向上抛，
 *       让 {@code BaseAgent} 能转 ERROR 状态并记录日志。</li>
 * </ul>
 */
public interface LlmClient {

    /**
     * 发起一次模型对话。
     *
     * <p>方法返回时，模型已经响应完毕。无论是纯文本回答还是工具调用请求，
     * 都通过返回的 {@link LlmResponse} 表达，策略用
     * {@link LlmResponse#hasToolCalls()} 决定下一步。
     *
     * <p><b>调用示例（ReAct 的 THINKING 阶段）：</b>
     * <pre>{@code
     *   LlmResponse response = llmClient.chat(messages, toolDefinitions);
     *   messages.add(Message.assistantWithToolCalls(response.content(), response.toolCalls()));
     *   if (response.hasToolCalls()) {
     *       // 进 ACTING
     *   } else {
     *       // 进 COMPLETED
     *   }
     * }</pre>
     *
     * @param messages 对话历史，非 null、非空。顺序即对话顺序，
     *                 通常第一条是 SYSTEM，最后一条是 USER 或 TOOL。
     * @param tools    当前可用的工具描述列表。允许为 {@code null} 或空，
     *                 表示本轮不提供工具。非空时元素不得为 null。
     * @return 模型返回，永不为 null
     * @throws IllegalArgumentException {@code messages} 为 null 或空
     * @throws AgentException           模型调用技术故障（网络、限流、格式非法）
     */
    LlmResponse chat(List<Message> messages, List<ToolDefinition> tools);
}