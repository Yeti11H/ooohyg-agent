package com.ooohyg.agent.test;

import com.ooohyg.agent.core.base.BaseAgent;
import com.ooohyg.agent.core.capability.llm.LlmResponse;
import com.ooohyg.agent.core.capability.memory.LongMemoryRecord;
import com.ooohyg.agent.core.capability.memory.LongMemorySearchResult;
import com.ooohyg.agent.core.capability.memory.LongTermMemory;
import com.ooohyg.agent.core.capability.memory.MemoryAwareLlmClient;
import com.ooohyg.agent.core.capability.memory.MemoryRecallTool;
import com.ooohyg.agent.core.capability.memory.ShortTermMemory;
import com.ooohyg.agent.core.capability.retrieval.Document;
import com.ooohyg.agent.core.capability.retrieval.EmbeddingClient;
import com.ooohyg.agent.core.capability.retrieval.InMemoryVectorStore;
import com.ooohyg.agent.core.capability.retrieval.RetrievalTool;
import com.ooohyg.agent.core.capability.retrieval.SearchResult;
import com.ooohyg.agent.core.capability.retrieval.VectorStore;
import com.ooohyg.agent.core.capability.tool.DefaultToolRegistry;
import com.ooohyg.agent.core.capability.tool.ToolResult;
import com.ooohyg.agent.core.message.Message;
import com.ooohyg.agent.core.message.MessageRole;
import com.ooohyg.agent.core.message.ToolCall;
import com.ooohyg.agent.core.result.AgentResult;
import com.ooohyg.agent.strategy.react.ReactStrategy;
import com.ooohyg.agent.testutil.HashEmbeddingClient;
import com.ooohyg.agent.testutil.ScriptedLlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * ooohyg-agent 记忆与检索模块冒烟测试（纯 JDK main 方法，不依赖 JUnit）。
 *
 * <p>补充 {@link AgentFrameworkSmokeTest} 未覆盖的模块：
 * <ul>
 *   <li>T10 InMemoryVectorStore 向量检索（排序 / topK / 维度校验 / 同 id 覆盖）</li>
 *   <li>T11 RetrievalTool RAG 检索工具（知识库 → 查询 → 命中）</li>
 *   <li>T12 RetrievalTool 接入 ReAct 端到端（模型主动调 retrieval 工具）</li>
 *   <li>T13 MemoryAwareLlmClient 短期记忆装饰器（enrich 生效且不动入参）</li>
 *   <li>T14 MemoryRecallTool 长期记忆（复用 EmbeddingClient + VectorStore 的实现）</li>
 * </ul>
 *
 * <p>运行：java -cp &lt;classes&gt; com.ooohyg.agent.test.MemoryRetrievalSmokeTest
 */
public final class MemoryRetrievalSmokeTest {

    private static int passed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("========== ooohyg-agent 记忆与检索模块冒烟测试 ==========");
        System.out.println("环境: Java " + System.getProperty("java.version"));
        System.out.println();

        run("T10 InMemoryVectorStore 向量检索", MemoryRetrievalSmokeTest::test10_vectorStore);
        run("T11 RetrievalTool RAG 检索", MemoryRetrievalSmokeTest::test11_retrievalTool);
        run("T12 RetrievalTool 接入 ReAct 端到端", MemoryRetrievalSmokeTest::test12_retrievalInReact);
        run("T13 MemoryAwareLlmClient 短期记忆装饰器", MemoryRetrievalSmokeTest::test13_memoryAwareDecorator);
        run("T14 MemoryRecallTool 长期记忆", MemoryRetrievalSmokeTest::test14_memoryRecallTool);

