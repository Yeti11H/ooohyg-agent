# ooohyg-agent

一个基于 Java 21 的可插拔 Agent 执行框架。

支持 ReAct、Plan-and-Execute、Reflexion 三种执行策略，提供工具调用、RAG 检索、
记忆系统等能力。核心层零框架依赖，只使用 Java 标准库 + SLF4J。

---

## 特性

- **三种执行策略**：ReAct（思考-行动-观察）、Plan-and-Execute（先规划再执行）、Reflexion（评估-修正-重跑）
- **策略可嵌套组合**：`Reflexion(Plan(React))` 三层嵌套是框架推荐形态
- **可插拔的模型客户端**：`LlmClient` 是接口，适配 OpenAI / 通义 / Ollama 只需实现一次
- **可插拔的工具系统**：`Tool` + `ToolRegistry`，工具自由继承不受限
- **RAG 检索能力**：`EmbeddingClient` + `VectorStore` 抽象，提供 `InMemoryVectorStore` 示例实现
- **记忆系统**：短期记忆（对话压缩，装饰 LlmClient）+ 长期记忆（跨会话经验，作为 Tool 接入）
- **核心层零框架依赖**：core 只依赖 JDK 标准库 + SLF4J，不依赖 Spring、Redis、Kafka、MySQL
- **明确的异常契约**：技术故障走异常（`AgentException`），业务失败走返回值（`AgentResult.failure`）
- **状态机约束**：策略内部状态流转由枚举的 `canTransitionTo` 显式约束
- **可观测**：关键节点带 SLF4J 日志，所有日志携带 `executionId` 便于链路追踪

---

## 快速开始

### 开发工作流：从确认需求到可核验补丁

工作流复用 Plan 与 ReAct。轻量模式只确认 Plan；完整模式依次确认 Intent、Spec 和 Plan。
两种模式都固定基线提交，只有确认后才能写入任务自己的源码目录。运行状态和每版确认文档保存在本地。

首版可执行范围是内置订单分页样例。离线脚本只替代模型决策，实际修改 Java 文件，并用本机
JDK 编译、执行独立断言、生成 Git 补丁。公开 GitHub 导入是独立的只读入口，导入的仓库不执行构建、
不调用模型、不写源码。

用 JDK 21、Maven 和 Git 构建后，在 PowerShell 中执行：

```powershell
mvn package
$jar = 'target/ooohyg-agent-0.0.1-SNAPSHOT.jar'
java -jar $jar workflow start full order-list '订单列表太慢'
$run = '复制上一步输出的 Run ID'
java -jar $jar workflow answer $run 50
```

检查 Intent，复制当前 `Confirmation token`；按同样步骤确认
新出现的 Spec 和 Plan。每条命令可在新终端运行，`status` 会显示当前草案以及确认码。

```powershell
java -jar $jar workflow status $run
$token = '复制当前 Confirmation token'
java -jar $jar workflow confirm $run $token
# 重复确认 Spec 和 Plan，直到状态为 READY，再执行：
java -jar $jar workflow execute $run
```

离线完整模式的第一个补丁刻意漏掉上限：旧排序检查通过，但请求 51 条的检查失败；返修后重新编译和验证
默认 20 条、请求 49 / 50 / 51 条、非正数、顺序和导出。轻量模式记录检查结果，不自动返修。
`patch.diff` 是最终补丁；`checks.json` 保存命令、退出码和逐轮结果；
`acceptance.md` 单独报告已验证行为、审查状态和待人工处理项。无 DSH 审查时，完整模式的报告会显示
`Acceptance incomplete`。中断后的再次执行会记录待检查状态，保留现有文件，不重复写入；检查后可启动新任务。
默认产物目录为 `.agent-workflow/`，也可用 `WORKFLOW_HOME` 指定。

