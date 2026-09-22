package com.ooohyg.agent.core.execution;


import java.util.Objects;
import java.util.UUID;

/**
 * Agent 执行的唯一标识。
 *
 * <p>采用独立值对象而非裸 {@code String}，原因是：
 * <ul>
 *   <li>编译期防止把 conversationId、taskId 等其他字符串 id 传错位置；</li>
 *   <li>id 携带生成规则（时间前缀 + 随机后缀），便于按时间范围排查；</li>
 *   <li>日志、序列化、跨进程传递时类型明确。</li>
 * </ul>
 *
 * <p>本类使用 Java 21 的 {@code record} 声明。record 是"不可变值对象"的
 * 语法糖，编译器会自动生成 {@code equals}、{@code hashCode}、
 * {@code toString} 和构造器。你不需要手写这些。
 *
 * <p>格式：{@code exec_<epochMillis>_<8hex>}
 * 例如：{@code exec_1712345678901_a1b2c3d4}
 */
public record ExecutionId(String value) {

    /**
     * 紧凑构造器：在对象创建时做校验。
     *
     * <p>record 的紧凑构造器写法是 {@code public ExecutionId {} } 不带参数列表，
     * 参数 {@code value} 在方法体内隐式可用。校验失败直接抛异常。
     */
    public ExecutionId {
        Objects.requireNonNull(value, "ExecutionId value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("ExecutionId value must not be blank");
        }
    }

    /**
     * 生成一个新的 ExecutionId。
     *
     * <p>为什么用"时间戳 + 随机后缀"而不是纯 UUID：
     * <ul>
     *   <li>时间戳前缀让 id 天然按时间有序，便于排查和归档；</li>
     *   <li>随机后缀保证同一毫秒内的并发不会碰撞。</li>
     * </ul>
     */
    public static ExecutionId generate() {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new ExecutionId("exec_" + System.currentTimeMillis() + "_" + hex);
    }

    /**
     * 从已有字符串恢复一个 ExecutionId（反序列化场景）。
     *
     * @param value 非空、非空白字符串
     * @return 新的 ExecutionId 实例
     */
    public static ExecutionId of(String value) {
        return new ExecutionId(value);
    }

    /**
     * 覆盖 record 自动生成的 toString，让它直接返回 value。
     *
     * <p>默认的 {@code toString()} 会输出 {@code ExecutionId[value=exec_...]}，
     * 在日志里显得冗长。这里直接返回纯 id 字符串。
     */
    @Override
    public String toString() {
        return value;
    }
}