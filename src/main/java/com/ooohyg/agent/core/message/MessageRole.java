package com.ooohyg.agent.core.message;

/**
 * 消息角色。
 *
 * <p>四种角色覆盖了 Agent 执行过程中的全部消息来源：
 * <ul>
 *   <li>{@link #SYSTEM}：系统提示词，定义 Agent 的人设和行为规则；</li>
 *   <li>{@link #USER}：用户输入，本次任务的起点；</li>
 *   <li>{@link #ASSISTANT}：模型回复，可能是最终答案，也可能是工具调用请求；</li>
 *   <li>{@link #TOOL}：工具执行结果，通过 {@code toolCallId} 和 ASSISTANT
 *       的 toolCalls 配对。</li>
 * </ul>
 *
 * <p><b>为什么只有四种角色：</b>
 * <ul>
 *   <li>这四种是主流 LLM API（OpenAI、Anthropic、通义千问、Ollama）
 *       都支持的；</li>
 *   <li><b>RAG 检索结果不作为一种 role</b>——它应该拼接到 SYSTEM 或 USER
 *       消息的 content 中，作为当轮临时注入，而不是永久进入对话历史。
 *       理由：RAG 结果的语义作用域是"当前这次推理"，把它作为独立的
 *       role 会污染历史，且主流 LLM API 不识别这个 role，adapter 转换成本高；</li>
 *   <li>如果将来某个提供方有独有 role（如 OpenAI 的 {@code developer}），
 *       在 adapter 层做映射，不污染 core。</li>
 * </ul>
 */
public enum MessageRole {

    /**
     * 系统提示词。通常位于消息列表开头，定义 Agent 的角色、能力边界
     * 和行为约束。
     */
    SYSTEM,

    /**
     * 用户输入。本次任务的起点，也是用户后续对话的载体。
     */
    USER,

    /**
     * 模型回复。可能是：
     * <ul>
     *   <li>纯文本回答（{@code content} 非空，{@code toolCalls} 为 null）；</li>
     *   <li>工具调用请求（{@code toolCalls} 非空，{@code content} 可为空）；</li>
     *   <li>两者兼有（模型一边解释思路，一边请求调用工具）。</li>
     * </ul>
     */
    ASSISTANT,

    /**
     * 工具执行结果。每个 TOOL 消息通过 {@code toolCallId} 关联到
     * 之前某条 ASSISTANT 消息中的某个 ToolCall。
     */
    TOOL
}