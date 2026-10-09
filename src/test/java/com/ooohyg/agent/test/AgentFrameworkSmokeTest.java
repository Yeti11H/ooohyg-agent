package com.ooohyg.agent.test;

import com.ooohyg.agent.core.base.AgentState;
import com.ooohyg.agent.core.base.BaseAgent;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.tool.DefaultToolRegistry;
import com.ooohyg.agent.core.capability.tool.EchoTool;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolResult;
import com.ooohyg.agent.core.exception.AgentException;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import com.ooohyg.agent.core.message.ToolCall;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ReflexionStrategy;
import com.ooohyg.agent.strategy.StrategyType;
import com.ooohyg.agent.strategy.plan.PlanAndExecuteStrategy;
import com.ooohyg.agent.strategy.react.ReactStrategy;
import com.ooohyg.agent.testutil.AddTool;
import com.ooohyg.agent.testutil.FixedAnswerStrategy;
import com.ooohyg.agent.testutil.KeywordEvaluator;
import com.ooohyg.agent.testutil.LinePlanParser;
import com.ooohyg.agent.testutil.ScriptedLlmClient;
import com.ooohyg.agent.testutil.ScriptedLlmClient.ChatCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ooohyg-agent 框架可运行性冒烟测试（纯 JDK main 方法，不依赖 JUnit）。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>T01 工具注册与执行（DefaultToolRegistry + EchoTool + AddTool + 重名检测）</li>
 *   <li>T02 消息流转（Message 工厂合法构造 + 非法构造互斥约束）</li>
 *   <li>T03 ReAct 端到端（think → act(echo) → observe → answer）</li>
 *   <li>T04 ReAct 工具不存在（Tool not found 喂回模型）</li>
 *   <li>T05 ReAct 步数耗尽 wrap-up（正常收尾 / wrap-up 仍调工具）</li>
 *   <li>T06 Plan-and-Execute 端到端（Plan(React) 嵌套，5 次模型调用）</li>
 *   <li>T07 Plan 空计划业务失败</li>
 *   <li>T08 ReflexionStrategy 可引用性验证（import + 实例化 + type）</li>
 *   <li>T09 Reflexion 端到端（评估接受 / 反馈注入 / 循环耗尽）</li>
 * </ul>
 *
 * <p>运行：java -cp &lt;classes&gt; com.ooohyg.agent.test.AgentFrameworkSmokeTest
 */
public final class AgentFrameworkSmokeTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();
    private static final List<String> diagnostics = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("========== ooohyg-agent 框架可运行性冒烟测试 ==========");
        System.out.println("环境: Java " + System.getProperty("java.version"));
        System.out.println();

        run("T01 工具注册与执行", AgentFrameworkSmokeTest::test01_toolRegistry);
        run("T02 消息流转", AgentFrameworkSmokeTest::test02_messageFlow);
        run("T03 ReAct 端到端", AgentFrameworkSmokeTest::test03_reactEndToEnd);
        run("T04 ReAct 工具不存在", AgentFrameworkSmokeTest::test04_reactUnknownTool);
        run("T05a ReAct 步数耗尽 wrap-up 正常收尾", AgentFrameworkSmokeTest::test05a_reactWrapUpSuccess);
        run("T05b ReAct wrap-up 仍返回工具调用", AgentFrameworkSmokeTest::test05b_reactWrapUpStillToolCall);
        run("T06 Plan-and-Execute 端到端", AgentFrameworkSmokeTest::test06_planEndToEnd);
        run("T07 Plan 空计划失败", AgentFrameworkSmokeTest::test07_planEmptyPlan);
        run("T08 ReflexionStrategy 可引用性验证", AgentFrameworkSmokeTest::test08_reflexionReference);
        run("T09 Reflexion 端到端", AgentFrameworkSmokeTest::test09_reflexionEndToEnd);

        System.out.println();
        System.out.println("========== 汇总 ==========");
        System.out.println("通过: " + passed + "，失败: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("失败明细:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
        }
        if (!diagnostics.isEmpty()) {
            System.out.println("实验/诊断输出:");
            for (String d : diagnostics) {
                System.out.println("  " + d);
            }
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // ================= 工具 =================

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void run(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failures.add(name + " => " + t);
            System.out.println("[FAIL] " + name + " => " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    // ================= 测试用例 =================

    /** T01 工具注册与执行。 */
    private static void test01_toolRegistry() {
        EchoTool echo = new EchoTool();
        AddTool add = new AddTool();
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(echo, add));

        Optional<Tool> echoFound = registry.find("echo");
        assertTrue(echoFound.isPresent(), "find('echo') 应命中");
        assertEquals("echo", echoFound.get().definition().name(), "echo definition name");

        Optional<Tool> addFound = registry.find("add");
        assertTrue(addFound.isPresent(), "find('add') 应命中");
        assertEquals("add", addFound.get().definition().name(), "add definition name");

        assertTrue(registry.find("nope").isEmpty(), "find('nope') 应为空");
        assertTrue(registry.find(null).isEmpty(), "find(null) 应为空");
        assertEquals(2, registry.definitions().size(), "definitions 数量应为 2");

        // definitions 是不可变快照
        try {
            registry.definitions().clear();
            throw new AssertionError("definitions 应不可变（clear 应抛 UnsupportedOperationException）");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        // 重名检测
        try {
            new DefaultToolRegistry(List.of(new EchoTool(), new EchoTool()));
            throw new AssertionError("重名工具应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Duplicate tool name"),
                    "重名异常消息应含 Duplicate tool name");
        }

        // 工具执行
        ToolResult echoResult = echo.execute("{\"msg\":\"hi\"}");
        assertTrue(echoResult.success(), "EchoTool 应成功");
        assertEquals("Echo: {\"msg\":\"hi\"}", echoResult.output(), "EchoTool 输出");

        ToolResult addResult = add.execute("1+2");
        assertTrue(addResult.success(), "AddTool('1+2') 应成功");
        assertEquals("Result: 3", addResult.output(), "AddTool 输出");

        ToolResult badResult = add.execute("abc");
        assertTrue(!badResult.success(), "AddTool('abc') 应业务失败");
        assertTrue(badResult.errorMessage() != null && !badResult.errorMessage().isBlank(),
                "AddTool 失败应携带 errorMessage");
    }

    /** T02 消息流转。 */
    private static void test02_messageFlow() {
        // 合法构造
        Message sys = Message.system("系统提示");
        assertEquals(MessageRole.SYSTEM, sys.role(), "SYSTEM role");
        assertEquals(null, sys.toolCallId(), "SYSTEM toolCallId 应为 null");
        assertEquals(null, sys.toolCalls(), "SYSTEM toolCalls 应为 null");

        Message user = Message.user("用户问题");
        assertEquals(MessageRole.USER, user.role(), "USER role");
        assertEquals("用户问题", user.content(), "USER content");

        Message assistant = Message.assistant("纯文本回答");
        assertEquals(MessageRole.ASSISTANT, assistant.role(), "ASSISTANT role");
        assertTrue(!assistant.hasToolCalls(), "纯文本 assistant 不应有 toolCalls");

        Message assistantWithTool = Message.assistantWithToolCalls(
                "我来查一下", List.of(new ToolCall("call_1", "echo", "{}")));
        assertTrue(assistantWithTool.hasToolCalls(), "带工具调用的 assistant 应有 toolCalls");
        assertEquals("call_1", assistantWithTool.toolCalls().get(0).id(), "toolCall id");

        Message tool = Message.tool("call_1", "工具输出");
        assertEquals(MessageRole.TOOL, tool.role(), "TOOL role");
        assertEquals("call_1", tool.toolCallId(), "TOOL toolCallId");
        assertEquals(null, tool.toolCalls(), "TOOL toolCalls 应为 null");

        // 非法构造：SYSTEM/USER 不能携带 toolCallId / toolCalls
        expectIllegalArgument(() -> new Message(MessageRole.SYSTEM, "内容", "call_x", null),
                "SYSTEM 携带 toolCallId 应抛");
        expectIllegalArgument(() -> new Message(MessageRole.USER, "内容", null,
                        List.of(new ToolCall("c", "e", "{}"))),
                "USER 携带 toolCalls 应抛");
        // USER 内容空白
        expectIllegalArgument(() -> Message.user("  "), "USER 空白内容应抛");
        // ASSISTANT content 与 toolCalls 全空
        expectIllegalArgument(() -> new Message(MessageRole.ASSISTANT, null, null, null),
                "ASSISTANT 全空应抛");
        expectIllegalArgument(() -> Message.assistantWithToolCalls(null, List.of()),
                "ASSISTANT 空 toolCalls 应抛");
        // TOOL 缺少 toolCallId 或携带 toolCalls
        expectIllegalArgument(() -> Message.tool(null, "内容"), "TOOL 缺 toolCallId 应抛");
        expectIllegalArgument(() -> new Message(MessageRole.TOOL, "内容", "c",
                        List.of(new ToolCall("c", "e", "{}"))),
                "TOOL 携带 toolCalls 应抛");
    }

    /** T03 ReAct 端到端：think → act(echo) → observe → answer。 */
    private static void test03_reactEndToEnd() {
        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                new LlmResponse("我需要调用工具确认", List.of(new ToolCall("call_1", "echo", "hello")), "tool_calls"),
                new LlmResponse("最终答案：你好，我是测试 Agent", null, "stop")
        ));
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));
        BaseAgent agent = new BaseAgent("react-agent",
                new ReactStrategy(llm, registry), 5);

        AgentResult result = agent.run("打个招呼");
        assertTrue(result.success(), "ReAct 端到端应成功");
        assertTrue(result.output().contains("最终答案"), "输出应包含最终答案，实际: " + result.output());
        assertEquals(StrategyType.REACT, result.strategyType(), "strategyType 应为 REACT");
        assertEquals(AgentState.FINISHED, agent.state(), "Agent 状态应为 FINISHED");

        List<ChatCall> calls = llm.calls();
        assertEquals(2, calls.size(), "模型应被调用 2 次");

        // 第 2 次调用应携带 TOOL 消息（工具结果已喂回模型）
        Message secondCallToolMsg = calls.get(1).messages().stream()
                .filter(m -> m.role() == MessageRole.TOOL)
                .findFirst().orElse(null);
        assertTrue(secondCallToolMsg != null, "第 2 次模型调用应包含 TOOL 消息");
        assertEquals("Echo: hello", secondCallToolMsg.content(), "TOOL 消息内容应为回显结果");
        assertEquals("call_1", secondCallToolMsg.toolCallId(), "TOOL 消息 toolCallId 配对");

        // 第 1 次调用应带工具定义
        assertEquals(1, calls.get(0).tools().size(), "第 1 次调用应携带工具定义");
        assertEquals("echo", calls.get(0).tools().get(0).name(), "工具定义应为 echo");
    }

    /** T04 ReAct 工具不存在：Tool not found 应喂回模型。 */
    private static void test04_reactUnknownTool() {
        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                new LlmResponse(null, List.of(new ToolCall("call_1", "unknown_tool", "{}")), "tool_calls"),
                new LlmResponse("我无法调用该工具，改用内置知识回答", null, "stop")
        ));
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));
        BaseAgent agent = new BaseAgent("react-unknown",
                new ReactStrategy(llm, registry), 5);

        AgentResult result = agent.run("测试未知工具");
        assertTrue(result.success(), "未知工具场景应最终成功");
        assertTrue(result.output().contains("内置知识"), "输出应包含模型回答");

        Message toolMsg = llm.calls().get(1).messages().stream()
                .filter(m -> m.role() == MessageRole.TOOL)
                .findFirst().orElse(null);
        assertTrue(toolMsg != null, "第 2 次调用应包含 TOOL 消息");
        assertTrue(toolMsg.content().contains("Tool not found: unknown_tool"),
                "TOOL 消息应包含 Tool not found，实际: " + toolMsg.content());
    }

    /** T05a ReAct 步数耗尽：wrap-up 调用（tools=null）返回纯文本 → 成功。 */
    private static void test05a_reactWrapUpSuccess() {
        // maxSteps=2：step0 工具调用，step1 工具调用，随后 wrap-up（第 3 次调用，无工具）
        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                new LlmResponse(null, List.of(new ToolCall("c1", "echo", "a")), "tool_calls"),
                new LlmResponse(null, List.of(new ToolCall("c2", "echo", "b")), "tool_calls"),
                new LlmResponse("步数已用尽，这是最终答案", null, "stop")
        ));
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));
        BaseAgent agent = new BaseAgent("react-wrapup",
                new ReactStrategy(llm, registry), 2);

        AgentResult result = agent.run("耗步数测试");
        assertTrue(result.success(), "wrap-up 纯文本应成功");
        assertTrue(result.output().contains("最终答案"), "输出应为 wrap-up 答案");
        assertEquals(3, llm.calls().size(), "应共调用模型 3 次");
        assertTrue(llm.calls().get(2).tools().isEmpty(), "wrap-up 调用不应携带工具定义");
    }

    /** T05b ReAct wrap-up 仍返回工具调用 → 业务失败。 */
    private static void test05b_reactWrapUpStillToolCall() {
        ScriptedLlmClient llm = new ScriptedLlmClient(
                List.of(new LlmResponse(null, List.of(new ToolCall("c1", "echo", "a")), "tool_calls")),
                true); // 无限重复工具调用
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));
        BaseAgent agent = new BaseAgent("react-wrapup-fail",
                new ReactStrategy(llm, registry), 2);

        AgentResult result = agent.run("耗步数测试");
        assertTrue(!result.success(), "wrap-up 仍调工具应业务失败");
        assertTrue(result.failureMessage().contains("wrap-up"),
                "失败信息应说明 wrap-up，实际: " + result.failureMessage());
        assertEquals(AgentState.FINISHED, agent.state(), "业务失败不应导致 ERROR 状态");
    }

    /** T06 Plan-and-Execute 端到端：Plan(React(echo))，共 5 次模型调用。 */
    private static void test06_planEndToEnd() {
        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                // 1. PLANNING：生成两行计划
                new LlmResponse("第一步：回显 a\n第二步：回显 b", null, "stop"),
                // 2. step0 React think
                new LlmResponse(null, List.of(new ToolCall("p1", "echo", "a")), "tool_calls"),
                // 3. step0 React observe 后 answer
                new LlmResponse("步骤一完成", null, "stop"),
                // 4. step1 React think
                new LlmResponse(null, List.of(new ToolCall("p2", "echo", "b")), "tool_calls"),
                // 5. step1 React observe 后 answer
                new LlmResponse("步骤二完成", null, "stop")
        ));
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));
        ReactStrategy react = new ReactStrategy(llm, registry);
        PlanAndExecuteStrategy plan = new PlanAndExecuteStrategy(
                llm, new LinePlanParser(), react);
        BaseAgent agent = new BaseAgent("plan-agent", plan, 5);

        AgentResult result = agent.run("做两件回显任务");
        assertTrue(result.success(), "Plan 端到端应成功");
        assertTrue(result.output().contains("步骤二完成"), "输出应为最后一步输出，实际: " + result.output());
        assertEquals(StrategyType.PLAN_AND_EXECUTE, result.strategyType(), "strategyType 应为 PLAN_AND_EXECUTE");
        assertEquals(5, llm.calls().size(), "应共调用模型 5 次");

        // 第 1 次（PLANNING）不携带工具定义；后续 React 调用携带 echo 定义
        assertTrue(llm.calls().get(0).tools().isEmpty(), "PLANNING 调用不应携带工具");
        for (int i = 1; i < 5; i++) {
            assertEquals("echo", llm.calls().get(i).tools().get(0).name(),
                    "React 子策略调用应携带 echo 工具");
        }
    }

    /** T07 Plan 空计划：解析失败 → 业务失败。 */
    private static void test07_planEmptyPlan() {
        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                new LlmResponse("# 这是注释行，格式不合规", null, "stop")
        ));
        FixedAnswerStrategy sub = new FixedAnswerStrategy("never reached");
        PlanAndExecuteStrategy plan = new PlanAndExecuteStrategy(
                llm, new LinePlanParser(), sub);
        BaseAgent agent = new BaseAgent("plan-empty", plan, 5);

        AgentResult result = agent.run("任务");
        assertTrue(!result.success(), "空计划应业务失败");
        assertTrue(result.failureMessage().contains("Plan generation failed"),
                "失败信息应说明计划生成失败，实际: " + result.failureMessage());
        assertTrue(sub.receivedInputs().isEmpty(), "子策略不应被执行");
    }

    /** T08 ReflexionStrategy 可引用性验证：有 package 声明、可 import、可实例化。 */
    private static void test08_reflexionReference() {
        // ReflexionStrategy.java 有 package com.ooohyg.agent.strategy; 声明，
        // 命名包内可直接 import 引用（本测试类顶部 import 编译通过即为证据）。
        ReflexionStrategy reflex = new ReflexionStrategy(
                new KeywordEvaluator("OK"), new FixedAnswerStrategy("OK"));
        assertEquals(StrategyType.REFLEXION, reflex.type(), "ReflexionStrategy.type() 应为 REFLEXION");

        // 非法参数保护
        try {
            new ReflexionStrategy(new KeywordEvaluator("OK"), new FixedAnswerStrategy("OK"), 0);
            throw new AssertionError("maxAttempts<=0 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("maxAttempts"),
                    "异常消息应提及 maxAttempts，实际: " + expected.getMessage());
        }
    }

    /** T09 Reflexion 端到端：评估接受 / 反馈注入 / 循环耗尽。 */
    private static void test09_reflexionEndToEnd() {
        // 场景 A：子策略一次输出含关键字 -> 首次评估即接受，无 feedback 注入
        FixedAnswerStrategy subA = new FixedAnswerStrategy("最终答案：内容包含 OK");
        ReflexionStrategy reflexA = new ReflexionStrategy(new KeywordEvaluator("OK"), subA, 3);
        BaseAgent agentA = new BaseAgent("reflex-a", reflexA, 5);

        AgentResult resultA = agentA.run("任务 A");
        assertTrue(resultA.success(), "场景 A 应成功，实际: " + resultA.failureMessage());
        assertEquals(StrategyType.REFLEXION, resultA.strategyType(), "场景 A strategyType 应为 REFLEXION");
        assertEquals("最终答案：内容包含 OK", resultA.output(), "场景 A 输出应为子策略输出");
        assertEquals(1, subA.receivedInputs().size(), "场景 A 子策略应只执行 1 次");
        assertTrue(!subA.receivedInputs().get(0).contains("修正建议"),
                "场景 A 首次执行不应携带 feedback");
        assertEquals(AgentState.FINISHED, agentA.state(), "场景 A 最终状态应为 FINISHED");

        // 场景 B：子策略始终不含关键字 -> 每轮注入 feedback，3 次尝试后耗尽失败
        FixedAnswerStrategy subB = new FixedAnswerStrategy("不合格的回答");
        ReflexionStrategy reflexB = new ReflexionStrategy(new KeywordEvaluator("OK"), subB, 3);
        BaseAgent agentB = new BaseAgent("reflex-b", reflexB, 5);

        AgentResult resultB = agentB.run("任务 B");
        assertTrue(!resultB.success(), "场景 B 应失败（循环耗尽）");
        assertTrue(resultB.failureMessage().contains("Reflexion exhausted"),
                "场景 B 失败信息应含 Reflexion exhausted，实际: " + resultB.failureMessage());
        assertEquals(3, subB.receivedInputs().size(), "场景 B 子策略应执行 3 次");

        // 第 2、3 次输入必须携带 feedback（第 1 次不带）
        assertTrue(!subB.receivedInputs().get(0).contains("修正建议"),
                "场景 B 第 1 次输入不应携带 feedback");
        for (int i = 1; i < 3; i++) {
            String input = subB.receivedInputs().get(i);
            assertTrue(input.contains("原始任务"), "场景 B 第 " + (i + 1) + " 次输入应含原始任务");
            assertTrue(input.contains("修正建议"), "场景 B 第 " + (i + 1) + " 次输入应含修正建议段");
            assertTrue(input.contains("缺少以下关键内容"),
                    "场景 B 第 " + (i + 1) + " 次输入应携带 KeywordEvaluator 的 feedback，实际: " + input);
        }
        assertEquals(AgentState.FINISHED, agentB.state(), "场景 B 业务失败不应置 ERROR 状态");
    }

    // ================= 辅助 =================

    private static final List<String> diagnosticsList = diagnostics;

    private static void expectIllegalArgument(ThrowingRunnable body, String message) {
        try {
            body.run();
            throw new AssertionError(message + "（未抛异常）");
        } catch (IllegalArgumentException expected) {
            // expected
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError(message + "（抛出异常类型不对: " + e.getClass().getName() + "）");
        }
    }
}
