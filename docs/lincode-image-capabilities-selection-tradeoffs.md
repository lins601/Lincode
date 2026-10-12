# Lincode 图片能力逐条分析：实现、选型、对比与取舍

> 文档基线：2026-07-23 当前工作区源码
>
> 对照图片：`截屏2026-07-23 18.36.57.png`、`截屏2026-07-23 18.37.40.png`
>
> 目标：逐条解释图片里的描述在项目中对应什么、为什么这样设计、还可以怎样做，以及不同方案如何取舍

## 0. 阅读方式

下面每一条都使用相同结构：

1. **图片表达**：图片想说明什么。
2. **源码结论**：当前代码到底实现到了什么程度。
3. **对应代码**：从哪些类开始阅读。
4. **实现流程**：功能在运行时怎样工作。
5. **为什么这样选**：结合本地 Java Agent CLI 的业务约束解释选型。
6. **方案对比与取舍**：与常见替代方案比较。
7. **边界与正确表述**：指出不能夸大的部分，并给出适合讲项目或面试的说法。

文中的“源码结论”是事实；“为什么这样选”属于基于代码和产品定位做出的设计分析。

## 1. 先纠正图片中与当前源码不一致的地方

图片来自项目较早阶段或简历草稿，其中一些数字和描述已经过期。学习和面试时应以当前代码为准。

| 图片说法 | 当前源码事实 | 建议表述 |
|---|---|---|
| Java 21 | `pom.xml` 编译基线是 Java 17 | Java 17+ |
| 上下文达到固定 90% 时压缩 | 当前按 `window - summaryReserve - buffer` 动态计算，大窗口分别预留 20K 和 13K | 基于模型窗口动态计算压缩阈值 |
| Agent 动态 Token 预算为窗口的 90% | ReAct 硬 Token 上限默认不限制；上下文压缩和 Agent 退出预算是两套机制 | 动态上下文预算 + 可配置 Agent 硬预算 |
| 长期记忆用 SQLite 或 Markdown | 长期记忆默认持久化到 `long_term_memory.json`；Markdown 是 `PAI.md` 项目记忆 | JSON 长期记忆 + Markdown 项目规则 |
| Memory 使用 BM25 + 余弦相似度 | Memory 使用关键词匹配；RAG 使用 Jieba + SQL LIKE + 向量余弦混合检索，没有 BM25 实现 | 关键词记忆检索 + RAG 混合检索 |
| ToolRegistry 有 9 个内置工具 | 当前注册 16 个内置工具入口 | 16 个内置工具 + 动态 MCP 工具 |
| 60+ MCP 外部工具 | MCP 工具数取决于用户实际配置的 Server，源码没有固定 60+ | 支持动态接入任意已配置 MCP 工具 |
| Plan 任务失败后下游自动 `SKIP` | Multi-Agent 会显式提示下游跳过；Plan 模式当前主要停止推进或重新规划 | 区分 Plan 与 Multi-Agent 的失败传播 |
| Multi-Agent 最大并行 4 线程 | 工具和 Plan 批次上限 4；Multi-Agent 默认只有两个 Worker | 不同执行层分别说明并发上限 |
| 自动提取事实到长期记忆 | `extractFacts` 方法存在，但当前主流程不自动调用；长期记忆需要用户明确保存 | 显式长期记忆，避免错误事实自动持久化 |
| `web_fetch` 失败后 Java 自动切浏览器 | 主要由 System Prompt 和 `web-access` Skill 引导 LLM 改用浏览器 MCP | 模型驱动的 fallback 策略 |
| SearchProvider 支持运行时热切换 | 当前在首次使用时创建并缓存；支持启动时自动选择和显式配置，不是真正无重启热切换 | Provider 策略抽象 + 配置选择 |
| SQLite 向量检索必然 `<100ms` | 这是用户提供的本地测试参考，不是仓库内稳定基准 | 在指定规模和机器下的本地测试结果 |

## 2. 图片顶部：项目定位

### 2.1 “类似 Claude Code 的终端 Agent CLI”

**图片表达**

Lincode 是一个面向编程任务的终端 Agent，支持 ReAct、Multi-Agent、MCP、Memory 和 RAG，用户可以用自然语言驱动代码开发和调试。

**源码结论**

这个定位准确。Lincode 不是单纯的聊天 CLI，而是一个能读取代码、调用工具、修改文件、执行命令并依据结果继续推理的本地编程 Agent。当前还有 Plan、浏览器、Skill、Runtime API、图片输入和微信文本通道等能力。

**对应代码**

- `src/main/java/com/lincode/cli/Main.java`
- `src/main/java/com/lincode/agent/Agent.java`
- `src/main/java/com/lincode/agent/PlanExecuteAgent.java`
- `src/main/java/com/lincode/agent/AgentOrchestrator.java`
- `src/main/java/com/lincode/tool/ToolRegistry.java`

**实现流程**

用户输入先由 `Main` 解析。普通输入进入 ReAct，`/plan` 进入 Plan-and-Execute，`/team` 进入 Multi-Agent。Agent 把 System Prompt、对话历史和工具 Schema 发给模型；模型通过 Function Calling 选择工具；Java 执行工具后把结果回灌给模型，直到模型输出最终答案。

**为什么这样选**

编码工作发生在文件系统、终端、Git、网页和浏览器中。只让模型生成文本无法形成闭环，因此产品必须同时包含：

- 决策层：大模型。
- 执行层：本地工具和 MCP。
- 上下文层：项目文件、Memory、RAG、Skill。
- 控制层：HITL、策略、审计和快照。
- 交互层：终端 Renderer。

**方案对比与取舍**

| 方案 | 优点 | 缺点 | 适用情况 |
|---|---|---|---|
| 普通聊天机器人 | 实现简单、风险低 | 无法验证和完成真实修改 | 知识问答 |
| IDE 插件 | 能结合编辑器上下文 | 绑定 IDE，终端和自动化场景弱 | 日常 IDE 开发 |
| 终端 Agent CLI | 接近代码、Shell、Git，容易自动化 | 终端 UI 和本地安全更难 | Lincode 的目标场景 |
| 远程 SaaS Agent | 易集中运维 | 代码隐私、环境复现和权限更复杂 | 团队平台化 |

**边界与正确表述**

Lincode 可以表述为“对标 Claude Code 交互思路的 Java Agent CLI”，但不应声称功能、稳定性或沙箱能力与 Claude Code 完全等价。

## 3. 图片顶部：技术栈如何选

### 3.1 Java 17，而不是图片中的 Java 21

**图片表达**

项目主体使用 Java。

**源码结论**

`pom.xml` 的 `maven.compiler.source` 和 `target` 都是 17，因此准确表述是 Java 17+。在更高 JDK 上可以运行，不代表项目使用了 Java 21 语法或特性。

**对应代码**

- `pom.xml`
- 整个 `src/main/java/com/lincode/`

**实现流程**

Java 承担 CLI 生命周期、模型协议、工具调度、并发、SQLite、MCP、浏览器会话和安全策略。Maven Shade 将依赖打进可执行 Jar。

**为什么这样选**

Java 17 是 LTS，适合需要长期维护的完整产品。强类型对 JSON-RPC、Function Calling、任务状态和并发结构更友好，也方便把 Agent 能力与传统 Java 工程结合。

**方案对比与取舍**

| 语言 | 优势 | 相比 Java 的代价 |
|---|---|---|
| Python | AI 生态丰富、原型快 | 大型协议和并发状态更依赖运行时约束 |
| TypeScript | JSON/前端生态自然 | JVM 企业项目集成能力较弱 |
| Go | 单文件部署、并发模型简单 | LLM/Java 代码分析生态不同 |
| Java 17 | LTS、类型安全、企业生态成熟 | 样板代码和启动体积通常更大 |

**边界与正确表述**

不要在简历中写 Java 21，除非先修改 `pom.xml` 并验证所有构建与测试。

### 3.2 JLine 4

**图片表达**

用 JLine 实现终端交互。

**源码结论**

准确。JLine 负责 LineReader、历史、补全、高亮、状态区域和终端能力；项目另外保留 Lanterna 和 Plain Renderer。

**对应代码**

