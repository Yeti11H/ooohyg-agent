package com.ooohyg.agent.core.capability.agent;

import com.ooohyg.agent.core.base.BaseAgent;
import com.ooohyg.agent.core.capability.tool.CapabilityKind;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolDefinition;
import com.ooohyg.agent.core.capability.tool.ToolResult;
import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.result.AgentResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 把"派子 Agent"包装成 {@link Tool}，让主 Agent 在 ReAct 循环里
 * 像调用普通工具一样调用子 Agent。
 *
 * <p><b>核心哲学：把"要不要派子 Agent"和"派哪个"合并为一次选择。</b>
 * 本工具的 {@code description} 会把所有已注册子 Agent 的 name
 * 和 description 拼进去，模型在选工具时一步完成两个判断。
 *
 * <p><b>上下文隔离是本工具的核心价值：</b>
 * 子 Agent 在自己的 {@link BaseAgent} 里跑，有独立的
 * {@code messages} 列表、独立的 {@code ExecutionId}、独立的
 * 工具调用轨迹。跑完只把最终 {@code AgentResult} 转成一条
 * {@code ToolResult} 返回给主 Agent。中间过程完全隔离。
 *
 * <p><b>技术故障的处理：</b>子 Agent 抛出的 {@link AgentException}
 * 会被本工具捕获并转成 {@code ToolResult.failure}，而不是继续向上抛。
 * 原因：子 Agent 是主 Agent 的一个"能力"，它的技术故障相当于
 * "这个能力暂时不可用"——主 Agent 收到这个失败结果后，
 * 可以选择换一个子 Agent、换个参数、或者自己直接处理。
 *
 * <p><b>能力分类是 DELEGATE：</b>
 * 主 Agent 的权限决策器看到 {@link CapabilityKind#DELEGATE}，
 * 会按 DELEGATE 的规则处理——而不是把它当普通 READ 工具放行。
 *
 * <p><b>可重入：</b>本类无状态——它只持有 registry。每次 execute
 * 都新建一个 {@link BaseAgent}，跑完即销毁。同一个 SubAgentTool
 * 实例可以并发服务多个请求。
 */
public final class SubAgentTool implements Tool {

    private static final String DEFAULT_TOOL_NAME = "Agent";

    private final SubAgentRegistry registry;
    private final ToolDefinition definition;

    /**
     * 用默认工具名 "Agent" 构造。
     *
     * @param registry 子 Agent 注册表，非 null
     */
    public SubAgentTool(SubAgentRegistry registry) {
        this(registry, DEFAULT_TOOL_NAME);
    }

    /**
     * 用自定义工具名构造。
     *
     * @param registry 子 Agent 注册表，非 null
     * @param toolName 工具名，非空
     */
    public SubAgentTool(SubAgentRegistry registry, String toolName) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        this.definition = buildDefinition(toolName);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /**
     * 子 Agent 的能力分类是 DELEGATE。
     *
     * <p>这让主 Agent 的权限决策器知道"这是一个派子 Agent 的动作"，
     * 而不是把它当普通 READ 工具无脑放行。
     *
     * <p>子 Agent 内部的工具调用会有它自己的权限决策器再检查一次——
     * 两层独立决策，不互相替代。
     */
    @Override
    public CapabilityKind capability() {
        return CapabilityKind.DELEGATE;
    }

    @Override
    public ToolResult execute(String arguments) {
        String subAgentName = extractValue(arguments, "subagent_name");
        String prompt = extractValue(arguments, "prompt");

        if (subAgentName.isBlank()) {
            return ToolResult.failure(
                    "Missing 'subagent_name'. Available: " + availableNames());
        }
        if (prompt.isBlank()) {
            return ToolResult.failure("Missing 'prompt'.");
        }

        Optional<SubAgentDefinition> opt = registry.find(subAgentName);
        if (opt.isEmpty()) {
            return ToolResult.failure(
                    "Sub-agent not found: '" + subAgentName
                            + "'. Available: " + availableNames());
        }

        SubAgentDefinition def = opt.get();

        // 每次派子 Agent 都新建一个 BaseAgent。
        // BaseAgent 是一次性对象，跑完即废，成本很低。
        BaseAgent subAgent = new BaseAgent(def.name(), def.strategy(), def.maxSteps());

        try {
            AgentResult result = subAgent.run(prompt);
            if (result.success()) {
                return ToolResult.success(result.output());
            }
            return ToolResult.failure(
                    "Sub-agent '" + subAgentName + "' failed: " + result.failureMessage());
        } catch (AgentException e) {
            // 子 Agent 的技术故障转成业务失败，让主 Agent 能继续决策。
            return ToolResult.failure(
                    "Sub-agent '" + subAgentName + "' failed with technical error: "
                            + e.getMessage());
        }
    }

    // ---------- 内部辅助 ----------

    /**
     * 构造 ToolDefinition，把 registry 里的所有子 Agent 拼进描述里。
     *
     * <p>这是"一步决策"的关键——模型看到这个描述，就知道
     * 有哪些子 Agent 可选、各自擅长什么。
     */
    private ToolDefinition buildDefinition(String toolName) {
        List<SubAgentDefinition> subs = registry.definitions();

        String description;
        if (subs.isEmpty()) {
            description = "派子 Agent 执行任务。当前没有可用的子 Agent。";
        } else {
            String list = subs.stream()
                    .map(d -> "- " + d.name() + ": " + d.description())
                    .collect(Collectors.joining("\n"));
            description = """
                    派子 Agent 执行任务。当你需要以下专业能力时使用：
                    """ + list + """

                    子 Agent 有独立的上下文，只返回最终结论。
                    传入 subagent_name 指定用哪个，prompt 描述任务。
                    """;
        }

        return new ToolDefinition(
                toolName,
                description,
                """
                {"type":"object","properties":{
                  "subagent_name":{"type":"string",
                                   "description":"要派哪个子 Agent（用名字指定）"},
                  "prompt":{"type":"string",
                            "description":"给子 Agent 的任务描述"}
                },"required":["subagent_name","prompt"]}
                """
        );
    }

    /** 已注册子 Agent 名字的逗号分隔串，用于错误提示。 */
    private String availableNames() {
        return registry.definitions().stream()
                .map(SubAgentDefinition::name)
                .collect(Collectors.joining(", "));
    }

    /**
     * 从 arguments 中提取字符串字段。
     *
     * <p>极简实现：找 JSON 里的 key，提取其后的字符串值，
     * 处理基本的 {@code \"} 和 {@code \\} 转义。不处理嵌套、
     * 数组、Unicode 转义——那属于极少数场景，引入完整 JSON 库不值得。
     */
    private static String extractValue(String arguments, String key) {
        if (arguments == null) {
            return "";
        }
        String trimmed = arguments.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (!trimmed.startsWith("{")) {
            return stripQuotes(trimmed);
        }
        int keyIdx = trimmed.indexOf("\"" + key + "\"");
        if (keyIdx < 0) {
            return stripQuotes(trimmed);
        }
        int colonIdx = trimmed.indexOf(':', keyIdx);
        if (colonIdx < 0) {
            return stripQuotes(trimmed);
        }
        int valueStart = trimmed.indexOf('"', colonIdx + 1);
        if (valueStart < 0) {
            return stripQuotes(trimmed);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = valueStart + 1; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                char next = trimmed.charAt(i + 1);
                if (next == '"' || next == '\\') {
                    sb.append(next);
                    i++;
                    continue;
                }
            }
            if (c == '"') {
                return sb.toString();
            }
            sb.append(c);
        }
        return stripQuotes(trimmed);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}