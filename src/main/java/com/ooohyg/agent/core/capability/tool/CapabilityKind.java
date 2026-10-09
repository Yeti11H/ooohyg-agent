package com.ooohyg.agent.core.capability.tool;

/**
 * 工具的能力分类。
 *
 * <p>每个工具通过 {@link Tool#capability()} 声明自己属于哪一类。
 * 权限决策器根据这个分类决定"默认怎么处理"。
 *
 * <p><b>为什么默认最保守：</b>
 * {@link Tool#capability()} 的默认返回值是 {@link #READ}。
 * 用户新写工具忘了声明时，框架按只读处理——即使它实际会写文件、
 * 发网络请求，决策器也会拒绝或要求审批。这是 fail-safe 设计。
 */
public enum CapabilityKind {

    /** 只读能力：读文件、查数据库、检索。风险最低。 */
    READ,

    /** 写能力：写文件、改数据库、发消息。有副作用。 */
    WRITE,

    /** 网络能力：发 HTTP、调外部 API。可能泄露数据。 */
    NETWORK,

    /** 执行能力：跑系统命令、执行代码。风险最高。 */
    EXECUTE,

    /** 委派能力：派子 Agent。内部会递归决策。 */
    DELEGATE
}