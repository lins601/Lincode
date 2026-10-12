# Lincode 项目简历介绍（源码核验版）

> 核验时间：2026-07-23
> 核验基线：当前工作区代码，Git HEAD `b7ee842665f9b9b4b1edd3dddbbfd04507015d45`
> 使用说明：正文按 Agent 岗位的面试价值排序；“负责”口吻只应在确实参与对应模块设计或开发时使用。

## 可直接粘贴到简历

### 项目名称

**Lincode - Java 智能编码 Agent CLI**

### 项目简介

Lincode 是 Java 17 实现的智能编码 Agent CLI，支持 ReAct 工具调用、DAG 规划执行和多 Agent 协作，并通过分层记忆、上下文压缩、代码 RAG 与 MCP 完成代码检索、文件修改和命令执行的可控闭环。

### 技术栈

Java 17、Maven、JLine 4、OkHttp/SSE、Jackson、SQLite/JDBC、JGit、JavaParser、Jieba、Jsoup、JUnit 5、Mockito、MockWebServer

### 负责功能 / 技术难点

1. 搭建 ReAct 循环与 Function Calling Agent 内核：每轮向 LLM 提交工具 Schema，解析 `tool_calls`，经统一注册中心执行后按调用 ID 回灌 `tool_result`，直到模型不再请求工具时自然结束；同时统计 input、output、cached token，以连续 3 轮同名同参调用检测停滞，并设置 50 轮硬上限和可配置硬 Token 预算，防止异常循环。

2. 实现 Plan-and-Execute 的 DAG 任务编排：Planner 将复杂目标输出为 JSON 任务计划，通过两遍解析处理任务 ID 和前向依赖，再以 DFS 拓扑排序检测循环依赖；运行时按依赖就绪批次推进，同批任务最多 4 路并行且输出按计划顺序归并，任务在计划进度低于 50% 时失败会携带失败原因和已完成任务描述触发重新规划。

3. 设计三层上下文与 Memory 体系：以 `conversationHistory` 维护实际发送给模型的工作上下文，以 `ConversationMemory` 保存当前会话消息、工具结果和摘要，以 `LongTermMemory` 将显式保存的稳定事实按项目级或全局作用域持久化到 JSON；短期层采用 Map-Reduce 压缩旧条目，主消息历史在 user 边界切分并保留最近 3 个用户轮次，避免破坏 `tool_call/tool_result` 配对。

4. 完善长上下文与 Token 预算控制：`ContextProfile` 按模型窗口动态计算容量，短期记忆预算为窗口的 45%，自动压缩为摘要输出最多预留 20k token，并最多额外保留 13k 安全缓冲；相关长期记忆按查询检索后注入 system prompt，注入上限为窗口的 0.5% 且不超过 5k token，避免长期对话和工具结果持续挤占上下文。

5. 统一工具调度与 MCP 扩展链路：`ToolRegistry` 注册 16 个内置工具，并在运行时挂载 `mcp__{server}__{tool}` 动态工具；同轮调用通过 `ExecutorService` 最多 4 路并发，批次默认 90 秒超时，并按原始顺序回传结果。MCP 侧实现 JSON-RPC 请求关联、stdio/Streamable HTTP 传输、Schema 规范化和 tools/resources/prompts 能力接入，启用 HITL 时外部工具统一进入审批。

6. 构建 Agentic Search 与代码 RAG：精确定位组合使用 ripgrep、Glob、`grep_code` 和 `read_file`，本地测试单次检索约 200ms；模糊语义查询由 `search_code` 兜底，使用 JavaParser 按文件、类和方法切块，将 Embedding 以 JSON 数组持久化到 SQLite，并融合内存余弦相似度与 Jieba 关键词匹配，千级代码块规模下本地检索耗时低于 100ms。

## 可按岗位替换的亮点

- 偏 Multi-Agent 岗位：扩展 Planner、2 个 Worker 和 Reviewer 的主从协作流程，使用 `BlockingQueue` 池化分配 Worker，并为并发步骤创建独立 Reviewer，避免共享对话历史产生竞争；Reviewer 驳回后将问题反馈给 Worker，单步骤最多重试 2 次。
- 偏 Agent 安全岗位：将 HITL、PathGuard、CommandGuard、脱敏审计和 Side-Git 快照串成高风险工具控制链；恢复工作区前先创建保护快照，且快照仓库独立于用户项目的 `.git`。
- 偏 Agent 平台岗位：使用 SQLite 持久化后台任务状态，通过事务条件更新竞争领取任务；进程重启后把遗留的运行中任务重新入队，并在取消任务时中断对应 Worker。