轻量模式将第一条命令的 `full` 改成 `light`，回答页大小后只确认 Plan，然后执行。
已通过独立检查的完整 `order-list` 任务会生成 Wiki 提案。人工检查后，使用
`workflow wiki-publish <RUN_ID> <WIKI_APPROVAL_TOKEN>` 保存规则。再运行
`workflow start light order-history '给历史订单增加分页' --wiki`，可以看到上限 50 进入计划，并追溯到原任务和来源。
同题去掉 `--wiki` 时会停在澄清阶段；两组使用相同的样例基线，其中订单列表已经包含上限 50。
`workflow start light export '调整后台导出' --wiki` 显示该规则不适用于导出。来源内容变更时条目标为待检查。

公开仓库可用 `workflow import https://github.com/OWNER/REPO` 导入，随后用
`workflow read <IMPORT_ID> README.md` 阅读选定提交上的文件。这里只支持标准公开 HTTPS 地址和小型仓库，
不执行 Git hooks、子模块或仓库脚本。CLI 不支持私库、任意仓库补丁、新文件生成或自动发布。

可选外部服务需要显式开启：

- 任务创建时加 `--model=NAME`，并在本机设置 `WORKFLOW_CHAT_URL`（完整 HTTPS Chat Completions 地址）
  和 `WORKFLOW_CHAT_KEY`。模型读取内置样例后，可提交默认页大小与分页表达式来修改获批方法。
  表达式只允许 `limit`、非负整数、括号和 `Math.min` / `Math.max`；后端解析后生成源码，
  独立编译和验收才判断行为是否正确。工作流仍采用模板生成 Intent、Spec、Plan。
- 任务创建时加 `--dsh`。配置 `DEEPSEEK_API_KEY`；Windows 上同时用 `WORKFLOW_DSH_SCRIPT` 指向本机可信的
  DSH CLI `.mjs` 入口，其他系统可用 PATH 上的 `dsh-review`。每轮审查使用已提交的独立候选快照。
  审查报告或快照不匹配时标为未完成；只有独立检查确认的已知边界问题才自动返修，其他发现由人工判断。

模型服务和 DSH 都是可选集成；无需凭据即可重跑离线样例。工作流回归包括审批失效、断点保护、拒绝越界编辑、
模型表达式生成补丁、失败后返修、Wiki 适用范围及 DSH 报告校验：

```powershell
mvn '-Dtest=DevelopmentWorkflowTest' test
```

### 依赖

- Java 21
- Maven 3.9+
- SLF4J 实现（如 logback-classic 或 slf4j-simple）

### 构建

```bash
mvn -q compile
```

### 最小示例：纯 ReAct

```java
// 1. 准备能力（adapter 层实现，目前需要自己写）
LlmClient llm = new YourLlmClient(...);
ToolRegistry registry = new DefaultToolRegistry(List.of(new EchoTool()));

// 2. 装配策略
ExecutionStrategy strategy = new ReactStrategy(llm, registry);

// 3. 创建 Agent 并执行
BaseAgent agent = new BaseAgent("my-agent", strategy, 5);
AgentResult result = agent.run("帮我回显 hello");

System.out.println(result.success() ? result.output() : result.failureMessage());
```

### 带 RAG 的 ReAct

```java
// 准备嵌入模型和向量库
EmbeddingClient embedder = new YourEmbeddingClient(...);  // adapter 层
VectorStore store = new InMemoryVectorStore();

// 离线灌入知识
List<Document> docs = List.of(
        Document.of("doc-1", "Keep 会员分为基础会员和高级会员..."),
        Document.of("doc-2", "训练打卡每日最多计入一次...")
);
List<List<Float>> vectors = embedder.embedBatch(
        docs.stream().map(Document::content).toList()
);
store.add(docs, vectors);

// 把 RAG 包装成工具，接入 ReAct
ToolRegistry registry = new DefaultToolRegistry(List.of(
        new EchoTool(),
        new RetrievalTool(embedder, store)
));
ExecutionStrategy strategy = new ReactStrategy(llm, registry);
```

### 带短期记忆的 ReAct

```java
// 用户自己实现 ShortTermMemory（例如摘要压缩）
ShortTermMemory memory = new YourSummarizingMemory(llm);

// 用装饰器包住 LlmClient
LlmClient clientWithMemory = new MemoryAwareLlmClient(llm, memory);

// 策略拿到的还是 LlmClient 接口，代码不变
ExecutionStrategy strategy = new ReactStrategy(clientWithMemory, registry);
```