- `cli/Main.java`
- `cli/LincodeHistory.java`
- `cli/LincodeCompleter.java`
- `cli/LincodeHighlighter.java`
- `render/inline/InlineRenderer.java`
- `render/inline/BottomStatusBar.java`

**实现流程**

启动时创建 `Terminal -> LineReader -> Renderer`。JLine 接收按键并维护输入行；Lincode 注入补全器、高亮器和历史实现；Renderer 把流式 reasoning、工具状态和最终回答输出到输入行上方，并维护底部状态栏。

**为什么这样选**

终端编辑并不是简单的 `Scanner.nextLine()`：还要处理方向键、中文宽度、ANSI、光标、重绘、历史和异步输出。JLine 已解决大量跨终端细节。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| `Scanner` / `BufferedReader` | 极简 | 无编辑、历史、补全和稳定重绘 |
| 手写 ANSI | 自由度高 | CJK 宽度、终端兼容和状态同步成本高 |
| JLine | 适合现代交互 CLI | 需要理解 LineReader 与终端重绘模型 |
| Lanterna | 适合全屏 TUI | 与普通 shell transcript 的体验不同 |

**边界与正确表述**

JLine 解决输入和终端基础设施，Lincode 自己仍要负责“异步 Agent 输出不能覆盖用户输入”等产品级问题。

### 3.3 SQLite

**图片表达**

SQLite 用于本地存储。

**源码结论**

准确，但不是所有数据都放 SQLite。它用于 RAG、后台任务和 Runtime API；长期记忆使用 JSON，配置和 MCP 也有自己的文件格式。

**对应代码**

- `rag/VectorStore.java`
- `runtime/task/DurableTaskManager.java`
- `runtime/api/RuntimeThreadStore.java`

**实现流程**

JDBC 直接打开用户目录下的数据库文件，通过建表、索引和事务完成代码块、任务状态、Thread 与事件持久化。

**为什么这样选**

本地 CLI 最重要的是零运维。SQLite 不需要数据库服务，既有事务又是单文件，比纯 JSON 更适合状态更新和条件查询。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| JSON 文件 | 易读易调试 | 并发更新、事务和查询弱 |
| SQLite | 零服务、事务、SQL | 高并发和分布式能力有限 |
| Redis | 队列与并发强 | 需要额外服务，重启持久化需配置 |
| PostgreSQL | 功能和扩展性强 | 对单机 CLI 过重 |

**边界与正确表述**

SQLite 是产品形态驱动的选择，不代表它在任何规模都优于服务型数据库。

### 3.4 JGit

**图片表达**

项目使用 JGit。

**源码结论**

JGit 主要用于 Side-Git 快照与恢复，不是普通业务仓库 Git 操作的替代品。

**对应代码**

- `snapshot/SideGitManager.java`
- `snapshot/SnapshotService.java`

**实现流程**

Lincode 在用户项目 `.git` 之外创建旁路 Git 仓库，任务前后记录快照。恢复前再保存一次当前状态，之后根据快照内容恢复文件。

**为什么这样选**

Agent 会改真实文件，但不能随意改用户分支、暂存区和提交记录。旁路 Git 既利用内容寻址和增量对象，又避免污染用户仓库。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 直接 `git commit` | 简单 | 污染用户历史，未初始化 Git 时不可用 |
| 全量复制目录 | 容易理解 | 空间大、速度慢 |
| 文件补丁日志 | 轻量 | 二进制、删除和复杂恢复较难 |
| Side-Git + JGit | 增量、纯 Java、不污染用户 Git | 实现和边界处理更复杂 |

**边界与正确表述**

Side-Git 是 Agent 修改恢复机制，不是进程沙箱，也不能替代远程备份。

### 3.5 Chrome DevTools Protocol

**图片表达**

项目可以控制浏览器并复用登录态。

**源码结论**

准确方向是“通过 chrome-devtools MCP 使用 CDP”，而不是 Lincode 自己实现完整 CDP 客户端。

**对应代码**

- `browser/BrowserSession.java`
- `browser/BrowserGuard.java`
- `cli/Main.java` 中 `/browser` 处理
- `mcp/McpServerManager.java`

**实现流程**

Lincode 通过 MCP 启动或重启 `chrome-devtools` Server；isolated 模式使用临时浏览器数据目录，shared 模式通过 `--autoConnect` 或指定浏览器 URL 连接本机 Chrome。页面操作由动态 MCP 工具完成。

**为什么这样选**

直接实现 CDP 需要维护大量协议域和 Chrome 版本兼容。复用现成 MCP Server 可以让 Lincode 专注 Agent、会话和安全控制。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 原生 CDP 客户端 | 控制力最强 | 协议面巨大、维护成本高 |
| Selenium | 测试生态成熟 | 对 Agent 的 DOM 快照和工具生态不够自然 |
| Playwright | API 完整、自动等待 | 需要额外运行时和自身会话模型 |
| Chrome DevTools MCP | 与 Agent 工具协议统一 | 依赖外部 Server，能力受其实现约束 |

**边界与正确表述**

可以写“基于 Chrome DevTools MCP 接入 CDP 浏览器能力”，不要写成“手写完整 CDP 协议栈”。

### 3.6 Jieba、OkHttp、Ollama

**图片表达**

Jieba 处理中文检索，OkHttp 负责网络，Ollama 提供本地 Embedding。

**源码结论**

准确。Jieba 用于 RAG 与 Memory 查询分词；OkHttp 用于模型、Web、MCP HTTP 和 Embedding；Ollama 是 Embedding Provider 之一。

**对应代码**

- `util/JiebaSegmenterFactory.java`
- `rag/RagQueryTokenizer.java`
- `memory/MemoryQueryTokenizer.java`
- `llm/AbstractOpenAiCompatibleClient.java`
- `mcp/transport/StreamableHttpTransport.java`
- `rag/EmbeddingClient.java`

**实现流程**

中文查询先分词，再参与关键词匹配；网络请求统一通过 OkHttp；RAG 索引时按配置调用 Ollama 或 OpenAI-compatible Embedding 接口。

**为什么这样选**

这些库分别解决了中文无空格分词、Java 网络请求和本地向量生成问题，避免自行实现底层算法和协议。

**方案对比与取舍**

- Jieba 比简单按空格分词更适合中文，但不等同于训练好的代码检索模型。
- OkHttp 比 JDK 原生 HTTP API 提供更成熟的拦截、连接与流式生态，但引入额外依赖。
- Ollama 隐私和成本较好，但要求本地服务与模型；远程 Embedding 易用但有费用和代码外传问题。

**边界与正确表述**

Ollama 在这里主要服务 Embedding，不等于整个 Agent 的推理模型都在本地运行。

## 4. 图片一：核心职责逐条分析

### 4.1 ripgrep + Glob + read_file 精确检索，RAG 语义兜底

**图片表达**

Agent 先用文件名和关键词工具定位代码，模糊问题再用 `search_code` 进行语义检索。

**源码结论**

准确。精确检索是默认主路径，RAG 是辅助路径。约 200ms 是用户提供的本地测试数据，不是仓库基准保证。

**对应代码**

- `tool/RipgrepCodeSearchEngine.java`
- `tool/JavaCodeSearchEngine.java`
- `tool/ToolRegistry.java`
- `rag/CodeRetriever.java`

**实现流程**

1. `glob_files` 根据路径模式筛文件。
2. `grep_code` 优先启动 ripgrep。
3. ripgrep 不可用或异常时回退 Java 文件扫描。
4. `read_file` 按需读取相关行段。
5. 当用户只描述业务含义或关键词未知时，模型可调用 `search_code`。

**为什么这样选**

代码库中的类名、方法名和错误字符串通常是精确信号。精确搜索速度快、结果可解释，而且不需要提前建索引；RAG 的价值在于解决“我不知道代码里用了什么词”的问题。

**方案对比与取舍**

| 方法 | 精度 | 前置成本 | 适合问题 |
|---|---|---|---|
| Glob | 文件路径精确 | 无 | “配置文件在哪里” |
| ripgrep | 符号/字符串精确 | 无 | “谁调用了 createOrder” |
| AST/LSP | 结构与语义更强 | 解析或服务启动 | 引用、类型关系 |
| 向量 RAG | 语义召回强 | 需要索引和 Embedding | “任务恢复怎么做” |