### 精简版项目描述

基于 Java 17 开发智能编码 Agent CLI，完成 ReAct + Function Calling 内核、Plan-and-Execute DAG 编排和三层 Memory 上下文工程；通过统一并行工具调度、MCP、代码 RAG、人工审批和 Side-Git 快照，形成从任务规划、工具执行到审查回滚的完整链路。

## 图片能力点的取舍结论

### 第一优先级：简历必须保留

- **ReAct + Function Calling**：Agent 岗位最常追问的核心调用闭环，能够展开工具 Schema、消息协议、停止条件和死循环保护。
- **Plan-and-Execute + DAG**：体现复杂任务拆解、依赖建模、拓扑排序、并发批次和失败重规划能力。
- **三层 Memory + 上下文工程**：体现工作上下文、短期记忆、长期记忆、摘要压缩和 Token 预算之间的边界与取舍。

### 第二优先级：建议作为核心亮点

- **统一并行工具调度 + MCP**：能讲清并发上限、结果顺序、超时取消、JSON-RPC、双传输和审批边界。
- **Agentic Search + RAG**：同时覆盖精确代码搜索和自然语言语义检索，并可带本地实测性能数据。
- **Multi-Agent 审查协作**：默认由 Planner、2 个 Worker 和 Reviewer 组成；Worker 通过阻塞队列池化分配，Reviewer 驳回后最多重试 2 次，适合回答多 Agent 如何避免共享状态竞争。

### 版面充足时再选用

- **HITL + Side-Git 回滚**：高风险工具审批、敏感字段脱敏审计、独立 JGit 快照仓库和恢复前保护快照，适合偏 Agent 安全岗位。
- **SQLite 持久化后台任务**：任务状态机、事务领取、重启后重新入队和取消中断，适合偏 Agent 平台或后端岗位。
- **NetworkPolicy + Web/Browser fallback**：SSRF 防护、网页正文抽取和 CDP 会话复用，适合强调联网 Agent 或 Browser Use 时替换其他条目。

### 不建议作为核心亮点

- JLine 命令补全、历史记录、语法高亮和终端 Banner 更适合作为产品完成度说明，不应挤占 Agent 核心能力。
- 多模型 Provider 数量属于适配工作，除非岗位明确要求模型网关，否则不如执行编排、Memory 和工具系统有区分度。

## 图片表述中需要修正的地方

| 图片中的说法 | 当前源码一致的说法 |
|---|---|
| Java 21 | Maven 编译目标为 Java 17 |
| 固定在 90% 上下文占用时压缩 | 根据模型窗口动态计算，预留摘要输出空间和安全缓冲；例如 200k 窗口约在 167k、1M 窗口约在 967k token 触发 |
| 9 个内置工具 + 60+ MCP 工具 | 当前注册 16 个内置工具；MCP 工具由运行时连接的服务动态决定，不应写固定数量 |
| 长期记忆使用 SQLite 或 Markdown | `LongTermMemory` 当前持久化为 `~/.lincode/memory/long_term_memory.json` |
| 自动提取事实进入长期记忆 | 当前规范是通过 `/save`、`save_memory` 或用户明确要求显式保存，不自动抽取长期事实 |
| Memory 使用 BM25 + 余弦检索 | Memory 使用 Jieba 关键词匹配；代码 RAG 使用余弦相似度与关键词混合检索，当前没有 BM25 实现 |
| Plan 子任务失败后下游统一标记 SKIP | Plan 模式会因依赖未满足停止推进；显式跳过失败依赖的下游步骤是 Multi-Agent 编排路径的行为 |

## 性能数据口径

- “精确代码检索约 200ms”和“千级代码块检索低于 100ms”来自用户提供的本地测试结果，可写入简历，但应保留“本地测试”限定。
- 当前仓库没有固化对应机器配置、数据集、预热次数和 P95/P99 的基准报告；面试前应准备测试环境与测量方法，不能将其表述为所有环境下的性能保证。

## 面试时应如实说明的能力边界