### 带长期记忆的 ReAct

```java
// 用户自己实现 LongTermMemory（可复用 EmbeddingClient + VectorStore）
LongTermMemory longTermMemory = new YourVectorLongTermMemory(embedder, store);

ToolRegistry registry = new DefaultToolRegistry(List.of(
        new EchoTool(),
        new RetrievalTool(embedder, store),
        new MemoryRecallTool(longTermMemory),
        new MemoryRememberTool(longTermMemory)
));
```

### 策略嵌套

```java
ExecutionStrategy react = new ReactStrategy(llm, registry);
ExecutionStrategy plan = new PlanAndExecuteStrategy(llm, planParser, react);
ExecutionStrategy reflexion = new ReflexionStrategy(evaluator, plan);

BaseAgent agent = new BaseAgent("complex-agent", reflexion, 20);
AgentResult result = agent.run("帮我完成一个复杂任务");
```

---

## 项目结构

```
src/main/java/com/ooohyg/agent/
├── core/                             核心层：只定义接口、领域模型、状态机
│   ├── base/                         AgentState, BaseAgent
│   ├── execution/                    ExecutionId, AgentContext, ExecutionStatus
│   ├── message/                      Message, MessageRole, ToolCall
│   ├── capability/                   能力接口与数据载体
│   │   ├── llm/                      LlmClient, LlmResponse
│   │   ├── tool/                     Tool, ToolDefinition, ToolResult,
│   │   │                             ToolRegistry, DefaultToolRegistry
│   │   │   └── builtin/              EchoTool
│   │   ├── retrieval/                Document, SearchResult,
│   │   │                             EmbeddingClient, VectorStore,
│   │   │                             InMemoryVectorStore, RetrievalTool
│   │   └── memory/                   ShortTermMemory, MemoryAwareLlmClient,
│   │                                 LongMemoryRecord, LongMemorySearchResult,
│   │                                 LongTermMemory, MemoryRecallTool,
│   │                                 MemoryRememberTool
│   ├── strategy/                     StrategyType, ExecutionStrategy
│   ├── result/                       AgentResult
│   └── exception/                    AgentException
└── strategy/                         三个策略的具体实现
    ├── react/                        ReactState, ReactStrategy
    ├── plan/                         PlanState, PlanStep, PlanParser,
    │                                 PlanAndExecuteStrategy
    └── reflexion/                    ReflexionState, EvaluationResult,
                                      Evaluator, ReflexionStrategy
```

**依赖方向**：`strategy → core`，`core` 不反向依赖任何策略。core 内的数据类不依赖行为类。

---

## 核心概念

### BaseAgent

Agent 执行容器。只做四件事：

1. 管理生命周期状态（`AgentState`）
2. 创建执行上下文（`AgentContext`）
3. 把执行委托给策略（`ExecutionStrategy`）
4. 统一处理异常并推进状态

**BaseAgent 不写循环、不调模型、不调工具。** 循环由策略内部驱动。这是策略模式相对于模板方法模式的核心优势——一个 `BaseAgent` 服务所有策略。

### ExecutionStrategy

三种执行范式的统一契约。`BaseAgent` 只依赖它，不依赖任何具体策略。新增策略不需要改 `BaseAgent`。

### 三个策略

| 策略 | 核心循环 | 组合方式 |
|------|---------|---------|
| **ReAct** | think → act → observe | 直接调模型和工具 |
| **Plan-and-Execute** | plan → step-by-step execute | 把每步委派给子策略 |
| **Reflexion** | evaluate → revise → re-execute | 包裹一个子策略，评估其输出 |

### 能力接口