**边界与正确表述**

不要写“所有代码检索都在 200ms 内”。应写“本地测试中，精确检索在指定仓库规模下约 200ms 内”。

### 4.2 ReAct、并行执行与上下文压缩

**图片表达**

Agent 使用 ReAct 循环，同一轮工具可并行，并根据 Token 占用触发压缩。

**源码结论**

ReAct 和并行工具准确；固定 90% 触发已经过期。当前压缩阈值由模型窗口和预留空间动态计算。

**对应代码**

- `agent/Agent.java`
- `agent/AgentBudget.java`
- `tool/ToolRegistry.java`
- `context/ContextProfile.java`
- `memory/ConversationHistoryCompactor.java`

**实现流程**

模型产生 `tool_calls` 后，Agent 记录 assistant 消息并调用 `executeTools()`。工具结果以 `tool` 消息回灌，进入下一轮。调用模型前估算真实历史 Token，达到动态阈值时先压缩历史。

**为什么这样选**

ReAct 适合编码任务，因为下一步经常依赖刚刚读到的文件或命令结果。并行只用于同一轮相互独立的工具，减少 I/O 等待；动态压缩为摘要输出和下一轮结果留空间。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 固定工作流 | 可预测 | 难适应未知代码库 |
| ReAct | 根据观察动态决策 | 调用次数和成本不完全可预测 |
| 固定 90% 压缩 | 简单 | 不同窗口下剩余空间可能不合理 |
| 绝对预留压缩 | 大窗口更合理 | 参数需要结合模型能力维护 |

**边界与正确表述**

ReAct 的默认硬 Token 预算近似无限，但有连续重复工具 3 轮和最多 50 轮的保险阀。不要把上下文压缩阈值和 Agent 退出预算混成一个“90% Token 预算”。

### 4.3 SQLite 持久化长任务

**图片表达**

任务提交后写入 SQLite，由后台 Worker 异步执行；进程重启后可以恢复未完成任务。

**源码结论**

准确。重启时原 `RUNNING` 任务会回到待执行状态；默认两个 Worker。

**对应代码**

- `runtime/task/DurableTaskManager.java`
- `runtime/task/DurableTask.java`
- `runtime/task/TaskStatus.java`

**实现流程**

1. `enqueue` 写入 `ENQUEUED`。
2. Worker 在事务中查最早任务。
3. 使用 `WHERE id=? AND status=ENQUEUED` 的条件更新抢占任务。
4. 执行后更新为完成、失败或取消。
5. 启动时把遗留 `RUNNING` 重置为 `ENQUEUED`。

**为什么这样选**

本地 Agent 的后台任务数量通常有限，但不能因为 CLI 关闭就完全丢失。SQLite 同时解决状态持久化、任务查询和原子领取。

**方案对比与取舍**

| 方案 | 重启恢复 | 部署成本 | 扩展性 |
|---|---:|---:|---:|
| 内存队列 | 否 | 最低 | 单进程 |
| SQLite 队列 | 是 | 低 | 单机中小并发 |
| Redis 队列 | 可实现 | 中 | 多进程较好 |
| Kafka/RabbitMQ | 是 | 高 | 分布式大吞吐 |

**边界与正确表述**

当前恢复是“重新排队执行”，不是精确恢复到 Agent 上一次工具调用位置，也没有分布式租约和幂等任务框架。

### 4.4 三层 Memory、Compactor 与混合检索

**图片表达**

项目有短期、长期和压缩记忆，并结合关键词与向量检索。

**源码结论**

方向正确，但细节需拆开：

- 真正发送给模型的是 `conversationHistory`。
- 当前会话结构化短期记忆是 `ConversationMemory`。
- 长期记忆是 JSON 文件，不是 SQLite。
- Memory 自身用关键词匹配，不用 BM25 和向量。
- 余弦混合检索属于代码 RAG。

**对应代码**

- `agent/Agent.java`
- `memory/ConversationMemory.java`
- `memory/LongTermMemory.java`
- `memory/ConversationHistoryCompactor.java`
- `memory/ContextCompressor.java`
- `memory/MemoryRetriever.java`
- `rag/CodeRetriever.java`

**实现流程**

用户、助手和工具结果进入短期记忆；相关长期事实按查询检索后注入 Prompt。真实 LLM 历史和结构化短期记忆分别有压缩器。RAG 独立处理代码的语义与关键词检索。

**为什么这样选**

“模型消息历史”“当前会话可检索记录”“跨会话稳定事实”生命周期不同。放在一个容器里会导致清理、压缩和权限边界混乱。

**方案对比与取舍**

| 方案 | 优点 | 风险 |
|---|---|---|
| 所有历史原样保存 | 信息完整 | Token 无限增长 |
| 只有滑动窗口 | 简单 | 早期关键决策消失 |
| 摘要 + 最近历史 | 成本可控 | 摘要可能遗漏细节 |
| 自动向量化所有记忆 | 语义召回强 | 成本、隐私和错误记忆扩大 |
| 显式长期记忆 | 可审计 | 用户需要主动保存 |

**边界与正确表述**

推荐写“会话历史、短期结构化记忆、长期事实记忆三类上下文”，不要说“三个 SQLite Memory 层”。

### 4.5 MCP、双传输、Schema 清洗和 HITL

**图片表达**

Lincode 能接入 MCP 外部工具，支持 stdio 和 HTTP，清洗工具 Schema，并对调用做人工审批。

**源码结论**

准确。当前还支持 Resource、Prompt 和列表变化通知；MCP OAuth、sampling 和自动重启尚未交付。

**对应代码**

- `mcp/McpServerManager.java`
- `mcp/McpClient.java`
- `mcp/transport/`
- `mcp/protocol/McpSchemaSanitizer.java`
- `hitl/HitlToolRegistry.java`

**实现流程**

读取用户级和项目级配置，为每个 Server 创建传输和 JSON-RPC 客户端，完成初始化，读取工具列表，清洗 Schema 后注册为 `mcp__server__tool`。模型调用时再路由回对应 Server；MCP 工具默认受 HITL 约束。

**为什么这样选**

MCP 把外部能力变成统一协议，核心项目不必为 GitHub、数据库、浏览器等系统逐个实现专用集成。Schema 清洗用于吸收不同 Server 与模型厂商之间的兼容差异。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 每个系统写专用插件 | 可深度定制 | 核心代码持续膨胀 |
| Shell 脚本工具 | 灵活 | Schema、结果和权限不统一 |
| MCP | 标准化、动态发现 | 协议仍在演进，Server 质量不一致 |

**边界与正确表述**

外部工具数量是动态的，不能把某次环境中的“60+”写成项目固定能力。

### 4.6 Plan-and-Execute + Multi-Agent

**图片表达**

Planner 把复杂任务拆成 DAG，Worker 并行执行，Reviewer 审核并触发重试。

**源码结论**

图片把两条执行路径合在了一句话里：

- Plan-and-Execute 有 Planner、DAG、最多 4 个任务并行和早期失败重规划。
- Multi-Agent 有 Planner、两个 Worker、Reviewer 和最多 2 次审核重试。

**对应代码**

- `agent/PlanExecuteAgent.java`
- `plan/Planner.java`
- `plan/ExecutionPlan.java`
- `agent/AgentOrchestrator.java`
- `agent/SubAgent.java`

**实现流程**

Plan 模式先生成 JSON 计划，经用户审阅后执行依赖就绪批次。Multi-Agent 则由规划子 Agent 生成步骤，编排器分配 Worker，Reviewer 检查结果，拒绝后把问题反馈给 Worker。

**为什么这样选**

复杂任务有两个不同问题：

- “先做什么、后做什么”由 DAG 解决。
- “谁负责、谁检查”由角色协作解决。

把两者分成两种模式可以避免简单任务承担不必要的 Multi-Agent 成本。

**方案对比与取舍**

| 模式 | 规划成本 | 并发 | 质量反馈 | 适用任务 |
|---|---:|---:|---:|---|
| ReAct | 低 | 工具级 | 模型自检 | 小型动态任务 |
| Plan | 中 | 任务级最多 4 | 失败可重规划 | 有依赖的复杂任务 |
| Multi-Agent | 高 | 默认 2 Worker | Reviewer 最多重试 2 次 | 可分工且重视审核 |

