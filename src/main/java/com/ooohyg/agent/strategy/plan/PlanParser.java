package com.ooohyg.agent.strategy.plan;

import java.util.List;

/**
 * 计划解析器。把模型输出的原始文本解析成有序的 {@link PlanStep} 列表。
 *
 * <p>Plan-and-Execute 策略的 PLANNING 阶段，模型会返回一段"计划文本"。
 * 这段文本的格式由 PlanAndExecuteStrategy 的 prompt 约定，但 <b>core
 * 层不解析它</b>——core 不引入 JSON 库、不解析 Markdown，这是硬约束。
 * 所以解析动作抽成接口，具体实现（正则、JSON、模板匹配）
 * 由使用方按自己约定的 prompt 格式提供。
 *
 * <p><b>为什么在 strategy.plan 包下，不在 capability：</b>
 * <ul>
 *   <li>它不是一种"能力"（不访问外部系统、不产生副作用），
 *       只是 Plan 策略内部的一个辅助契约；</li>
 *   <li>其他两个策略（ReAct、Reflexion）不需要它。放进 capability
 *       会误导使用者以为它是通用能力。</li>
 * </ul>
 *
 * <p><b>解析失败为什么返回空列表，而不是抛异常：</b>
 * <ul>
 *   <li>模型输出不符合约定格式是"业务失败"——模型可以通过重试修正。
 *       按框架的异常契约，业务失败不走异常路径；</li>
 *   <li>返回空列表让 PlanAndExecuteStrategy 用统一的分支判断：
 *       {@code steps.isEmpty()} 即视为计划生成失败，
 *       转成 {@code AgentResult.failure(...)}；</li>
 *   <li>不引入专门的 {@code PlanParseException}——新增异常类型
 *       需要真实收益，而这里"空列表"已经能清晰表达语义。</li>
 * </ul>
 *
 * <p><b>全有或全无：</b>解析器不应返回"部分步骤"——例如模型输出了
 * 3 步，前 2 步合法、第 3 步格式错，此时应返回空列表，而不是返回
 * 前 2 步。理由：部分成功会让 Plan 策略在"执行到一半"时才发现计划
 * 不完整，修复成本更高。要么一份完整可用的计划，要么判定为失败，
 * 让调用方决定重试。</p>
 *
 * <p><b>index 由解析器分配：</b>返回的列表中，每个 {@link PlanStep} 的
 * {@code index} 应当是从 0 开始连续递增的。解析器最了解模型输出的顺序，
 * 由它统一分配 index 可以避免调用方再遍历一次。</p>
 *
 * <p><b>实现约束：</b>
 * <ul>
 *   <li>实现必须线程安全——策略可能在多线程环境下调用；</li>
 *   <li>实现不得有副作用——纯函数：相同输入 → 相同输出；</li>
 *   <li>返回的列表必须是不可变快照，调用方无法通过它修改解析器内部状态。</li>
 * </ul>
 */
public interface PlanParser {

    /**
     * 把模型输出的计划文本解析为步骤列表。
     *
     * <p>格式由 prompt 约定，本接口不规定——实现与 prompt 是一对约定，
     * 二者必须匹配。典型做法是让模型返回 JSON 数组，实现用 JSON 库解析；
     * 也可以约定 Markdown 编号列表，实现用正则提取。
     *
     * @param rawModelOutput 模型返回的原始文本，非 null
     * @return 解析出的步骤列表，从 index=0 开始连续编号。
     *         解析失败或未识别出任何步骤时返回空列表，永不为 null
     */
    List<PlanStep> parse(String rawModelOutput);
}