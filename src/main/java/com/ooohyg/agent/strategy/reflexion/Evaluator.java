package com.ooohyg.agent.strategy.reflexion;

import com.ooohyg.agent.core.result.AgentResult;

/**
 * 评估器。判断子策略的输出是否满足任务要求。
 *
 * <p>Evaluator 是 Reflexion 策略的核心可变点。Reflexion 本身的循环逻辑
 * （评估—修正—重跑）是固定的，但"什么叫合格"因任务而异：
 * <ul>
 *   <li>写代码：能否编译、能否通过测试；</li>
 *   <li>写文案：是否符合品牌调性、字数是否达标；</li>
 *   <li>做数学题：答案是否正确；</li>
 *   <li>通用场景：由一个 LLM 判断"任务是否已被解答"。</li>
 * </ul>
 * 这些标准不可能用一段写死的 prompt 覆盖。把它们抽成接口，让使用方
 * 按场景提供实现——这是 Reflexion 相对其他策略最需要插拔的地方。
 *
 * <p><b>为什么不把评估逻辑直接写在 ReflexionStrategy 里：</b>
 * <ul>
 *   <li>评估标准多种多样，硬编码一种等于放弃其他所有场景；</li>
 *   <li>测试时可以注入 mock Evaluator，让 Reflexion 的循环逻辑
 *       被独立验证，不必每次都跑真实模型评估；</li>
 *   <li>遵循项目的依赖倒置惯例：策略依赖接口，具体实现由使用方提供。</li>
 * </ul>
 *
 * <p><b>为什么入参是 (originalTask, candidateResult)，不是 AgentContext：</b>
 * <ul>
 *   <li>评估只关心两件事："任务是什么"和"结果是什么"。ExecutionId、
 *       maxSteps 这些上下文属性对评估没有意义；</li>
 *   <li>给少一点，实现就好写一点。传 ctx 只会让实现多一个"能访问但
 *       用不上"的入口，是诱惑也是噪音；</li>
 *   <li>如果将来某个评估器确实需要额外上下文（如租户 id），
 *       到那时再扩签名——有真实需求才改。</li>
 * </ul>
 *
 * <p><b>为什么 candidateResult 是 AgentResult 而不是 String：</b>
 * <ul>
 *   <li>子策略可能返回失败（success=false），评估器需要知道这一点。
 *       传 String 会丢失"失败 / failureMessage"信息；</li>
 *   <li>AgentResult 已经携带 success / output / failureMessage，
 *       评估器可以按需读取，不必再造一个中间类型。</li>
 * </ul>
 *
 * <p><b>实现建议：</b>
 * <ul>
 *   <li><b>LLM 评估器</b>：把 originalTask 和 candidateResult.output()
 *       拼成 prompt，调一次模型，让它返回"通过"或"修正建议"。
 *       LLM 评估器放在 adapter 层，因为它依赖具体模型；</li>
 *   <li><b>规则评估器</b>：直接检查 output 是否满足某条件（如长度、
 *       关键字、正则）。纯 Java，可以放 core；</li>
 *   <li><b>组合评估器</b>：多个评估器按 AND / OR 组合。
 *       等有真实场景再加，不要提前抽象。</li>
 * </ul>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——Reflexion 可能在多线程下调用同一个 Evaluator；</li>
 *   <li>实现不得有副作用——评估应当是可重复的纯判断，相同输入
 *       给出相同结论；</li>
 *   <li>实现不得抛技术异常以外的东西。"评估过程本身故障"（如 LLM
 *       评估器网络超时）应抛
 *       {@link com.ooohyg.agent.core.exception.AgentException}，
 *       由 ReflexionStrategy 向上传播到 BaseAgent，
 *       转 {@code ReflexionState.FAILED} 和 {@code AgentState.ERROR}；</li>
 *   <li>"判定不通过"是正常业务结论，走 {@link EvaluationResult#reject(String)}，
 *       不走异常。</li>
 * </ul>
 */
public interface Evaluator {

    /**
     * 评估一次子策略的执行结果。
     *
     * <p>返回 {@link EvaluationResult#pass()} 表示任务已满足，Reflexion
     * 可以终止；返回 {@link EvaluationResult#reject(String)} 表示需要修正，
     * 返回值里的 feedback 会被注入下一轮子策略的输入。
     *
     * @param originalTask    原始任务（即 {@code ctx.userInput()}），
     *                        非 null、非空白。评估器据此判断"是否达标"，
     *                        而不是仅凭 output 判断
     * @param candidateResult 子策略本次执行的返回值，非 null。
     *                        可能 success=true 也可能 success=false
     * @return 评估结论，永不为 null
     * @throws com.ooohyg.agent.core.exception.AgentException
     *         评估过程本身的技术故障（如调用评估模型失败）
     */
    com.ooohyg.agent.strategy.reflexion.EvaluationResult evaluate(String originalTask, AgentResult candidateResult);
}