**边界与正确表述**

不要把“最多 4 线程”同时套到 Multi-Agent；它默认只有两个 Worker。

### 4.7 ReAct 核心循环与 ToolRegistry

**图片表达**

ReAct 由 LLM Function Calling 驱动，通过 ToolRegistry 动态注册内置和 MCP 工具。

**源码结论**

机制准确，数量过期。当前源码有 16 个内置工具入口，MCP 数量动态变化。

**对应代码**

- `agent/Agent.java`
- `tool/ToolRegistry.java`
- `llm/LlmClient.java`
- `mcp/McpServerManager.java`

**实现流程**

ToolRegistry 生成工具 Schema 列表交给模型。模型返回工具名和 JSON 参数，Agent 交给 Registry 执行。MCP 启动后也向同一 Registry 动态增删工具，因此 Agent 不需要区分内置和外部工具的决策方式。

**为什么这样选**

统一工具出口可以复用并行、超时、审批、审计、渲染和错误处理。否则三条 Agent 路径都会产生重复工具代码。

**方案对比与取舍**

| 工具协议 | 优点 | 缺点 |
|---|---|---|
| 从文本中解析命令 | 模型兼容广 | 格式脆弱、容易误解析 |
| XML 标签 | 比自由文本稳定 | 仍需自行定义协议 |
| 原生 Function Calling | 结构化、可校验 | 依赖模型支持 tools |
| 固定工作流 API | 可控 | 模型无法自主选工具 |

**边界与正确表述**

讯飞 MaaS 当前不发送 Lincode 工具列表，不能笼统说“所有支持的模型都能使用所有工具”。

### 4.8 Plan 的 DAG、拓扑排序与失败传播

**图片表达**

DAG 管理依赖，独立任务并行，前置失败后下游自动跳过。

**源码结论**

拓扑排序和并行准确；“显式 SKIP”主要属于 Multi-Agent。Plan 中失败后可能在进度小于 50% 时重新规划，否则失败任务会让依赖它的任务无法变为可执行，计划最终部分完成或停止推进。

**对应代码**

- `plan/ExecutionPlan.java`
- `plan/Task.java`
- `agent/PlanExecuteAgent.java`
- `agent/AgentOrchestrator.java`

**实现流程**

`ExecutionPlan` 使用 DFS 生成依赖在前的执行顺序并检测环。每轮选择所有依赖已完成的任务，组成并行批次。Multi-Agent 在循环结束后显式提示因前置失败而未执行的步骤。

**为什么这样选**

DAG 比线性列表更适合“读取两个模块可以并行、完成后再统一修改”这类任务。拓扑排序保证依赖顺序，批次调度释放并发。

**方案对比与取舍**

| 算法/结构 | 优点 | 缺点 |
|---|---|---|
| 线性步骤 | 简单 | 无法表达并行和复杂依赖 |
| DFS 拓扑排序 | 实现直接、容易检测递归环 | 动态入度调度不如 Kahn 直观 |
| Kahn 算法 | 天然按入度分批 | 需要维护入度结构 |
| 通用工作流引擎 | 重试补偿完善 | 对本地 CLI 过重 |

**边界与正确表述**

如果简历强调下游 `SKIP`，应明确写在 Multi-Agent 路径；Plan 路径可以写“失败依赖不会继续执行，早期失败支持重新规划”。

### 4.9 同轮 tool_calls 并行且结果保序

**图片表达**

多个工具调用通过 `ExecutorService` 并行执行，完成后按模型原始顺序返回，三种模式共享调度器。

**源码结论**

准确。最大并行数为 4，默认批次超时约 90 秒。

**对应代码**

- `tool/ToolRegistry.java` 的 `executeTools`
- `agent/Agent.java`
- `agent/PlanExecuteAgent.java`
- `agent/SubAgent.java`

**实现流程**

Registry 为每个调用创建 `Callable`，使用 `invokeAll` 执行。`Future` 列表与输入顺序一致，收集结果时按索引返回；超时的 Future 转成结构化超时结果。

**为什么这样选**

模型协议依靠 `tool_call_id` 和调用顺序理解结果。只按完成顺序返回虽然更快展示，但会增加模型关联错误和测试不确定性。

**方案对比与取舍**

| 方案 | 延迟 | 顺序稳定 | 实现复杂度 |
|---|---:|---:|---:|
| 串行 for-loop | 高 | 是 | 低 |
| `ExecutorService` + 保序收集 | 低 | 是 | 中 |
| `CompletableFuture.anyOf` 完成即返回 | 低 | 否 | 中 |
| 响应式流 | 低 | 可配置 | 高 |

**边界与正确表述**

只有同一轮被模型同时提出的工具调用才适合并行；有读写依赖的动作应由模型分轮发出。

### 4.10 动态 Token 预算与 Map-Reduce 压缩

**图片表达**

根据模型上下文窗口管理 Token，采用 Map-Reduce 压缩，支持 200K 和 1M 窗口模型。

**源码结论**

模型窗口声明准确：GLM Client 为 200,000，DeepSeek Client 为 1,000,000。固定 `90% × window` 已过期。

**对应代码**

- `context/ContextProfile.java`
- `memory/TokenBudget.java`
- `memory/ContextCompressor.java`
- `memory/ConversationHistoryCompactor.java`
- `llm/GLMClient.java`
- `llm/DeepSeekClient.java`

**实现流程**

大窗口压缩阈值约为：

```text
window - 20K 摘要预留 - 13K 安全缓冲
```

短期记忆压缩把旧条目按每 5 条分片摘要，再合并摘要；真实历史压缩按 user 边界切分，保留最近 3 个用户轮次并保护工具消息配对。

**为什么这样选**

长上下文不是无限内存。工具结果可能突然很大，摘要本身也需要输出空间。绝对预留比简单百分比更能表达这些固定成本。

**方案对比与取舍**

| 压缩方式 | 优点 | 缺点 |
|---|---|---|
| 直接截掉最旧消息 | 最便宜 | 可能切断工具边界和关键决策 |
| 滑动窗口 | 简单稳定 | 早期事实完全丢失 |
| 一次性摘要 | 调用少 | 超长输入可能摘要不全 |
| Map-Reduce 摘要 | 可处理较长历史 | 增加模型调用和摘要误差 |
| 外部长期检索 | 可跨会话 | 需要额外存储和召回设计 |

**边界与正确表述**

模型 Client 声明的窗口是 Lincode 的策略输入，不等于任何模型名称在所有上游套餐中都必然提供相同窗口。

### 4.11 SearchProvider 策略抽象

**图片表达**

联网搜索支持智谱、SerpAPI 和 SearXNG，并能自动选择或切换。

**源码结论**

三种 Provider 和自动选择准确；“热切换”不准确。Provider 在 ToolRegistry 首次使用时创建并缓存，通常需要重新创建实例或重启后应用环境变量变化。

**对应代码**

- `web/SearchProvider.java`
- `web/SearchProviderFactory.java`
- `web/ZhipuSearchProvider.java`
- `web/SerpApiSearchProvider.java`
- `web/SearxngSearchProvider.java`

**实现流程**

未显式配置 `SEARCH_PROVIDER` 时，按 GLM Key、SerpAPI Key、SearXNG URL 的顺序选择；显式值则直接选择相应实现。ToolRegistry 只依赖接口。

**为什么这样选**

不同用户对成本、可访问性、中文效果和数据合规的要求不同。策略模式把业务调用与供应商 HTTP 细节分离。

**方案对比与取舍**

| Provider | 优点 | 缺点 |
|---|---|---|
| 智谱搜索 | 国内网络和中文场景友好，可复用 GLM Key | 供应商绑定 |
| SerpAPI | 国际搜索聚合成熟 | 付费、受外部服务限制 |
| SearXNG | 可自托管、控制数据 | 需要自行部署和维护 |

**边界与正确表述**

ToolRegistry 的工具描述仍有“SerpAPI 默认”等旧文案，但 Factory 当前优先选择智谱。对外说明应以 Factory 行为为准。

### 4.12 Jsoup Readability 正文提取

**图片表达**

通过语义标签优先和链接密度评分提取网页正文。

**源码结论**

准确，但这是项目自实现的“极简 Readability”，不是 Mozilla Readability 的完整 Java 移植。

