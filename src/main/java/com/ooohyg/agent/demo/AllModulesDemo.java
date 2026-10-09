package com.ooohyg.agent.demo;

import com.ooohyg.agent.adapter.AddTool;
import com.ooohyg.agent.adapter.HashEmbeddingClient;
import com.ooohyg.agent.adapter.KeywordEvaluator;
import com.ooohyg.agent.adapter.LinePlanParser;
import com.ooohyg.agent.adapter.RuleBasedLlmClient;
import com.ooohyg.agent.adapter.VectorLongTermMemory;
import com.ooohyg.agent.adapter.WindowShortTermMemory;
import com.ooohyg.agent.core.base.BaseAgent;
import com.ooohyg.agent.core.capability.llm.LlmClient;
import com.ooohyg.agent.core.capability.memory.LongMemoryRecord;
import com.ooohyg.agent.core.capability.memory.MemoryAwareLlmClient;
import com.ooohyg.agent.core.capability.memory.MemoryRecallTool;
import com.ooohyg.agent.core.capability.memory.ShortTermMemory;
import com.ooohyg.agent.core.capability.retrieval.Document;
import com.ooohyg.agent.core.capability.retrieval.EmbeddingClient;
import com.ooohyg.agent.core.capability.retrieval.InMemoryVectorStore;
import com.ooohyg.agent.core.capability.retrieval.RetrievalTool;
import com.ooohyg.agent.core.capability.retrieval.VectorStore;
import com.ooohyg.agent.core.capability.tool.DefaultToolRegistry;
import com.ooohyg.agent.core.capability.tool.EchoTool;
import com.ooohyg.agent.core.capability.tool.Tool;
import com.ooohyg.agent.core.capability.tool.ToolRegistry;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.ExecutionStrategy;
import com.ooohyg.agent.strategy.ReflexionStrategy;
import com.ooohyg.agent.strategy.plan.PlanAndExecuteStrategy;
import com.ooohyg.agent.strategy.react.ReactStrategy;

import java.util.List;
import java.util.Map;

/**
 * 全模块端到端演示。
 *
 * <p>把框架 core 接口与 adapter 具体实现类全部组装起来，一次跑通
 * 六大模块，作为"所有模块都有可运行业务实现"的验证入口：
 *
 * <ol>
 *   <li><b>ReAct + 工具调用</b>：ReactStrategy 驱动 AddTool 完成加法；</li>
 *   <li><b>RAG 检索</b>：RetrievalTool + HashEmbeddingClient +
 *       InMemoryVectorStore 完成知识库检索；</li>
 *   <li><b>长期记忆</b>：VectorLongTermMemory 记忆写入，
 *       MemoryRecallTool 按需回忆；</li>
 *   <li><b>Plan-and-Execute</b>：LinePlanParser 解析计划，
 *       子策略逐步骤执行；</li>
 *   <li><b>Reflexion</b>：KeywordEvaluator 评估失败后注入修正反馈重跑；</li>
 *   <li><b>短期记忆装饰</b>：MemoryAwareLlmClient + WindowShortTermMemory
 *       包裹模型客户端跑通流程。</li>
 * </ol>
 *
 * <p>运行方式：在项目根目录执行
 * {@code mvn -q compile exec:java -Dexec.mainClass=com.ooohyg.agent.demo.AllModulesDemo}
 * 或直接在 IDEA 中运行本类的 main。任意场景失败时以非零退出码结束。
 */
public final class AllModulesDemo {

    private AllModulesDemo() {
    }

