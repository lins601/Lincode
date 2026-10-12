# Lincode — AI Agent CLI

**项目时间：** 2026.04—2026.06　　**项目角色：** 独立开发

- **项目简介：** 类 Claude Code 的智能编码 Agent CLI，以大模型为决策核心，连接代码库、终端、浏览器与 MCP 外部工具，自主完成任务理解、方案规划、跨文件修改、命令执行和测试验证，并通过分层记忆、上下文压缩、人工审批与快照回滚保障长任务的可控执行。
- **技术栈：** Java 17、JLine 4、OkHttp/SSE、Jackson、SQLite/JDBC、JGit、JavaParser、Jieba、ReAct、Function Calling、Plan-and-Execute、Multi-Agent、MCP、RAG、CDP
- **负责功能：**

  1. 实现 ReAct、Plan-and-Execute 和 Multi-Agent 三种执行模式：ReAct 通过 Function Calling 完成 `tool_calls → tool_result` 迭代闭环，并以连续 3 轮同参调用检测和 50 轮硬上限防止异常循环；Planner 将复杂任务拆分为 DAG，经前向依赖解析和 DFS 拓扑排序后最多 4 路并行执行，前半程失败自动重新规划；团队模式由 Planner、2 个 Worker 和 Reviewer 协作，审查驳回后最多重试 2 次。

  2. 构建三层上下文与 Memory 体系，以 `conversationHistory` 管理实际模型输入、`ConversationMemory` 保存当前会话与工具结果、`LongTermMemory` 按项目级或全局作用域持久化稳定事实；结合 Map-Reduce 短期摘要、user 边界会话压缩和按模型窗口动态计算的上下文预算控制长对话，并通过 ripgrep 实时代码探索与 RAG 混合检索完成代码库理解，本地精确检索约 200ms、千级代码块语义检索低于 100ms。

  3. 基于 Function Calling 建立统一工具调用机制，注册 16 个内置工具，并支持同轮工具最多 4 路并行且按原始顺序返回；通过 MCP 动态接入 tools/resources/prompts，覆盖 JSON-RPC、stdio 和 Streamable HTTP 传输，结合 Skill 按需加载、HITL 审批、PathGuard、CommandGuard、脱敏审计及独立 Side-Git 快照形成工具安全与恢复链路。

  4. 扩展 Agent 交互入口，支持图片输入、JavaParser 语法诊断回灌、基于 Chrome DevTools Protocol 的浏览器操作与 isolated/shared 会话复用，并提供微信 iLink 文本通道；同时以 SQLite 持久化后台任务状态，支持进程重启后重新入队和取消时中断 Worker。