**对应代码**

- `web/HtmlExtractor.java`
- `web/WebFetcher.java`

**实现流程**

1. 删除 script、style、nav、aside、广告类名等噪声。
2. 优先选择 `article`、`main`、`role=main`。
3. 如果没有合适语义容器，对 div/section 等按文本长度与链接占比评分。
4. 把标题、段落、列表、表格和代码转换为 Markdown。

**为什么这样选**

Agent 需要的是低噪声文本，而不是完整 DOM。两阶段方法对博客、文档和官网 SSR 页面成本低、可解释。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 返回原 HTML | 信息全 | Token 大、噪声多 |
| CSS 选择器定制 | 单站准确 | 不能泛化 |
| 极简 Readability | 轻量、可控 | 复杂页面准确率有限 |
| Mozilla Readability | 算法成熟 | Java 集成和依赖更复杂 |
| 无头浏览器 DOM | 能处理 JS | 资源和延迟高 |

**边界与正确表述**

该算法不能执行 JavaScript。SPA、登录墙和强反爬页面需要浏览器或其他 fallback。

### 4.13 NetworkPolicy、SSRF 与限流

**图片表达**

联网工具有 scheme、Host、DNS 校验和请求限流。

**源码结论**

基本准确。只允许 HTTP/HTTPS，拒绝 localhost、环回、链路本地、站内和未指定地址；默认 60 秒最多 30 次请求。

**对应代码**

- `web/NetworkPolicy.java`
- `tool/ToolRegistry.java`

**实现流程**

请求前解析 URI，校验 scheme 和 Host，再对 `InetAddress.getAllByName` 的每个地址判断是否为受限网络；之后申请当前时间窗口的请求配额。

**为什么这样选**

Agent 的 URL 可能来自用户、网页或模型。如果不限制，`web_fetch` 可能被诱导访问本机管理端口、云元数据或内网系统。

**方案对比与取舍**

| 控制 | 能解决什么 | 不能解决什么 |
|---|---|---|
| scheme 白名单 | `file:`、`ftp:` 等协议滥用 | 合法 HTTPS 上的恶意内容 |
| DNS/IP 检查 | 常见内网 SSRF | 完整 DNS rebinding |
| 固定窗口限流 | 简单防止调用过量 | 不是严格令牌桶，也不是分布式限流 |
| 容器网络策略 | 强隔离 | 部署成本高 |

**边界与正确表述**

源码注释已经明确当前仍需加强 DNS rebinding、CIDR 策略和证书校验，因此不能宣传为企业级完整 SSRF 防护。

### 4.14 web_search、web_fetch 作为 Function Calling 工具

**图片表达**

模型可以自主判断何时联网，而不是每次任务都强制搜索。

**源码结论**

准确。两者注册进 ToolRegistry，并由工具 Schema、System Prompt 和用户目标共同影响模型选择。

**对应代码**

- `tool/ToolRegistry.java`
- `web/SearchProvider.java`
- `web/WebFetcher.java`
- `src/main/resources/prompts/base.md`

**实现流程**

模型看到 `web_search` 和 `web_fetch` 的说明：未知 URL 或需要最新信息时搜索，已有 URL 时抓正文；静态抓取失败后 Prompt 引导改用浏览器 MCP。

**为什么这样选**

不是所有编码问题都需要联网。让模型根据任务调用，可以减少延迟、费用和无关信息污染；本地项目问题优先读代码。

**方案对比与取舍**

| 策略 | 优点 | 缺点 |
|---|---|---|
| 每轮强制联网 | 信息较新 | 慢、贵、容易偏离本地代码 |
| 完全禁止联网 | 隐私好 | 无法查最新文档 |
| 用户显式命令联网 | 可控 | 用户需要理解工具 |
| Function Calling 自主选择 | 体验自然 | 依赖模型判断质量 |

**边界与正确表述**

模型可能判断错误，因此 Prompt 规则、工具描述和用户明确指令仍然重要。

## 5. 图片二：扩展职责逐条分析

### 5.1 Token 消耗、耗时与成本统计

**图片表达**

统计每轮模型调用的输入、输出 Token 和耗时，用于比较模型成本与性能。

**源码结论**

准确。当前还记录 cached input Token，并可在状态栏展示估算成本。

**对应代码**

- `llm/AbstractOpenAiCompatibleClient.java`
- `agent/AgentBudget.java`
- `memory/TokenBudget.java`
- `context/TokenUsageFormatter.java`
- `render/StatusInfo.java`
- `render/inline/BottomStatusBar.java`

**实现流程**

模型流式响应结束时解析 Provider 返回的 usage，累加 input/output/cached Token。Agent 记录任务耗时，Renderer 将 `in/out/cache/cost/elapsed` 显示在底部状态栏。

**为什么这样选**

Agent 一次任务可能调用模型很多轮。只看最终答案无法判断是模型慢、工具慢，还是上下文太大。统计是成本治理和模型对比的基础。

**方案对比与取舍**

| 统计方式 | 优点 | 缺点 |
|---|---|---|
| 使用 Provider usage | 相对准确 | 各厂商字段不同 |
| 本地字符估算 | 所有模型可用 | 对中文、图片和特殊 tokenizer 有误差 |
| 完整链路追踪平台 | 分析能力强 | 对本地 CLI 过重 |

**边界与正确表述**

成本是根据模型价格表或配置估算，不应当作最终账单；缓存字段也依赖 Provider 是否返回。

### 5.2 Multi-Agent 主从协作架构

**图片表达**

通过 `/team` 开启 Planner、Worker、Reviewer 三类角色，缓解单 Agent 处理复杂任务的瓶颈。

**源码结论**

准确。编排器是主控，子 Agent 是执行角色。

**对应代码**

- `agent/AgentOrchestrator.java`
- `agent/SubAgent.java`
- `agent/AgentRole.java`

**实现流程**

编排器把目标交给 Planner，解析 JSON 步骤；按依赖把步骤分配给 Worker；Worker 结果交给 Reviewer；未通过时携带反馈重试；最后汇总所有步骤。

**为什么这样选**

单 Agent 同时规划、执行和审核容易产生自我确认偏差。角色分离让每个 Prompt 更聚焦，也能为独立步骤提供并行空间。

**方案对比与取舍**

| 架构 | 优点 | 缺点 |
|---|---|---|
| 单 Agent | 调用少、上下文统一 | 复杂任务易失去全局结构 |
| 多角色同一模型 | 角色清晰、实现成本适中 | Token 和调用次数增加 |
| 多模型多 Agent | 可按能力分工 | 路由、成本和一致性更复杂 |
| 人类项目团队 | 判断更可靠 | 延迟和人力成本高 |

**边界与正确表述**

当前角色主要是逻辑隔离，不是独立进程或分布式 Agent 集群。

### 5.3 BlockingQueue + ExecutorService Worker 引擎

**图片表达**

Worker 池并行领取子任务，并按依赖推进工作流。

**源码结论**

准确。默认 Worker 数量为 2；并行批次结束后按步骤顺序输出。

**对应代码**

- `agent/AgentOrchestrator.java` 的 `runBatchParallel`

**实现流程**

把两个 Worker 放入 `LinkedBlockingQueue`。每个并行步骤执行前 `take` 一个 Worker，结束后 `put` 回队列，保证同一个 Worker 不被两个线程同时使用。输出写入独立缓冲区，完成后按 step 顺序 flush。

**为什么这样选**

SubAgent 自己维护对话历史，不是线程安全的。Worker 池把“并发度限制”和“Agent 实例独占”同时解决。

**方案对比与取舍**

| 方案 | 优点 | 风险 |
|---|---|---|
| 每任务新建 Agent | 完全隔离 | 初始化和上下文重复 |
| 共享一个 Worker | 成本低 | 历史竞争、无法并行 |
| BlockingQueue Worker 池 | 并发可控、实例独占 | Worker 数固定 |
| Actor 模型 | 隔离和消息语义强 | 引入框架和学习成本 |

**边界与正确表述**

并行只发生在依赖已经满足的同一批次，不是所有步骤无条件同时执行。

### 5.4 手写 JSON-RPC 2.0 客户端

**图片表达**