| 接口 | 用途 | adapter 层实现 |
|------|------|---------------|
| `LlmClient` | 调模型 | OpenAI / Anthropic / Ollama 客户端 |
| `Tool` | 一次工具调用 | HTTP 请求、数据库查询等 |
| `ToolRegistry` | 按 name 查找工具 | `DefaultToolRegistry`（已在 core） |
| `EmbeddingClient` | 文本 → 向量 | OpenAI / BGE / 通义嵌入客户端 |
| `VectorStore` | 向量存储与检索 | pgvector / Milvus / `InMemoryVectorStore` |
| `ShortTermMemory` | 短期记忆（对话压缩） | 摘要压缩 / 窗口裁剪 |
| `LongTermMemory` | 长期记忆（跨会话经验） | 向量实现 / 数据库实现 |
| `PlanParser` | 解析模型输出的计划 | JSON / Markdown 解析器 |
| `Evaluator` | 评估子策略结果 | LLM 评估器 / 规则评估器 |

---

## 设计决策

核心决策记录在各类的 Javadoc 里，这里列最重要的几条。

### 为什么用策略组合而不是继承

`BaseAgent` 持有 `ExecutionStrategy`，不继承。原因：

- 三种策略的循环毫无共同点，抽象类放不下东西
- 嵌套组合（`Reflexion(Plan(React))`）用继承无法表达
- 新增策略不改 `BaseAgent` 一行代码

### 为什么异常分两条通道

- **技术故障**（网络超时、模型 5xx、工具内部崩溃）→ 抛 `AgentException`，由 `BaseAgent` 转 `ERROR` 状态
- **业务失败**（达到步数上限、模型判定无解、工具未找到）→ 返回 `AgentResult.failure(...)`，正常流转到 `FINISHED`

判据：**模型换参数能不能解决？** 能 → 业务失败；不能 → 技术故障。

### 为什么短期记忆用装饰器，长期记忆用工具

- **短期记忆**：每次调模型前都要加工消息列表，模型无感 → 用 `MemoryAwareLlmClient` 装饰 `LlmClient`，策略代码一行不改
- **长期记忆**：检索需要 query，是否相关由模型判断，不能自动触发 → 包成 `Tool`，模型主动调

这与 RAG 的接入方式一致——RAG 也是工具，让模型主动决定要不要检索。

### 为什么 `Document` / `LongMemoryRecord` 分开

结构相同（id + content + metadata），但语义不同——一个是“外部知识”，一个是“自身经历”。共用会让未来字段分叉时两个领域互相污染。

### 为什么 core 层不依赖 Spring / Redis / Kafka

core 被打包进任何项目时，不应拖上这些框架。具体技术实现全部放 adapter 模块（未来拆出）。Spring 装配 core 组件的方式是构造器注入——core 代码里一行 Spring 都没有，但完全能被 Spring 装配。

---

## 当前进度

### 已完成

- **核心层**：状态机、执行上下文、消息模型、结果 / 异常、能力接口
- **工具子系统**：`Tool` / `ToolDefinition` / `ToolResult` / `ToolRegistry` / `DefaultToolRegistry` / `EchoTool`
- **三个策略**：ReAct、Plan-and-Execute、Reflexion
- **RAG 能力**：`EmbeddingClient` / `VectorStore` / `Document` / `SearchResult` / `InMemoryVectorStore` / `RetrievalTool`
- **记忆系统**：短期记忆装饰器 + 长期记忆接口 + Tool 包装器
- **日志**：`BaseAgent` 与三个策略均带 SLF4J 日志，携带 `executionId`

### 未完成

- **adapter 模块**：真实 `LlmClient` 实现、`EmbeddingClient` 实现、`VectorStore` 实现
- **端到端测试**：目前还没有跑通整套拼装的集成测试
- **沙箱**：进程隔离、资源限制（等出现代码执行工具需求）
- **任务恢复**：Checkpoint 与状态重放（等出现长任务需求）
- **可靠事件处理**：事件模型与发布 / 订阅（等出现订阅方）
- **多模块拆分**：目前是单模块，未来拆 `agent-core` / `agent-adapter-*` / `agent-runtime`

---

## 硬约束

以下约束在 core 层不可违反：

- 只依赖 Java 标准库 + SLF4J
- 不依赖 Spring AI、Redis、Kafka、MySQL
- core 只定义接口、领域模型和状态机
- 具体技术实现放 adapter 模块
- 依赖倒置：`strategy` 可以 import `core`，`core` 不能 import `strategy`
- 每个类都必须能回答：“它的调用方是谁？”

---

## 许可证

（待补充）
