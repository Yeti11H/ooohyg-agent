package com.ooohyg.agent.strategy.plan;

import java.util.Objects;

/**
 * 计划中的一步。
 *
 * <p>Plan-and-Execute 策略在 PLANNING 阶段生成一组 PlanStep，
 * 在 EXECUTING 阶段逐个执行。PlanStep 是"计划的最小单元"，
 * 不承载"怎么执行"——具体执行方式由 PlanAndExecuteStrategy 决定
 * （每步通常委派给 ReactStrategy，见策略类的 Javadoc）。
 *
 * <p><b>为什么 index 从 0 开始：</b>
 * <ul>
 *   <li>与 Java 数组/List 索引一致，避免"列表下标 i 对应步骤 i+1"
 *       这种心智转换；</li>
 *   <li>日志里打印 "step[0] ... step[1] ..." 比 "step[1] ... step[2] ..."
 *       与代码遍历顺序更吻合；</li>
 *   <li>步骤序号只用于标识和排序，无业务含义，起点是 0 还是 1
 *       本质是约定。选定后全项目一致即可。</li>
 * </ul>
 *
 * <p><b>为什么 description 是自然语言：</b>
 * <ul>
 *   <li>它是模型生成的，也是给模型看的——每步执行时，这段描述会作为
 *       ReactStrategy 的 userInput（或 userInput 的一部分）；</li>
 *   <li>core 不解析它，不要求 JSON 或任何结构化格式——
 *       步骤描述的"结构"由生成端（模型）和消费端（子策略）约定；</li>
 *   <li>调试时人能直接读懂，比结构化格式友好。</li>
 * </ul>
 *
 * <p><b>为什么有 done 字段：</b>
 * <ul>
 *   <li>EXECUTING 阶段每完成一步就替换为 done=true 的版本，
 *       策略内部据此判断"还有哪些没做"；</li>
 *   <li>第一版没有外部 observer，done 只被 PlanAndExecuteStrategy
 *       自己读取。但它不影响不可变性，未来接事件/监控时不需要改结构；</li>
 *   <li>不用 {@code Set<Integer> completedIndexes} 单独记录进度——
 *       进度是步骤自身的属性，放在一起语义更内聚。</li>
 * </ul>
 *
 * <p><b>不可变性：</b>record 天然不可变。标记完成用
 * {@link #markDone()} 返回新实例，不修改原对象——策略内部替换即可。
 */
public record PlanStep(
        int index,
        String description,
        boolean done
) {

    /**
     * 紧凑构造器：校验 index 非负、description 非空白。
     *
     * <p>{@code done} 不需要校验——布尔值总有确定取值。
     */
    public PlanStep {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "index must not be negative, got: " + index);
        }
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }

    /**
     * 创建未完成状态的步骤。
     *
     * <p>生成计划时用本工厂方法。{@code done} 默认为 false。
     *
     * @param index       步骤序号，从 0 开始，非负
     * @param description 步骤描述，非 null、非空白
     */
    public static PlanStep pending(int index, String description) {
        return new PlanStep(index, description, false);
    }

    /**
     * 返回本步骤的"已完成"副本。
     *
     * <p>不修改当前实例——record 不可变。策略执行完一步后，
     * 用返回值替换列表中的原对象。
     */
    public PlanStep markDone() {
        return new PlanStep(index, description, true);
    }
}