    public static void main(String[] args) {
        int failures = 0;
        failures += runScenario("S1 ReAct + 工具调用（add）", () -> scenarioReactTool());
        failures += runScenario("S2 RAG 检索（retrieval）", () -> scenarioRagRetrieval());
        failures += runScenario("S3 长期记忆（recall_memory）", () -> scenarioLongTermMemory());
        failures += runScenario("S4 Plan-and-Execute", () -> scenarioPlanAndExecute());
        failures += runScenario("S5 Reflexion 修正重跑", () -> scenarioReflexion());
        failures += runScenario("S6 短期记忆装饰器", () -> scenarioShortTermMemory());

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL MODULES PASSED: 全部模块端到端跑通。");
        } else {
            System.out.println("FAILED SCENARIOS: " + failures + " 个场景未通过。");
            System.exit(1);
        }
    }

    // ---------- 场景定义 ----------

    /** S1：ReAct 循环 + 工具调用。 */
    private static boolean scenarioReactTool() {
        LlmClient llm = new RuleBasedLlmClient();
        ToolRegistry tools = new DefaultToolRegistry(List.of(new EchoTool(), new AddTool()));
        ExecutionStrategy strategy = new ReactStrategy(llm, tools);
        BaseAgent agent = new BaseAgent("react-tool-agent", strategy, 5);

        AgentResult result = agent.run("请计算 12+30 的和");
        return expectSuccess(result, "根据工具返回的结果：计算结果：42");
    }

    /** S2：RAG 检索链路（embed → store → search → 工具）。 */
    private static boolean scenarioRagRetrieval() {
        EmbeddingClient embedding = new HashEmbeddingClient();
        VectorStore store = new InMemoryVectorStore();

        store.add(
                List.of(
                        new Document("kb-1",
                                "向量检索是通过计算向量相似度来查找相关文档的技术，广泛用于 RAG。",
                                Map.of("tag", "vector")),
                        new Document("kb-2",
                                "RAG 是检索增强生成，先检索相关资料，再交给生成模型作答。",
                                Map.of("tag", "rag"))
                ),
                List.of(embedding.embed("向量检索是通过计算向量相似度来查找相关文档的技术，广泛用于 RAG。"),
                        embedding.embed("RAG 是检索增强生成，先检索相关资料，再交给生成模型作答。"))
        );

        LlmClient llm = new RuleBasedLlmClient();
        ToolRegistry tools = new DefaultToolRegistry(
                List.of(new RetrievalTool(embedding, store)));
        ExecutionStrategy strategy = new ReactStrategy(llm, tools);
        BaseAgent agent = new BaseAgent("rag-agent", strategy, 5);

        AgentResult result = agent.run("请检索知识库，查询：什么是向量检索");
        return expectSuccess(result, "向量检索");
    }

    /** S3：长期记忆：先写入，再让 Agent 主动回忆。 */
    private static boolean scenarioLongTermMemory() {
        EmbeddingClient embedding = new HashEmbeddingClient();
        VectorStore store = new InMemoryVectorStore();
        VectorLongTermMemory longTerm = new VectorLongTermMemory(embedding, store);
        longTerm.remember(new LongMemoryRecord(
                "mem-1", "用户最喜欢的编程语言是 Java", Map.of("type", "preference")));

        LlmClient llm = new RuleBasedLlmClient();
        ToolRegistry tools = new DefaultToolRegistry(
                List.of(new MemoryRecallTool(longTerm)));
        ExecutionStrategy strategy = new ReactStrategy(llm, tools);
        BaseAgent agent = new BaseAgent("memory-agent", strategy, 5);

        AgentResult result = agent.run("请回忆用户最喜欢的编程语言");
        return expectSuccess(result, "用户最喜欢的编程语言是 Java");
    }

    /** S4：Plan-and-Execute：规划两步，子策略逐步骤执行。 */
    private static boolean scenarioPlanAndExecute() {
        LlmClient llm = new RuleBasedLlmClient();
        ToolRegistry tools = new DefaultToolRegistry(List.of(new AddTool()));
        ExecutionStrategy react = new ReactStrategy(llm, tools);
        ExecutionStrategy plan = new PlanAndExecuteStrategy(llm, new LinePlanParser(), react);
        BaseAgent agent = new BaseAgent("plan-agent", plan, 5);

        AgentResult result = agent.run("请把任务拆解：计算 12+30 的和，并整理最终答案");
        return expectSuccess(result, "最终答案：42");
    }

    /** S5：Reflexion：第一次不达标，注入修正建议后第二次通过。 */
    private static boolean scenarioReflexion() {
        LlmClient llm = new RuleBasedLlmClient(List.of("最终答案"));
        ToolRegistry tools = new DefaultToolRegistry(List.of(new EchoTool()));
        ExecutionStrategy react = new ReactStrategy(llm, tools);
        ExecutionStrategy reflexion = new ReflexionStrategy(
                new KeywordEvaluator(List.of("最终答案")), react);
        BaseAgent agent = new BaseAgent("reflexion-agent", reflexion, 5);

        AgentResult result = agent.run(
                "请回答：你是什么类型的智能体？输出中必须包含'最终答案'四个字");
        return expectSuccess(result, "最终答案");
    }

    /** S6：短期记忆装饰器：窗口裁剪后流程仍可跑通。 */
    private static boolean scenarioShortTermMemory() {
        LlmClient llm = new RuleBasedLlmClient();
        ShortTermMemory shortTerm = new WindowShortTermMemory(8);
        LlmClient decorated = new MemoryAwareLlmClient(llm, shortTerm);
        ToolRegistry tools = new DefaultToolRegistry(List.of(new AddTool()));
        ExecutionStrategy strategy = new ReactStrategy(decorated, tools);
        BaseAgent agent = new BaseAgent("short-memory-agent", strategy, 5);

        AgentResult result = agent.run("请计算 3+4 的和");
        return expectSuccess(result, "根据工具返回的结果：计算结果：7");
    }

    // ---------- 辅助 ----------

    private static int runScenario(String name, Scenario scenario) {
        System.out.println("=== " + name + " ===");
        try {
            boolean ok = scenario.run();
            System.out.println(ok ? ">>> PASS" : ">>> FAIL");
            System.out.println();
            return ok ? 0 : 1;
        } catch (Exception e) {
            System.out.println(">>> FAIL (exception: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ")");
            System.out.println();
            return 1;
        }
    }

    private static boolean expectSuccess(AgentResult result, String mustContain) {
        if (!result.success()) {
            System.out.println("   result=FAILURE, reason=" + result.failureMessage());
            return false;
        }
        String output = result.output() == null ? "" : result.output();
        System.out.println("   output=" + output);
        if (mustContain != null && !mustContain.isEmpty() && !output.contains(mustContain)) {
            System.out.println("   expected to contain: " + mustContain);
            return false;
        }
        return true;
    }

    @FunctionalInterface
    private interface Scenario {
        boolean run();
    }
}