使用 `CompletableFuture + ConcurrentHashMap` 做请求响应配对，支持超时和通知广播。

**源码结论**

准确。

**对应代码**

- `mcp/jsonrpc/JsonRpcClient.java`
- `mcp/jsonrpc/JsonRpcMessage.java`
- `mcp/notifications/NotificationRouter.java`

**实现流程**

请求 ID 由 `AtomicLong` 生成；发送前把 ID 和 Future 放入 pending Map；收到响应后按 ID 移除并完成 Future；没有 ID 的消息广播给通知监听器；定时线程负责超时完成。

**为什么这样选**

MCP 的传输可能异步返回，多个请求会交错。Future 把协议层异步配对封装成上层易用的请求接口，ConcurrentHashMap 保证接收线程和调用线程安全协作。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 引入 JSON-RPC 库 | 少写协议代码 | 可能不适配 MCP 传输和通知 |
| 手写最小客户端 | 边界清晰、易定制 | 需要自己处理错误和兼容 |
| 完全响应式实现 | 高并发自然 | 对当前规模复杂度过高 |

**边界与正确表述**

这是面向 MCP 所需子集的 JSON-RPC 客户端，不代表覆盖 JSON-RPC 的所有批量请求和扩展场景。

### 5.5 McpTransport：stdio 与 Streamable HTTP

**图片表达**

抽象统一传输接口，底层支持子进程管道和 HTTP/SSE。

**源码结论**

准确。HTTP 实现能解析 SSE 格式响应并维护 MCP Session ID。

**对应代码**

- `mcp/transport/McpTransport.java`
- `mcp/transport/StdioTransport.java`
- `mcp/transport/StreamableHttpTransport.java`

**实现流程**

上层 `JsonRpcClient` 只调用 `send` 和 `onReceive`。stdio 启动子进程并读取 stdout JSON；HTTP POST JSON，接受 JSON 或 `text/event-stream`，解析后通知监听器。

**为什么这样选**

传输与协议分离后，同一套 JSON-RPC 和 MCP Client 可以复用；本地工具适合 stdio，远程服务适合 HTTP。

**方案对比与取舍**

| 传输 | 优点 | 缺点 |
|---|---|---|
| stdio | 本地部署简单、无需端口 | 进程生命周期和 stderr 管理复杂 |
| Streamable HTTP | 远程可访问、语言无关 | 网络、认证和会话管理复杂 |
| WebSocket | 双向长连接自然 | 不是当前 MCP 主选传输 |

**边界与正确表述**

当前 HTTP `send` 会读取本次响应体并解析 SSE，不应夸大成完整的长期 SSE 订阅与断线续传实现。

### 5.6 BrowserSession isolated/shared 状态管理

**图片表达**

浏览器会话支持隔离和共享模式，使用同步保证状态一致。

**源码结论**

准确。`BrowserSession` 管理的是 Lincode 侧会话元数据，不是浏览器进程本身。

**对应代码**

- `browser/BrowserSession.java`
- `browser/BrowserMode.java`
- `browser/BrowserGuard.java`

**实现流程**

Session 保存模式、浏览器 URL、最后导航 URL 和 Agent 打开的标签页 ID。所有读写方法使用 `synchronized`，切换模式时清理旧状态。

**为什么这样选**

浏览器工具可能由不同 Agent 或异步路径访问。状态对象很小，使用内置锁比引入复杂并发结构更直观。

**方案对比与取舍**

| 方案 | 优点 | 缺点 |
|---|---|---|
| 无状态，每次读取 MCP | 单一真相 | 调用多、无法跟踪 Agent tab |
| `synchronized` 状态对象 | 简单、原子性清晰 | 高并发下锁粒度粗 |
| 原子变量/并发集合组合 | 局部性能好 | 多字段切换难保证整体一致 |
| Actor 会话 | 串行状态天然安全 | 引入额外模型 |

**边界与正确表述**

shared 模式复用真实登录态风险更高，因此项目还有敏感页面策略和“只关闭 Agent 自己创建的标签页”规则。

### 5.7 CDP `--autoConnect` 复用本机 Chrome

**图片表达**

当任务需要登录态时，Agent 可以自动发现并连接本机 Chrome。

**源码结论**

能力存在，但不是每次检测到登录页后由 Java 无条件自动连接。用户可通过 `/browser connect`，模型也可调用 `browser_connect`；底层重启 chrome-devtools MCP 并传入 `--autoConnect`。

**对应代码**

- `cli/Main.java` 中 browser 命令
- `tool/ToolRegistry.java` 的 browser 工具
- `browser/BrowserConnectivityCheck.java`

**实现流程**

检查本机 Chrome 远程调试能力，使用 `--autoConnect` 重启 MCP Server；成功后 Session 切为 shared。失败时恢复原启动参数。

**为什么这样选**

用户已登录的页面无法靠普通 HTTP 抓取。复用 Chrome 比让用户把 Cookie 或 Token 复制给 Agent 更安全、更自然。

**方案对比与取舍**

| 方案 | 登录态 | 风险/成本 |
|---|---|---|
| isolated 浏览器 | 无 | 隔离好，但要重新登录 |
| shared Chrome | 有 | 能看到用户真实页面，风险高 |
| 导入 Cookie | 有 | 密钥管理和泄露风险高 |
| 远程浏览器服务 | 可有 | 需要额外基础设施 |

**边界与正确表述**

“Codex 级别 Browser Use”是宣传性判断，源码只能证明具备 CDP 会话复用与工具调用能力，不能证明整体体验等价。

### 5.8 web_fetch 到浏览器 MCP 的 fallback

**图片表达**

静态抓取拿不到 SPA、反爬或客户端渲染正文时，切到 Chrome DevTools MCP，用 `take_snapshot` 读取 DOM。

**源码结论**

策略准确，但“自动”主要由 Prompt 和 `web-access` Skill 引导 LLM，而不是 `WebFetcher` 内部直接调用浏览器。

**对应代码**

- `src/main/resources/prompts/base.md`
- `src/main/resources/skills/web-access/SKILL.md`
- `web/WebFetcher.java`
- 动态 MCP 工具 `mcp__chrome-devtools__take_snapshot`

**实现流程**

模型先调用 `web_fetch`。结果为空或提示 SPA/防爬时，下一轮根据系统规则选择浏览器 navigate/wait/take_snapshot。`take_snapshot` 返回结构化 DOM 文本，比截图更节省视觉 Token。

**为什么这样选**

HTTP 抓取便宜且快，浏览器昂贵但覆盖面强。分层 fallback 能把常见静态页面留在轻量路径，把真正需要执行 JavaScript 的页面交给浏览器。

**方案对比与取舍**

| 方法 | 速度/资源 | 覆盖能力 |
|---|---|---|
| HTTP + Jsoup | 快、轻 | 静态/SSR |
| Jina 等正文代理 | 中 | 部分反爬/正文转换 |
| DOM snapshot | 较重 | SPA、登录态、交互页面 |
| screenshot + Vision | 最重 | 视觉布局、Canvas、图片内容 |

**边界与正确表述**

公众号、知乎和掘金并非所有页面都稳定可抓；站点策略会变化。应描述为“覆盖常见不可达场景”，而非保证全部成功。

### 5.9 SQLite 轻量向量存储

**图片表达**

向量以 JSON 数组写入 SQLite，检索时加载并计算余弦相似度。

**源码结论**

准确。`<100ms` 是特定机器与千行级代码块规模下的本地参考。

**对应代码**

- `rag/VectorStore.java`

**实现流程**

代码块和 `embedding_json` 按项目路径写入 `code_chunks`。查询时加载当前项目所有向量，反序列化为 `float[]`，逐个计算余弦相似度，排序取 TopK。

**为什么这样选**

目标是本地中小代码库，优先考虑安装简单和可调试。一个 SQLite 文件比部署向量数据库更符合 CLI 产品。

**方案对比与取舍**

| 方案 | 部署 | 查询扩展性 | 适用规模 |
|---|---|---|---|
| JSON 文件 | 最简单 | 弱 | 很小 |
| SQLite + 进程内余弦 | 简单 | 线性扫描 | 小到中 |
| FAISS/HNSW | 需原生/额外集成 | 高 | 中到大 |
| pgvector/Milvus | 需服务 | 很高 | 大规模、多用户 |

