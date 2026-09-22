package com.ooohyg.agent.strategy.reflexion;

import java.util.Objects;

/**
 * 一次评估的结论。
 *
 * <p>Evaluator 评估完子策略的输出后，用本类表达"通过"或"不通过"。
 * ReflexionStrategy 据此决定是进 ACCEPTED 还是进 REVISING。
 *
 * <p><b>两种状态二选一</b>（由紧凑构造器强制）：
 * <ul>
 *   <li>{@code accepted = true} 时，{@code feedback} 必须为 null。
 *       通过就是通过，不需要附带给下一轮的建议；</li>
 *   <li>{@code accepted = false} 时，{@code feedback} 必须非空白。
 *       修正建议是下一轮的输入，没有它就无从修正，所以强制非空。</li>
 * </ul>
 *
 * <p><b>为什么通过时不带 feedback：</b>
 * <ul>
 *   <li>"通过但有个建议"在实践中模糊——建议如果真的重要，就不该算通过；
 *       如果不重要，就不该占用下一轮上下文；</li>
 *   <li>严格的二选一让调用方的分支判断简单：
 *       {@code if (result.accepted())} 就一定是终态，
 *       不会有"接受了但还要处理 feedback"的额外分支；</li>
 *   <li>未来若真的需要"通过兼建议"，可以新增字段（比如 notes），
 *       不影响当前语义。</li>
 * </ul>
 *
 * <p><b>feedback 的写法建议（给 Evaluator 实现者）：</b>
 * <ul>
 *   <li>它是给模型看的——写"哪里不合格、应该怎么改"，而不是"得分 60 分"；</li>
 *   <li>具体优于笼统："答案没给出具体数字"比"不够好"有用；</li>
 *   <li>不要把整个原始任务重复一遍——ReflexionStrategy 会把原始任务
 *       与 feedback 一起注入下一轮，重复浪费上下文。</li>
 * </ul>
 *
 * <p><b>为什么不复用 ToolResult / AgentResult：</b>
 * <ul>
 *   <li>语义不同：那两个表达"执行结果"，本类表达"评估结论"；</li>
 *   <li>字段不同：那两个是 output / errorMessage，本类是 feedback，
 *       用途完全不一样；</li>
 *   <li>调用方不同：本类只被 ReflexionStrategy 读取。</li>
 * </ul>
 */
public record EvaluationResult(
        boolean accepted,
        String feedback
) {

    /**
     * 紧凑构造器：强制"通过 / 不通过"的二选一约束。
     */
    public EvaluationResult {
        if (accepted) {
            if (feedback != null) {
                throw new IllegalArgumentException(
                        "Accepted evaluation must not carry feedback");
            }
        } else {
            Objects.requireNonNull(feedback,
                    "Rejected evaluation must have non-null feedback");
            if (feedback.isBlank()) {
                throw new IllegalArgumentException(
                        "Rejected evaluation feedback must not be blank");
            }
        }
    }

    /**
     * 构造一个"通过"的评估结论。
     *
     * <p>表示子策略的输出已经满足任务要求，Reflexion 循环可终止。
     */
    public static EvaluationResult pass() {
        return new EvaluationResult(true, null);
    }

    /**
     * 构造一个"未通过"的评估结论，附带修正建议。
     *
     * @param feedback 告诉下一轮"哪里不合格、应该怎么改"，
     *                 非 null、非空白
     */
    public static EvaluationResult reject(String feedback) {
        return new EvaluationResult(false, feedback);
    }
}