        System.out.println();
        System.out.println("========== 汇总 ==========");
        System.out.println("通过: " + passed + "，失败: " + failures.size());
        if (!failures.isEmpty()) {
            System.out.println("失败明细:");
            for (String f : failures) {
                System.out.println("  - " + f);
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

    /** T10 InMemoryVectorStore：检索、排序、topK、维度校验、同 id 覆盖。 */
    private static void test10_vectorStore() {
        VectorStore store = new InMemoryVectorStore();
        HashEmbeddingClient emb = new HashEmbeddingClient();

        // 空库检索返回空
        assertTrue(store.search(emb.embed("anything"), 5).isEmpty(), "空库检索应返回空");

        // 灌入 3 条，按相同文本查询应精确命中
        store.add(List.of(
                Document.of("d1", "报销流程是什么"),
                Document.of("d2", "如何申请年假"),
                Document.of("d3", "报销需要哪些发票")),
                List.of(
                        emb.embed("报销流程是什么"),
                        emb.embed("如何申请年假"),
                        emb.embed("报销需要哪些发票")));

        List<SearchResult> hits = store.search(emb.embed("报销流程是什么"), 2);
        assertEquals(2, hits.size(), "topK=2 应返回 2 条");
        assertEquals("d1", hits.get(0).document().id(), "相同文本应最相似");
        assertTrue(hits.get(0).score() >= hits.get(1).score(), "score 应降序排列");

        // topK 超过总量时返回全部
        assertEquals(3, store.search(emb.embed("报销流程是什么"), 10).size(), "topK 超量应返回全部");

        // 维度不一致应抛异常
        try {
            store.add(List.of(Document.of("d4", "x")), List.of(List.of(1.0f, 2.0f)));
            throw new AssertionError("维度不一致应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("dimension"), "异常应说明维度问题");
        }

        // 同 id 覆盖语义
        store.add(List.of(Document.of("d1", "报销流程更新版")), List.of(emb.embed("报销流程更新版")));
        List<SearchResult> after = store.search(emb.embed("报销流程更新版"), 1);
        assertEquals(1, after.size(), "覆盖后仍只有 1 条 d1");
        assertEquals("d1", after.get(0).document().id(), "同 id 覆盖");
        assertEquals("报销流程更新版", after.get(0).document().content(), "覆盖后内容应为新内容");
    }

    /** T11 RetrievalTool：知识库检索工具直接执行。 */
    private static void test11_retrievalTool() {
        HashEmbeddingClient emb = new HashEmbeddingClient();
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(
                Document.of("r1", "公司团建时间是本周五下午"),
                Document.of("r2", "报销单需要财务总监签字")),
                List.of(
                        emb.embed("公司团建时间是本周五下午"),
                        emb.embed("报销单需要财务总监签字")));

        RetrievalTool tool = new RetrievalTool(emb, store);

        // 标准 JSON 参数
        ToolResult ok = tool.execute("{\"query\":\"团建时间\"}");
        assertTrue(ok.success(), "检索应成功");
        assertTrue(ok.output().contains("团建"), "结果应包含团建文档，实际: " + ok.output());

        // 无相关 query：向量库仍返回 topK 条候选（哈希夹具不保证语义区分度，
        // 这里只验证"低相关检索仍 success、不会失败"）
        ToolResult miss = tool.execute("{\"query\":\"完全无关内容xyz\"}");
        assertTrue(miss.success(), "低相关检索应 success");

        // 空库检索：应 success 并提示未命中（向量库对空库返回空列表）
        RetrievalTool emptyTool = new RetrievalTool(emb, new InMemoryVectorStore());
        ToolResult empty = emptyTool.execute("{\"query\":\"任意查询\"}");
        assertTrue(empty.success(), "空库结果应 success");
        assertTrue(empty.output().contains("No relevant"), "空库应提示未命中，实际: " + empty.output());

        // 空白参数应业务失败（注：extractQuery 对 "{}" 会整体当 query，
        // 只有空白/null 才会判 Empty query 失败——这是框架设计行为）
        ToolResult bad = tool.execute("");
        assertTrue(!bad.success(), "空白参数应失败");
        ToolResult badNull = tool.execute(null);
        assertTrue(!badNull.success(), "null 参数应失败");

        // 模型直接返回纯字符串参数也可用
        ToolResult plain = tool.execute("团建时间");
        assertTrue(plain.success() && plain.output().contains("团建"), "纯字符串 query 应可用");
    }

    /** T12 RetrievalTool 接入 ReAct：模型主动调 retrieval 工具并基于结果回答。 */
    private static void test12_retrievalInReact() {
        HashEmbeddingClient emb = new HashEmbeddingClient();
        InMemoryVectorStore store = new InMemoryVectorStore();
        store.add(List.of(Document.of("k1", "框架版本是 1.0.0")),
                List.of(emb.embed("框架版本是 1.0.0")));
        RetrievalTool rt = new RetrievalTool(emb, store);

        ScriptedLlmClient llm = new ScriptedLlmClient(List.of(
                new LlmResponse("让我查一下知识库",
                        List.of(new ToolCall("c1", "retrieval", "{\"query\":\"框架版本\"}")),
                        "tool_calls"),
                new LlmResponse("框架版本是 1.0.0", null, "stop")
        ));
        DefaultToolRegistry registry = new DefaultToolRegistry(List.of(rt));
        BaseAgent agent = new BaseAgent("rag-agent", new ReactStrategy(llm, registry), 5);

        AgentResult result = agent.run("框架版本是多少");
        assertTrue(result.success(), "RAG ReAct 端到端应成功");
        assertTrue(result.output().contains("1.0.0"), "输出应包含检索到的知识，实际: " + result.output());

        // 工具结果应作为 TOOL 消息喂回模型
        Message toolMsg = llm.calls().get(1).messages().stream()
                .filter(m -> m.role() == MessageRole.TOOL)
                .findFirst().orElse(null);
        assertTrue(toolMsg != null, "第 2 次调用应包含 TOOL 消息");
        assertTrue(toolMsg.content().contains("1.0.0"), "TOOL 消息应包含检索结果，实际: " + toolMsg.content());
    }

    /** T13 MemoryAwareLlmClient：短期记忆装饰器应生效且不修改入参。 */
    private static void test13_memoryAwareDecorator() {
        ScriptedLlmClient raw = new ScriptedLlmClient(List.of(new LlmResponse("ok", null, "stop")));
        MemoryAwareLlmClient client = new MemoryAwareLlmClient(raw, new TruncatingShortTermMemory());

        List<Message> msgs = new ArrayList<>(List.of(
                Message.system("sys"),
                Message.user("u1"),
                Message.assistant("a1"),
                Message.user("u2")));

        client.chat(msgs, List.of());

        assertEquals(1, raw.calls().size(), "底层客户端应被调用 1 次");
        List<Message> seen = raw.calls().get(0).messages();
        assertEquals(2, seen.size(), "记忆应把 4 条消息截断为 2 条");
        assertEquals(MessageRole.SYSTEM, seen.get(0).role(), "第一条应保留 SYSTEM");
        assertEquals("u2", seen.get(1).content(), "最后一条应保留最新 USER");

        // 入参不被修改
        assertEquals(4, msgs.size(), "入参列表不应被修改");
    }

    /** T14 MemoryRecallTool：长期记忆 remember → recall 工具执行。 */
    private static void test14_memoryRecallTool() {
        HashEmbeddingClient emb = new HashEmbeddingClient();
        VectorLongTermMemory ltm = new VectorLongTermMemory(emb, new InMemoryVectorStore());
        ltm.remember(LongMemoryRecord.of("m1", "用户偏好简洁回答"));
        ltm.remember(LongMemoryRecord.of("m2", "用户是 Java 开发者"));

        MemoryRecallTool tool = new MemoryRecallTool(ltm);

        ToolResult ok = tool.execute("{\"query\":\"用户偏好\"}");
        assertTrue(ok.success(), "回忆应成功");
        assertTrue(ok.output().contains("简洁"), "应召回偏好记忆，实际: " + ok.output());

        // 无相关 query：返回候选（哈希夹具不保证语义区分度，只验证仍 success）
        ToolResult miss = tool.execute("{\"query\":\"无任何相关的查询\"}");
        assertTrue(miss.success(), "低相关回忆应 success");

        // 空记忆库：应 success 并提示未命中
        MemoryRecallTool emptyTool = new MemoryRecallTool(
                new VectorLongTermMemory(emb, new InMemoryVectorStore()));
        ToolResult empty = emptyTool.execute("{\"query\":\"任意查询\"}");
        assertTrue(empty.success() && empty.output().contains("No relevant"),
                "空库应 success 提示未命中，实际: " + empty.output());

        ToolResult bad = tool.execute("{\"query\":\"\"}");
        assertTrue(!bad.success(), "空 query 应失败");
    }

    // ================= 测试实现类 =================

    /**
     * 【测试用途】基于 HashEmbeddingClient + InMemoryVectorStore 的
     * 内存版 LongTermMemory 实现——验证"复用向量检索基础设施实现
     * 长期记忆"这一设计路径可跑通。
     */
    static final class VectorLongTermMemory implements LongTermMemory {

        private final EmbeddingClient embeddingClient;
        private final VectorStore store;

        VectorLongTermMemory(EmbeddingClient embeddingClient, VectorStore store) {
            this.embeddingClient = embeddingClient;
            this.store = store;
        }

        @Override
        public void remember(LongMemoryRecord record) {
            store.add(List.of(new Document(record.id(), record.content(), record.metadata())),
                    List.of(embeddingClient.embed(record.content())));
        }

        @Override
        public List<LongMemorySearchResult> recall(String query, int topK) {
            List<SearchResult> hits = store.search(embeddingClient.embed(query), topK);
            List<LongMemorySearchResult> out = new ArrayList<>(hits.size());
            for (SearchResult hit : hits) {
                Document d = hit.document();
                out.add(new LongMemorySearchResult(
                        new LongMemoryRecord(d.id(), d.content(), d.metadata()),
                        hit.score()));
            }
            return out;
        }
    }

    /**
     * 【测试用途】极简窗口式短期记忆：超过 2 条时只保留首尾。
     */
    static final class TruncatingShortTermMemory implements ShortTermMemory {

        @Override
        public List<Message> enrich(List<Message> messages) {
            if (messages.isEmpty() || messages.size() <= 2) {
                return messages;
            }
            return List.of(messages.get(0), messages.get(messages.size() - 1));
        }
    }
}
