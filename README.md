# ooohyg-agent

一个基于 Java 21 的可插拔 Agent 执行框架。

支持 ReAct、Plan-and-Execute、Reflexion 三种执行策略，提供工具调用、
多策略嵌套、可插拔的模型客户端与评估器。核心层零框架依赖，
只使用 Java 标准库 + SLF4J。

---

## 特性

- **三种执行策略**：ReAct（思考-行动-观察循环）、Plan-and-Execute（先规划再执行）、Reflexion（评估-修正-重跑）
- **可插拔的模型客户端**：`LlmClient` 是接口，适配 OpenAI、Anthropic、通义千问、Ollama 等只需实现一次
- **可插拔的工具系统**：`Tool` + `ToolRegistry`，工具的自由继承不受限（接口而非抽象类）
- **策略可嵌套**：`Reflexion(Plan(React))` 三层嵌套是框架的推荐形态
- **核心层零框架依赖**：core 只依赖 JDK 标准库，不依赖 Spring、Redis、Kafka、MySQL
- **明确的异常契约**：技术故障走异常（`AgentException`），业务失败走返回值（`AgentResult.failure`）
- **状态机约束**：每个策略内部状态流转由枚举的 `canTransitionTo` 显式约束，非法流转编译期可见

---

## 快速开始

### 依赖

- Java 21
- Maven 3.9+

### 构建

```bash
mvn -q compile
```

### 最小示例

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

### 策略嵌套

```java
// 纯 ReAct
ExecutionStrategy react = new ReactStrategy(llm, registry);

// 先规划，每步用 ReAct 执行
ExecutionStrategy plan = new PlanAndExecuteStrategy(llm, parser, react);

// 反思 + 规划 + 反应（三层嵌套）
ExecutionStrategy reflexion = new ReflexionStrategy(evaluator, plan);

BaseAgent agent = new BaseAgent("complex-agent", reflexion, 20);
AgentResult result = agent.run("帮我完成一个复杂任务");
```

---

## 项目结构

```
src/main/java/com/ooohyg/agent/
├── core/                       核心层：只定义接口、领域模型、状态机
│   ├── base/                   AgentState, BaseAgent
│   ├── agent/                  （已合并到 base）
│   ├── execution/              ExecutionId, AgentContext, ExecutionStatus
│   ├── message/                Message, MessageRole, ToolCall
│   ├── capability/             能力接口与数据载体
│   │   ├── llm/                LlmClient, LlmResponse
│   │   └── tool/               Tool, ToolDefinition, ToolResult, ToolRegistry
│   ├── strategy/               StrategyType, ExecutionStrategy
│   ├── result/                 AgentResult
│   └── exception/              AgentException
└── strategy/                   三个策略的具体实现
    ├── react/                  ReactState, ReactStrategy
    ├── plan/                   PlanState, PlanStep, PlanParser, PlanAndExecuteStrategy
    └── reflexion/              ReflexionState, EvaluationResult, Evaluator, ReflexionStrategy
```

**依赖方向**：`strategy → core`，`core` 不反向依赖任何策略。`core` 内的数据类不依赖行为类。

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

三种执行范式的统一契约。`BaseAgent` 只依赖它，不依赖任何具体策略。

策略由构造器注入，`BaseAgent` 完全不 import 任何具体策略类。新增策略不需要改 `BaseAgent`。

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
| `Tool` | 一次工具调用 | HTTP 请求、数据库查询、文件操作等 |
| `ToolRegistry` | 按 name 查找工具 | `DefaultToolRegistry`（内存实现，已在 core） |
| `PlanParser` | 解析模型输出的计划 | JSON / Markdown 解析器 |
| `Evaluator` | 评估子策略结果 | LLM 评估器 / 规则评估器 |

---

## 设计决策

核心决策都记录在各类的 Javadoc 里，这里列最重要的几条：

### 为什么用策略组合而不是继承

`BaseAgent` 持有 `ExecutionStrategy`，不继承。原因：

- 三种策略的循环毫无共同点，抽象类放不下东西
- 嵌套组合（`Reflexion(Plan(React))`）用继承根本无法表达
- 新增策略不改 `BaseAgent` 一行代码

### 为什么异常分两条通道

- **技术故障**（网络超时、模型 5xx、工具内部崩溃）→ 抛 `AgentException`，由 `BaseAgent` 转 `ERROR` 状态
- **业务失败**（达到步数上限、模型判定无解、工具未找到）→ 返回 `AgentResult.failure(...)`，正常流转到 `FINISHED`

判据：**模型换参数能不能解决？** 能 → 业务失败；不能 → 技术故障。

### 为什么 `Message` 与 `AgentContext` 完全独立

`AgentContext` 只承载"执行前确定、执行期间不变"的环境信息（id、输入、预算）。消息历史属于策略内部状态，由策略自己维护。两者不互相引用。

### 为什么 core 层不依赖 Spring / Redis / Kafka

core 被打包进任何项目时，不应拖上这些框架。具体技术实现全部放 adapter 模块（未来拆出）。Spring 装配 core 组件的方式是构造器注入——core 代码里一行 Spring 都没有，但完全能被 Spring 装配。

---

## 当前进度

### 已完成

- 核心层：状态机、执行上下文、消息模型、结果/异常、能力接口
- 工具子系统：`Tool` / `ToolDefinition` / `ToolResult` / `ToolRegistry` / `DefaultToolRegistry` / `EchoTool`
- 三个策略：ReAct、Plan-and-Execute、Reflexion

### 未完成

- **adapter 模块**：真实 `LlmClient` 实现（OpenAI / Ollama 等）、真实工具
- **记忆系统**：短期记忆（对话压缩）、长期记忆（向量存储）
- **RAG**：`EmbeddingClient` / `VectorStore` / `Retriever` 接口与实现
- **沙箱**：进程隔离、资源限制
- **任务恢复**：Checkpoint 与状态重放
- **可靠事件处理**：事件模型与发布/订阅
- **多模块拆分**：目前是单模块，未来拆 `agent-core` / `agent-adapter-*` / `agent-runtime`

---

## 硬约束

以下约束在 core 层不可违反：

- 只依赖 Java 标准库 + SLF4J
- 不依赖 Spring AI、Redis、Kafka、MySQL
- core 只定义接口、领域模型和状态机
- 具体技术实现放 adapter 模块
- 依赖倒置：`strategy` 可以 import `core`，`core` 不能 import `strategy`
- 每个类都必须能回答："它的调用方是谁？"

---

## 许可证

（待补充）