- “三层 Memory”是架构表达：工作上下文、短期结构化记忆和长期持久化记忆并非三个同构存储组件。
- ReAct 的硬 Token 预算默认不启用，只有显式配置 `lincode.react.token.budget` 时才强制终止；默认主要依赖模型自然结束、停滞检测和 50 轮硬上限。
- PathGuard、CommandGuard 和 HITL 属于应用层控制，项目尚未提供容器或虚拟机级执行沙箱。
- MCP 已实现 tools、resources、prompts、通知和手动重启等核心能力，但 OAuth、sampling 和服务自动恢复仍未交付。
- 代码索引会先清理项目数据再全量重建，不是增量索引；向量保存在 SQLite 中，余弦相似度由应用进程加载后计算。
- RAG 需要可用的 Ollama 或 OpenAI-compatible Embedding 服务；LSP、Runtime API、图片输入和微信 iLink 通道目前均应按 MVP 描述。

## 源码验证摘要

| 简历能力 | 主要源码 |
|---|---|
| ReAct、Function Calling 与执行预算 | `src/main/java/com/lincode/agent/Agent.java`、`src/main/java/com/lincode/agent/AgentBudget.java`、`src/main/java/com/lincode/llm/LlmClient.java` |
| Planner、DAG、拓扑排序与失败重规划 | `src/main/java/com/lincode/plan/Planner.java`、`src/main/java/com/lincode/plan/ExecutionPlan.java`、`src/main/java/com/lincode/agent/PlanExecuteAgent.java` |
| 三层上下文、短期与长期记忆 | `src/main/java/com/lincode/agent/Agent.java`、`src/main/java/com/lincode/memory/ConversationMemory.java`、`src/main/java/com/lincode/memory/LongTermMemory.java` |
| Map-Reduce 压缩与窗口预算 | `src/main/java/com/lincode/memory/ContextCompressor.java`、`src/main/java/com/lincode/memory/ConversationHistoryCompactor.java`、`src/main/java/com/lincode/context/ContextProfile.java`、`src/main/java/com/lincode/memory/TokenBudget.java` |
| 并行工具调度 | `src/main/java/com/lincode/tool/ToolRegistry.java` |
| MCP 协议、传输与 Schema | `src/main/java/com/lincode/mcp/jsonrpc/JsonRpcClient.java`、`src/main/java/com/lincode/mcp/transport/`、`src/main/java/com/lincode/mcp/protocol/McpSchemaSanitizer.java` |
| 精确代码搜索与 RAG | `src/main/java/com/lincode/tool/RipgrepCodeSearchEngine.java`、`src/main/java/com/lincode/rag/CodeIndex.java`、`src/main/java/com/lincode/rag/CodeRetriever.java`、`src/main/java/com/lincode/rag/VectorStore.java` |
| Multi-Agent 审查协作 | `src/main/java/com/lincode/agent/AgentOrchestrator.java`、`src/main/java/com/lincode/agent/SubAgent.java` |
| 审批、策略、审计与回滚 | `src/main/java/com/lincode/hitl/HitlToolRegistry.java`、`src/main/java/com/lincode/policy/`、`src/main/java/com/lincode/snapshot/` |

## 验证记录

- 仓库包含 208 个主代码 Java 文件和 119 个测试类。
- 针对 Agent、ToolRegistry、MCP、Memory、RAG、安全策略、Side-Git、Runtime API 和模型工厂执行的聚焦测试共 185 个，结果为 0 失败、0 错误。
- `mvn test -Pquick` 在本机 Java 26 环境下运行 739 个测试，出现 2 个失败和 38 个错误：38 个错误来自 Mockito/Byte Buddy 尚不支持 Java 26 下的 Terminal mock；2 个失败来自本机未启动 Ollama Embedding 服务，因此不能描述为全量 quick suite 已通过。

## 建议简历功能点（完成对应改造后可写）

- 建立可复现的代码搜索基准测试，记录项目规模、机器配置、冷热启动、P50/P95/P99 和召回正确率，为 200ms 与 100ms 指标提供仓库内证据。
- 将代码 RAG 改为基于文件指纹的增量索引，并加入固定问题集、引用命中率和 TopK 召回评测。
- 引入容器或虚拟机级命令沙箱，增加文件系统、网络和 CPU/内存配额隔离；补齐 MCP OAuth、sampling 与自动恢复。