**边界与正确表述**

当前复杂度接近 O(N×D)，代码块大量增长时性能会线性下降；数据库索引并没有加速向量相似度。

### 5.10 JavaParser AST 分块、Embedding Provider 与混合检索

**图片表达**

Java 文件按 AST 的文件、类、方法分块，其他文本按大小分块；Embedding 可切 Ollama 或远程 API；最终融合语义和 Jieba 关键词。

**源码结论**

准确。混合检索不是标准 BM25，而是余弦结果、SQL LIKE 关键词结果和手工加权合并。

**对应代码**

- `rag/CodeChunker.java`
- `rag/CodeAnalyzer.java`
- `rag/EmbeddingClient.java`
- `rag/CodeRetriever.java`
- `rag/RagQueryTokenizer.java`

**实现流程**

JavaParser 提取结构边界和关系；EmbeddingClient 根据配置选择 Provider；查询同时做向量和关键词检索；名称、路径、正文命中得到不同加分；方法和类级代码块额外加权；单文件最多保留两个结果。

**为什么这样选**

按固定字符切方法会破坏代码语义，AST 分块更容易让模型看到完整实现。语义检索解决同义表达，关键词检索保护类名、方法名等强信号。

**方案对比与取舍**

| 分块/检索 | 优点 | 缺点 |
|---|---|---|
| 固定字符分块 | 语言无关 | 可能切断方法 |
| AST 分块 | 结构完整 | 需要语言解析器 |
| 纯向量 | 语义强 | 精确符号可能排名不稳 |
| 纯关键词 | 可解释 | 同义表达召回差 |
| 混合检索 | 兼顾两类信号 | 权重需要经验调试 |

**边界与正确表述**

JavaParser 不能解析所有其他语言的语义结构；非 Java 文件仍以文本分块为主。

### 5.11 RAG 封装为 `search_code` 工具

**图片表达**

RAG 不是写死在每轮输入中，而是作为工具交给模型按需调用，ReAct 和 Plan 都可使用。

**源码结论**

准确。Multi-Agent 的 SubAgent 也复用 ToolRegistry，因此原则上同样可用。

**对应代码**

- `tool/ToolRegistry.java` 的 `registerRagTools`
- `rag/CodeRetriever.java`
- `src/main/resources/prompts/base.md`

**实现流程**

ToolRegistry 暴露 query/top_k Schema。模型认为精确搜索不足时调用；Registry 初始化 CodeRetriever 并返回格式化结果；结果继续进入 Agent 推理。

**为什么这样选**

如果每轮都自动做 RAG，会增加 Embedding 调用、延迟和无关上下文。作为工具可以把“是否需要语义检索”的决策交给模型。

**方案对比与取舍**

| 注入方式 | 优点 | 缺点 |
|---|---|---|
| 每轮固定检索 | 模型总有上下文 | 成本高、可能污染 |
| 用户手动 `/search` | 可控 | 体验不自然 |
| Tool Calling 按需检索 | 成本与体验平衡 | 模型可能漏调 |
| 路由模型判断 | 可更稳定 | 多一次模型或分类器成本 |

**边界与正确表述**

System Prompt 只引导模型选择，不保证每个适合的场景都一定调用 `search_code`。

### 5.12 四种 MemoryEntry 与长期记忆持久化

**图片表达**

Memory 有对话、事实、摘要和工具结果四种类型，超预算淘汰旧条目，长期保存用户偏好与项目信息。

**源码结论**

四种类型和淘汰机制准确；“Markdown 存储长期记忆”不准确。长期记忆使用 JSON；`PAI.md` 是另一套项目规则机制。

**对应代码**

- `memory/MemoryEntry.java`
- `memory/ConversationMemory.java`
- `memory/LongTermMemory.java`
- `memory/MemoryManager.java`
- `prompt/ProjectMemoryLoader.java`

**实现流程**

对话和工具结果进入短期 Memory；压缩后生成 SUMMARY；用户明确保存时生成 FACT 写入 JSON。存储时按内容完全相同去重，启动时自动加载，检索时按 project/global 可见性和关键词匹配。

**为什么这样选**

类型信息使压缩和展示知道一条内容的来源。JSON 长期记忆易审计、删除和迁移；`PAI.md` 则适合团队版本控制的稳定规则。

**方案对比与取舍**

| 存储 | 优点 | 缺点 |
|---|---|---|
| Markdown | 人类易编辑 | 结构化 ID、删除和元数据较弱 |
| JSON | 结构清晰、易持久化 | 手工编辑体验一般 |
| SQLite | 查询和事务强 | 对少量事实略重 |
| 向量库 | 语义召回 | 成本和误召回更高 |

**边界与正确表述**

当前长期记忆去重是内容完全相同，不是语义相似去重。

### 5.13 ContextCompressor、最近消息与事实提取

**图片表达**

Map-Reduce 压缩旧消息，保留最近 3 轮，并自动提取关键事实进入长期记忆。

**源码结论**

需要三处修正：

- `ContextCompressor` 保留最近 3 个 `MemoryEntry`，不是严格 3 个完整 user/assistant 轮次。
- `ConversationHistoryCompactor` 自动保留最近 3 个用户轮次。
- `extractFacts` 方法存在但主流程没有调用，因此当前不会自动保存事实。

**对应代码**

- `memory/ContextCompressor.java`
- `memory/ConversationHistoryCompactor.java`
- `memory/MemoryManager.java`

**实现流程**

短期压缩按 5 条 Entry 一片做 Map 摘要，再 Reduce 合并；真实历史压缩按 user 消息边界划分，避免把 assistant tool call 与 tool result 拆开。

**为什么这样选**

真实模型协议边界比普通文本边界更重要。工具消息被截断会导致某些模型拒绝请求，因此真正的上下文压缩必须认识消息角色和工具关联。

**方案对比与取舍**

| 事实保存策略 | 优点 | 风险 |
|---|---|---|
| 自动提取并保存 | 使用方便 | 错误推断、临时任务、敏感内容被永久化 |
| 自动提取后让用户确认 | 体验与安全平衡 | 多一步交互 |
| 用户显式 `/save` | 可审计、最稳妥 | 依赖用户主动操作 |

**边界与正确表述**

当前项目选择显式保存。面试时可以说“保留了事实提取组件，但主链路没有自动启用，以降低错误记忆污染”。

### 5.14 DFS 拓扑排序与依赖批次并行

**图片表达**

把 DAG 转换为合法执行顺序，检测循环依赖，并发执行无依赖任务。

**源码结论**

准确。但“效率大幅提升”没有仓库基准数据，提升程度取决于任务是否真正独立以及主要耗时是否可并行。

**对应代码**

- `plan/ExecutionPlan.java`
- `agent/PlanExecuteAgent.java`

**实现流程**

DFS 使用 visited 和 visiting 集合：进入节点时放入 visiting，递归遇到 visiting 节点说明有环；退出时放入 visited 和执行顺序。运行时每轮提取依赖全部完成的任务并行执行。

**为什么这样选**

DFS 实现短、容易与任务对象结构结合。对于一次计划通常只有少量节点，不需要引入更复杂调度框架。

**方案对比与取舍**

| 方案 | 环检测 | 批次生成 | 适用 |
|---|---:|---:|---|
| DFS | 自然 | 需另选可执行任务 | 小型静态 DAG |
| Kahn 入度法 | 自然 | 天然可形成零入度批次 | 动态调度更直观 |
| 工作流引擎 | 完整 | 完整 | 长周期企业工作流 |

**边界与正确表述**

线程并发不能自动加速有强依赖的任务；多个任务同时写同一文件还可能产生冲突，规划阶段应避免这种并行。

### 5.15 Planner：5–10 个子任务、JSON 与前向引用

**图片表达**

Planner 使用 LLM 把复杂任务拆成 5–10 个可执行任务，以 JSON 输出，并支持依赖引用尚未创建的任务。

**源码结论**

准确。Prompt 规定简单任务 1–3 个，复杂任务 5–10 个；解析器使用两遍处理前向引用。

**对应代码**

- `plan/Planner.java`
- `src/main/resources/prompts/modes/planner.md`
- `plan/ExecutionPlan.java`

**实现流程**

