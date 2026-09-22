package com.ooohyg.agent.core.exception;

/**
 * Agent 执行的技术异常基类。
 *
 * <p><b>它只承载"技术故障"，不承载"业务失败"。</b>
 * 业务失败（达到最大步数、模型判定无解、用户输入不合法）应通过
 * {@code AgentResult.failure(...)} 返回，不走异常路径。
 *
 * <p>继承 {@link RuntimeException} 而非 {@link Exception}，理由：
 * <ul>
 *   <li>避免污染所有方法签名（core 层接口都是同步方法，不希望处处 throws）；</li>
 *   <li>技术故障本质上不可恢复，调用方若需要可以捕获，不需要也不影响；</li>
 *   <li>与 Spring、主流框架的异常设计一致。</li>
 * </ul>
 *
 * <p>子类分工（后续在策略实现时按需添加）：
 * <ul>
 *   <li>{@code ModelCallException}：模型调用失败（网络、限流、格式错误）；</li>
 *   <li>{@code ToolExecutionException}：工具调用失败；</li>
 *   <li>{@code SandboxException}：沙箱执行失败。</li>
 * </ul>
 *
 * <p>本批只提供基类。子类在没有具体抛出点之前不建，避免"没有调用方的空类"。
 */
public class AgentException extends RuntimeException {

    /**
     * 用消息构造。
     *
     * @param message 异常描述
     */
    public AgentException(String message) {
        super(message);
    }

    /**
     * 用消息和原因构造。
     *
     * @param message 异常描述
     * @param cause   原始异常（例如底层的 {@code IOException}）
     */
    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}