第一遍遍历 JSON tasks，为模型原始 ID 创建内部 `task_1...` 映射并建立所有 Task；第二遍再解析 dependencies，所以依赖可以引用 JSON 中后出现的 ID；最后做环检测。

**为什么这样选**

LLM 生成 JSON 时不一定严格按拓扑顺序排列。两遍解析比“要求模型绝不前向引用”更健壮，也让内部 ID 规范化。

**方案对比与取舍**

| 输出方式 | 优点 | 缺点 |
|---|---|---|
| 自然语言计划 | 人类易读 | 程序难可靠执行 |
| JSON | 易校验和映射 | 模型偶尔输出格式错误 |
| JSON Schema 严格结构化输出 | 更稳定 | 依赖模型接口能力 |
| 代码式 DSL | 表达力强 | 对模型和用户复杂 |

**边界与正确表述**

5–10 是 Prompt 引导，不是解析器强制校验；模型仍可能返回其他数量。当前解析也不是完整 JSON Schema 验证器。

### 5.16 JLine 交互：历史、补全、编辑和高亮

**图片表达**

提供类似现代编码 Agent 的终端输入体验。

**源码结论**

准确。除上下箭头历史、Tab 补全、行编辑和高亮外，还支持持久化历史过滤、底部 dock、ESC 取消和 `@path`/MCP Resource 补全。

**对应代码**

- `cli/Main.java`
- `cli/LincodeHistory.java`
- `cli/LincodeCompleter.java`
- `cli/LincodeHighlighter.java`
- `render/inline/InlineRenderer.java`

**实现流程**

Completer 根据当前输入上下文补全 slash 命令、模型、MCP、Skill、任务、浏览器、本地路径和 Resource；Highlighter 只改变显示样式，不修改最终文本；History 过滤空白、重复、疑似密钥、图片 Base64 和超长输入。

**为什么这样选**

Agent CLI 是长时间使用的开发工具。没有历史、补全和状态反馈，用户会反复输入复杂命令，也更容易粘贴敏感内容。

**方案对比与取舍**

| 交互形态 | 优点 | 缺点 |
|---|---|---|
| 普通 stdin/stdout | 最稳定 | 使用体验弱 |
| JLine inline | 保留 shell transcript，功能丰富 | 异步重绘复杂 |
| 全屏 TUI | 布局能力强 | 与终端滚动历史不自然 |
| Web UI | 视觉能力最强 | 需要服务和浏览器 |

**边界与正确表述**

“体验接近 Claude Code/Codex”属于主观评价。更严谨的说法是“实现了现代 Agent CLI 常见的输入编辑、历史、上下文补全、实时高亮和状态栏”。

## 6. 这些选型背后的通用决策方法

图片中的所有能力看似分散，实际上可以归纳为六条工程决策原则。

### 6.1 先判断产品形态，再选技术

Lincode 是本地单用户 CLI，因此优先：

- SQLite，而不是先部署 PostgreSQL、Redis 和 Kafka。
- JLine，而不是先做 Web 前端。
- JGit Side-Git，而不是云端版本系统。
- 本地工具和 MCP，而不是所有能力都远程化。

如果产品变成多人在线 Agent 平台，这些答案会改变。技术选型没有脱离业务前提的绝对正确。

### 6.2 高频、稳定、核心能力内置；长尾能力协议化

- 文件、目录、命令和精确搜索是每个编码任务都需要的，放进 ToolRegistry。
- 浏览器、第三方服务和外部数据源变化多，通过 MCP 扩展。

判断标准是：

1. 使用频率是否足够高。
2. 是否必须离线可用。
3. 是否需要核心项目承担兼容责任。
4. 是否有成熟标准协议可以复用。

### 6.3 轻量路径优先，昂贵能力兜底

项目多次使用这一模式：

- Glob/grep/read 优先，RAG 兜底。
- HTTP `web_fetch` 优先，浏览器兜底。
- DOM snapshot 优先，视觉截图兜底。
- ReAct 优先，Plan 或 Multi-Agent 处理复杂任务。
- SQLite 优先，暂不引入大型分布式基础设施。

这能同时控制延迟、Token、费用和故障面。

### 6.4 决策交给模型，执行和边界留给程序

适合交给模型的内容：

- 当前该读哪个文件。
- 是否需要联网。
- 如何拆分任务。
- 下一步调用什么工具。

必须由程序控制的内容：

- 路径是否越界。
- 命令是否明显危险。
- 用户是否批准。
- 超时、并发和循环上限。
- 工具结果的协议顺序。
- 快照、审计和恢复。

大模型擅长模糊决策，不擅长作为可靠的安全边界。

### 6.5 先做正确性，再做并发

项目的并发都带有约束：

- Tool Call 并行但结果保序。
- DAG 只并发依赖已满足的任务。
- Worker 通过队列保证实例独占。
- 并行输出先缓冲再按步骤展示。

这体现了一个重要原则：并发不能破坏协议、状态和用户可读性。

### 6.6 把“功能存在”和“生产级完备”分开

下面这些能力已经存在，但仍有明显演进空间：

- RAG 有向量检索，但还是线性扫描。
- LSP 有 Java 语法诊断，但不是完整 Language Server。
- Runtime API 有 Thread/Turn/Event，但不是多租户平台。
- NetworkPolicy 有基础 SSRF 防护，但不是完整企业网络沙箱。
- MCP 支持核心协议，但缺 OAuth、sampling 和自动恢复。
- 浏览器能复用 Chrome，但共享登录态仍是高风险操作。

优秀的项目讲解不是把所有功能说成“生产级”，而是能说明当前阶段、边界和下一步为什么这样演进。

## 7. 一个可复用的技术选型回答模板

以后面试官问“为什么选 SQLite、JLine、ReAct、DAG 或 MCP”时，可以使用下面的回答顺序：

1. **先说业务约束**：这是本地单用户 Java CLI，需要零运维、低启动成本和可离线运行。
2. **再说核心矛盾**：例如任务要持久化，但不值得部署独立数据库。
3. **给出候选方案**：内存、JSON、SQLite、Redis。
4. **明确评价维度**：部署、事务、并发、规模、维护成本。
5. **说明为什么当前选择最合适**：SQLite 在本地任务量下平衡最好。
6. **承认边界**：如果变成多实例分布式服务，会改用带租约的队列或消息系统。
7. **说明演进路径**：先抽象接口，规模变化后替换实现。

以 RAG 为例，可以这样回答：

> 代码检索同时有精确符号查询和模糊业务语义查询两类需求。ripgrep 对已知类名和字符串更快、更准确，而且不需要索引，所以我把 glob/grep/read 作为默认路径；只有关键词不明确时才调用向量 RAG。RAG 使用 JavaParser 保留类和方法边界，再融合余弦相似度与 Jieba 关键词结果。当前产品是单机 CLI，中小代码库优先零运维，因此向量先以 JSON 存 SQLite、在内存线性计算；如果代码块增长到数十万级，再演进到 HNSW、FAISS 或 pgvector，而不是一开始就引入重型基础设施。

这个回答同时包含了业务、实现、对比、取舍和演进，比只说“因为 SQLite 轻量”更能体现项目理解。

## 8. 总结

两张图片真正覆盖的是一套完整 Agent 工程体系：

- ReAct 和 Function Calling 解决模型如何行动。
- Plan、DAG 和 Multi-Agent 解决复杂任务如何组织。
- ToolRegistry 和 MCP 解决能力如何扩展。
- Memory、Compactor 和 TokenBudget 解决长对话如何持续。
- glob/grep/read 与 RAG 解决代码库如何理解。
- web_fetch、BrowserSession 和 CDP 解决网页与登录态如何访问。
- HITL、NetworkPolicy、审计和 Side-Git 解决风险如何控制。
- SQLite、ExecutorService、JLine、OkHttp、Jackson 等负责把 Agent 算法变成一个可运行的 Java 产品。

学习时不要只记技术名词。对每个模块都应追问：

1. 它解决了哪个真实用户问题？
2. 为什么更轻的方案不够？
3. 为什么更重的方案现在没有必要？
4. 失败后会发生什么？
5. 当规模或产品形态变化时，下一步怎样演进？

能逐条回答这五个问题，才算真正理解了 Lincode 的技术选型和工程取舍。
