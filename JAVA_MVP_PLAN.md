# Java MVP 方案 — Akashic Agent 复刻

## 背景

用 Java 复刻 akashic-agent 的核心功能，打造一个个人 AI 伴侣。核心能力：
1. **被动对话** — 回应用户消息
2. **记忆系统** — 跨对话记忆（5 层 Markdown 记忆）
3. **主动聊天** — 基于能量模型 + LLM Judge 自动推送消息

**预计规模**：~3000-4000 行 Java，2-3 周完成。

---

## 技术栈

| 组件 | 技术选型 |
|------|---------|
| 框架 | Spring Boot 3.3+ |
| LLM 调用 | Spring AI（兼容 OpenAI 协议：DeepSeek/OpenAI/Gemini/通义千问） |
| 数据库 | SQLite（sqlite-jdbc + Spring JDBC） |
| 并发模型 | Java 21 Virtual Threads |
| 构建工具 | Maven |
| 交互方式 | CLI 控制台（后期加 Telegram） |

---

## 项目结构

```
loki-agent/
├── pom.xml
├── src/main/java/com/loki/agent/
│   ├── LokiAgentApplication.java            # Spring Boot 启动入口
│   │
│   ├── config/
│   │   ├── AgentConfig.java                 # application.yml 配置绑定
│   │   └── AppConfig.java                   # Bean 定义
│   │
│   ├── bus/
│   │   ├── MessageBus.java                  # 双队列：入站/出站消息
│   │   ├── InboundMessage.java              # 入站消息 POJO
│   │   └── OutboundMessage.java             # 出站消息 POJO
│   │
│   ├── agent/
│   │   ├── AgentLoop.java                   # 主循环：轮询 bus → 处理 → 回复
│   │   ├── PassiveTurnPipeline.java         # 6 阶段对话流水线
│   │   ├── Reasoner.java                    # ReAct 工具调用循环
│   │   └── ContextBuilder.java              # 组装 system prompt + 消息数组
│   │
│   ├── llm/
│   │   ├── LlmProvider.java                 # Spring AI 封装：chat() + 重试
│   │   └── LlmResponse.java                 # 响应 POJO
│   │
│   ├── tool/
│   │   ├── Tool.java                        # 抽象基类：name, description, parameters, execute()
│   │   ├── ToolRegistry.java                # 注册 + 查找 + 执行
│   │   ├── ToolCall.java                    # 工具调用 POJO
│   │   └── tools/
│   │       ├── ReadFileTool.java            # 读文件
│   │       ├── WriteFileTool.java           # 写文件
│   │       ├── EditFileTool.java            # 编辑文件
│   │       ├── ListDirTool.java             # 列目录
│   │       ├── WebSearchTool.java           # 网页搜索（桩）
│   │       └── MemoryTool.java              # 记忆工具（memorize/recall/forget）
│   │
│   ├── memory/
│   │   ├── MemoryStore.java                 # 5 层 Markdown 文件读写
│   │   └── MemoryConsolidator.java          # LLM 驱动的记忆合并
│   │
│   ├── session/
│   │   ├── Session.java                     # 会话 POJO
│   │   ├── SessionManager.java              # 会话管理器
│   │   └── SessionStore.java                # SQLite CRUD
│   │
│   ├── proactive/
│   │   ├── ProactiveLoop.java               # 主动循环
│   │   ├── EnergyModel.java                 # 能量模型
│   │   ├── Judge.java                       # 推送决策器
│   │   ├── Sensor.java                      # 环境感知器
│   │   └── ProactiveConfig.java             # 主动系统配置
│   │
│   ├── channel/
│   │   ├── Channel.java                     # 渠道接口
│   │   └── CliChannel.java                  # CLI 控制台实现
│   │
│   └── prompt/
│       ├── PromptTemplates.java             # 所有 prompt 模板
│       └── SystemPromptBuilder.java         # System Prompt 组装器
│
└── src/main/resources/
    ├── application.yml
    └── schema.sql                           # SQLite 建表语句
```

---

## 实现阶段

### Phase 1：骨架搭建（第 1-2 天）

**目标**：项目能编译，Spring Boot 能启动，CLI 能读写输入。

#### 1.1 pom.xml
- Spring Boot 3.3 parent
- `spring-ai-openai-spring-boot-starter`（LLM 调用）
- `sqlite-jdbc`（数据库）
- `spring-boot-starter`（核心）
- Java 21 编译器配置

#### 1.2 application.yml
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        model: deepseek-chat
        options:
          max-tokens: 4096
server:
  port: 0  # 不启动 Web 服务器

loki:
  agent:
    workspace: ~/.loki-agent/workspace
    max-iterations: 10
```

#### 1.3 LokiAgentApplication.java
- `@SpringBootApplication` 启动类
- Main 方法启动 `AgentLoop`

#### 1.4 MessageBus.java
```java
public class MessageBus {
    private final BlockingQueue<InboundMessage> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<OutboundMessage> outbound = new LinkedBlockingQueue<>();

    public void publishInbound(InboundMessage msg);
    public InboundMessage consumeInbound() throws InterruptedException; // 阻塞等待
    public void publishOutbound(OutboundMessage msg);
    public OutboundMessage consumeOutbound() throws InterruptedException;
}
```

#### 1.5 CliChannel.java
```java
@Component
public class CliChannel implements Channel {
    private final Scanner scanner = new Scanner(System.in);

    @Override
    public void start(MessageBus bus) {
        // 线程 1：读 stdin → publishInbound
        // 线程 2：consumeOutbound → println
    }
}
```

#### 1.6 消息 POJO
- `InboundMessage`：channel, sender, chatId, content, timestamp, media, metadata
- `OutboundMessage`：channel, chatId, content, replyTo
- `sessionKey()` = `channel + ":" + chatId`

**Commit**: `feat: skeleton — Spring Boot app with CLI channel and MessageBus`

---

### Phase 2：LLM + 工具系统（第 3-5 天）

**目标**：Agent 能调用 LLM，能执行工具（ReAct 循环）。

#### 2.1 LlmProvider.java
```java
@Component
public class LlmProvider {
    private final ChatClient chatClient;  // Spring AI 注入

    public LlmResponse chat(List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools,
                            String model, int maxTokens);
    // 429/5xx 指数退避重试
}
```

Spring AI 处理 HTTP、流式传输、重试。我们封装一下返回 `LlmResponse`。

#### 2.2 Tool.java（抽象基类）
```java
public abstract class Tool {
    public abstract String name();
    public abstract String description();
    public abstract Map<String, Object> parameters();  // JSON Schema

    public abstract String execute(Map<String, Object> args);

    public Map<String, Object> toSchema() {
        return Map.of("type", "function", "function",
            Map.of("name", name(), "description", description(), "parameters", parameters()));
    }
}
```

#### 2.3 ToolRegistry.java
```java
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool);
    public List<Map<String, Object>> getSchemas();                    // 全部工具 → OpenAI 格式
    public String execute(String name, Map<String, Object> args);     // 查找 + 调用 + 异常捕获
}
```

#### 2.4 内置工具
| 工具 | 功能 |
|------|------|
| ReadFileTool | 路径校验 + `Files.readString()` + 行号标注 |
| WriteFileTool | 路径校验 + `Files.writeString()` |
| EditFileTool | 读取 → 替换 oldText → 写回 |
| ListDirTool | `Files.list()` → 排序输出 |
| MemoryTool | memorize/recall/forget，操作 MemoryStore |

#### 2.5 Reasoner.java — ReAct 循环
```java
@Component
public class Reasoner {
    public ReasonerResult run(List<Map<String, Object>> messages,
                              List<Map<String, Object>> toolSchemas) {
        // for iteration = 0..maxIterations:
        //   1. LlmResponse resp = llmProvider.chat(messages, tools, ...)
        //   2. if resp.toolCalls 不为空:
        //        - 追加 assistant 消息（含 tool_calls）
        //        - 逐个执行 tool_call，追加工具结果
        //        - 继续循环
        //   3. else: 返回 ReasonerResult(reply, invocations, thinking)
        // 达到上限：强制总结
    }
}
```

**Commit**: `feat: LLM provider with Spring AI, Tool system, ReAct loop`

---

### Phase 3：会话 + 记忆（第 6-8 天）

**目标**：对话跨重启持久化，Agent 有记忆能力。

#### 3.1 SessionStore.java — SQLite CRUD
```java
@Component
public class SessionStore {
    private final DataSource dataSource;  // sqlite-jdbc

    public void upsertSession(String key, Instant now, String metadataJson);
    public void insertMessage(String sessionKey, int seq, String role, String content,
                              String toolChainJson, String extraJson, String ts);
    public List<Map<String, Object>> fetchMessages(String sessionKey);
    public void updatePresence(String sessionKey, Instant lastUserAt);
    public Instant mostRecentUserAt(String sessionKey);
}
```

建表语句（schema.sql）：
```sql
CREATE TABLE IF NOT EXISTS sessions (
    key TEXT PRIMARY KEY,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    last_consolidated INTEGER DEFAULT 0,
    metadata TEXT,
    last_user_at TEXT,
    next_seq INTEGER DEFAULT 0
);

CREATE TABLE IF NOT EXISTS messages (
    id TEXT PRIMARY KEY,
    session_key TEXT NOT NULL,
    seq INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT,
    tool_chain TEXT,
    extra TEXT,
    ts TEXT NOT NULL,
    UNIQUE(session_key, seq)
);
```

#### 3.2 Session.java + SessionManager.java
```java
@Component
public class SessionManager {
    private final Map<String, Session> cache = new ConcurrentHashMap<>();
    private final SessionStore store;

    public Session getOrCreate(String key);
    public void appendMessages(Session session, List<Map<String, Object>> messages);
    // Session.get_history(): 展开消息为 OpenAI 格式
    //   - tool_chain → assistant + tool_calls + tool results
    //   - 工具结果截断到 10000 字符
}
```

#### 3.3 MemoryStore.java — 5 层 Markdown
```java
@Component
public class MemoryStore {
    private final Path memoryDir;  // {workspace}/memory/

    // 第 1 层：MEMORY.md（长期记忆）
    public String readLongTerm();
    public void writeLongTerm(String content);

    // 第 2 层：SELF.md（自我模型）
    public String readSelf();
    public void writeSelf(String content);

    // 第 3 层：PENDING.md（增量事实）
    public String readPending();
    public void appendPending(String facts);

    // 第 4 层：HISTORY.md（只追加的事件日志）
    public void appendHistory(String entry);

    // 第 5 层：RECENT_CONTEXT.md（压缩的近期上下文）
    public String readRecentContext();
    public void writeRecentContext(String content);

    // 日记
    public void appendJournal(String entry);  // journal/YYYY-MM-DD.md

    // 上下文注入
    public String getMemoryContext();  // "## Long-term Memory\n{content}"
}
```

**Commit**: `feat: session persistence with SQLite, 5-layer Markdown memory`

---

### Phase 4：上下文组装 + 被动对话（第 9-11 天）

**目标**：完整的被动对话流水线 — 用户消息 → 上下文组装 → LLM → 回复。

#### 4.1 SystemPromptBuilder.java
组装 system prompt（简化版，不使用 Phase 引擎）：

```java
@Component
public class SystemPromptBuilder {
    public String build(String memoryContext, String selfModel, String recentContext,
                        String sessionInfo, String skillsCatalog) {
        // 按顺序拼接：
        // 1. 身份（来自 PromptTemplates）
        // 2. 行为规则
        // 3. 自我模型（SELF.md）
        // 4. 长期记忆（MEMORY.md）
        // 5. 会话上下文（channel, chat_id, 时间）
        return identity + rules + selfModel + memoryContext + sessionInfo;
    }
}
```

#### 4.2 ContextBuilder.java
```java
@Component
public class ContextBuilder {
    public ContextResult build(Session session, InboundMessage msg,
                               String memoryBlock, String skillsInfo) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // [0] system prompt
        String systemPrompt = systemPromptBuilder.build(...);
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // [1..N] 历史消息
        messages.addAll(session.getHistory(50));

        // [N+1] 上下文帧（system-reminder 包裹）
        String contextFrame = buildContextFrame(skillsInfo, recentContext, memoryBlock);
        messages.add(Map.of("role", "user", "content", contextFrame));

        // [N+2] 用户消息（带时间戳）
        messages.add(Map.of("role", "user", "content",
            "[" + msg.timestamp() + "]\n" + msg.content()));

        return new ContextResult(systemPrompt, messages);
    }
}
```

#### 4.3 PassiveTurnPipeline.java — 简化 6 阶段
```java
@Component
public class PassiveTurnPipeline {
    public OutboundMessage run(InboundMessage msg) {
        // 阶段 1：BeforeTurn — 获取会话，准备上下文
        Session session = sessionManager.getOrCreate(msg.sessionKey());
        String memoryBlock = memoryStore.getMemoryContext();

        // 阶段 2：BeforeReasoning — 组装消息数组
        ContextResult ctx = contextBuilder.build(session, msg, memoryBlock, null);

        // 阶段 3-4：Reasoning — ReAct 循环
        ReasonerResult result = reasoner.run(ctx.messages(), toolRegistry.getSchemas());

        // 阶段 5：AfterReasoning — 持久化
        session.addMessage("user", msg.content());
        session.addMessage("assistant", result.reply());
        sessionManager.appendMessages(session, session.getUnsavedMessages());

        // 阶段 6：AfterTurn — 构建出站消息
        return new OutboundMessage(msg.channel(), msg.chatId(), result.reply(), null);
    }
}
```

#### 4.4 AgentLoop.java — 主循环
```java
@Component
public class AgentLoop implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        // 启动 CLI 渠道
        cliChannel.start(bus);

        // 虚拟线程主循环
        Thread.startVirtualThread(() -> {
            while (running) {
                InboundMessage msg = bus.consumeInbound();
                try {
                    OutboundMessage reply = pipeline.run(msg);
                    bus.publishOutbound(reply);
                } catch (Exception e) {
                    bus.publishOutbound(new OutboundMessage(
                        msg.channel(), msg.chatId(), "Error: " + e.getMessage(), null));
                }
            }
        });
    }
}
```

**Commit**: `feat: full passive turn — context assembly, ReAct loop, session persistence`

---

### Phase 5：主动系统（第 12-15 天）

**目标**：Agent 根据能量模型 + LLM Judge 主动找用户聊天。

#### 5.1 EnergyModel.java
```java
@Component
public class EnergyModel {
    // E(t) = 0.50*exp(-t/30) + 0.35*exp(-t/240) + 0.15*exp(-t/2880)
    public double computeEnergy(long minutesSinceLastUser);

    public double dEnergy(double energy);          // 1 - energy（饥饿度）
    public double dContent(int newItems);          // 1 - exp(-newItems / 3.0)（内容新鲜度）
    public double dRecent(int msgCount);           // log(1+k) / log(1+10)（上下文丰富度）

    public double compositeScore(double de, double dc, double dr);
    // 权重：w_e=0.40, w_c=0.40, w_r=0.20

    public int nextTickFromScore(double score);
    // score>0.70 → 420s, >0.40 → 1080s, >0.20 → 2400s, else → 4800s（±30% 抖动）
}
```

#### 5.2 Judge.java
```java
@Component
public class Judge {
    // 确定性否决：balance < 0.1 → 直接不推
    public boolean preComposeVeto(int sentToday, int dailyMax);

    // 完整判断：
    // 1. 确定性维度：urgency, balance, dynamics
    // 2. LLM 维度：information_gap, relevance, expected_impact（让 LLM 打 1-5 分）
    // 3. 加权计算最终分数
    // 4. final >= 0.60 → shouldSend = true
    public JudgeResult judgeMessage(String candidateMessage, List<String> recentProactive,
                                     double interruptFactor, long ageHours);
}
```

#### 5.3 Sensor.java
```java
@Component
public class Sensor {
    public double computeInterruptibility(double replyFactor, double activityFactor,
                                          double fatigueFactor);
    // 可打扰度 = 回复衰减 × 活跃度 × (1 - 疲劳度) × 随机抖动
}
```

#### 5.4 ProactiveLoop.java
```java
@Component
public class ProactiveLoop {
    // 在独立的虚拟线程中运行
    public void start() {
        Thread.startVirtualThread(() -> {
            while (running) {
                double baseScore = tick();
                int interval = energyModel.nextTickFromScore(baseScore);
                Thread.sleep(interval * 1000L);
            }
        });
    }

    private double tick() {
        // 1. 根据最后互动时间计算能量
        // 2. 计算可打扰度
        // 3. composite_score → baseScore
        // 4. if baseScore > 0.40:
        //      a. preComposeVeto 检查
        //      b. LLM 生成候选消息
        //      c. judge.judgeMessage → finalScore
        //      d. if finalScore >= 0.60: 通过 MessageBus 推送
        return baseScore;
    }
}
```

**Commit**: `feat: proactive system — energy model, judge, proactive loop`

---

### Phase 6：记忆合并（第 16-18 天）

**目标**：每次对话后，LLM 自动提取事实并合并到长期记忆。

#### 6.1 MemoryConsolidator.java
```java
@Component
public class MemoryConsolidator {
    // 每次被动对话完成后调用
    public void consolidate(Session session) {
        // 1. 检查自 last_consolidated 以来是否有足够的新消息
        // 2. 提取最近 10-20 轮消息
        // 3. 调用 LLM："从这段对话中提取关键事实"
        // 4. 将提取的事实追加到 PENDING.md
        // 5. 如果 PENDING.md 足够大：
        //      a. snapshot_pending（重命名为 .snapshot）
        //      b. 调用 LLM："将这些事实合并到 MEMORY.md"
        //      c. commit_pending_snapshot（删除 .snapshot）
        //      d. 将摘要追加到 HISTORY.md
    }
}
```

**Commit**: `feat: LLM-driven memory consolidation — PENDING -> MEMORY/HISTORY`

---

## 数据流图

```
┌─────────────┐     ┌─────────────┐     ┌───────────────────┐
│  CliChannel │────>│  MessageBus │────>│   AgentLoop.run() │
│  (stdin)    │<────│  (队列)      │<────│   (虚拟线程)       │
└─────────────┘     └─────────────┘     └───────┬───────────┘
                                                │
                                    ┌───────────▼───────────┐
                                    │ PassiveTurnPipeline   │
                                    │  1. Session.getOrCreate│
                                    │  2. MemoryStore.read   │
                                    │  3. ContextBuilder     │
                                    │  4. Reasoner.run()     │
                                    │  5. 持久化到 SQLite    │
                                    │  6. MemoryConsolidator │
                                    └───────────┬───────────┘
                                                │
                    ┌───────────────────────────▼───────────┐
                    │         Reasoner（ReAct 循环）          │
                    │  ┌─────────────────────────────────┐  │
                    │  │ LLM.chat(messages, tools)       │  │
                    │  │   有 tool_calls → 执行工具       │  │
                    │  │   循环直到没有 tool_calls        │  │
                    │  └─────────────────────────────────┘  │
                    └───────────────────────────────────────┘

        ┌──────────────────────────────────────────────────┐
        │          ProactiveLoop（独立线程）                  │
        │  sleep(interval) → tick()                         │
        │    EnergyModel → Sensor → Judge → 推送消息        │
        └──────────────────────────────────────────────────┘
```

---

## MVP 刻意跳过的功能

| Python 原功能 | MVP 跳过原因 |
|---------------|-------------|
| EventBus（emit/observe/fanout） | 不需要插件系统，直接方法调用 |
| 生命周期 Phase 引擎（18 个 dataclass + 拓扑排序） | PassiveTurnPipeline 顺序代码就够了 |
| 插件系统（`@tool` 装饰器、动态加载） | 工具直接硬编码注册 |
| SubAgent + SubagentManager | 个人使用不需要 |
| MCP 协议（JSON-RPC over stdio） | MVP 不需要 |
| A2A 对等代理 | MVP 不需要 |
| 向量记忆（sqlite-vec、embedding） | MEMORY.md 文本注入足够 |
| Telegram 渠道 | 先用 CLI，后续再加 |
| 技能系统（SKILL.md 加载器） | 行为规则硬编码在 PromptTemplates |
| HistoryRoutePolicy（LLM 路由） | 始终进行记忆检索 |
| 工具钩子（pre/post） | 直接执行 |
| 流式响应（SSE delta） | 返回完整响应 |
| 中断/恢复机制 | MVP 不需要 |

---

## 后续扩展路线图（MVP 之后怎么加）

MVP 不是临时方案，而是"最小可用版本"。每个跳过的功能都有明确的加装路径，不需要推翻重来。

### 扩展总览

```
当前 MVP 架构已留好扩展口：

ToolRegistry    ← 插件 / MCP / SubAgent 都往这里注册工具
PassiveTurnPipeline ← EventBus / Phase 引擎 都是把顺序调用改成事件调用
Channel 接口    ← Telegram / 微信 / 钉钉 都是实现这个接口
MemoryStore     ← 向量记忆只是换一种检索方式，注入到同一个 ContextBuilder
Reasoner        ← SubAgent 复用同一个 ReAct 循环，只是参数不同
```

### 扩展 1：向量记忆（难度：★☆☆☆☆，1-2 天）

**最简单的扩展**，完全不影响现有代码结构。

```java
// 新增文件：memory/VectorMemoryStore.java
@Component
public class VectorMemoryStore {
    // 依赖：sqlite-vec 扩展（JNI 加载）
    // 方法：
    public void embed(String text);              // 文本 → 向量 → 存入 SQLite
    public List<String> search(String query, int topK);  // 查询 → 余弦相似度 → 返回 top-k
}
```

**改动点**：
- `ContextBuilder.build()` 里增加一步：调用 `vectorMemoryStore.search(msg.content())`，把结果注入 context frame
- `MemoryConsolidator` 里增加一步：合并完成后调用 `vectorMemoryStore.embed()` 生成向量
- 其他代码不动

**为什么容易**：MVP 的 `ContextBuilder` 已经有 `memoryBlock` 参数，向量检索结果只是多一个 block 塞进去。

---

### 扩展 2：Telegram 渠道（难度：★★☆☆☆，2-3 天）

```java
// 新增文件：channel/TelegramChannel.java
@Component
public class TelegramChannel implements Channel {
    // 依赖：Telegram Bot API（用 HttpClient 调用）
    // 实现 Channel 接口的 start() / send() 方法

    @Override
    public void start(MessageBus bus) {
        // 启动长轮询（getUpdates）
        // 收到消息 → publishInbound
    }

    @Override
    public void send(OutboundMessage msg) {
        // 调用 Telegram sendMessage API
    }
}
```

**改动点**：
- `application.yml` 加 Telegram bot token 配置
- `AppConfig` 加 `@Bean TelegramChannel`
- 其他代码不动

**为什么容易**：MVP 的 `Channel` 接口已经抽象好了，Telegram 只是另一个实现。

---

### 扩展 3：技能系统（难度：★★☆☆☆，1-2 天）

```java
// 新增文件：skill/SkillsLoader.java
@Component
public class SkillsLoader {
    public List<Skill> listSkills();                    // 扫描 workspace/skills/ 目录
    public String loadSkill(String name);               // 读取 SKILL.md 内容
    public Map<String, String> getMetadata(String name); // 解析 YAML frontmatter
    public String buildSkillsSummary();                 // 生成 XML 目录注入 system prompt
}
```

**改动点**：
- `SystemPromptBuilder.build()` 里增加 skills 参数（已有占位）
- `ContextBuilder.build()` 里调用 `skillsLoader.buildSkillsSummary()`
- `Reasoner` 里增加"always=true"的技能常驻注入

---

### 扩展 4：EventBus 事件系统（难度：★★★☆☆，2-3 天）

```java
// 新增文件：bus/EventBus.java
@Component
public class EventBus {
    private final Map<Class<?>, List<Consumer<?>>> handlers = new ConcurrentHashMap<>();

    public <T> void on(Class<T> eventType, Consumer<T> handler);
    public <T> void emit(T event);       // 顺序拦截链
    public <T> void fanout(T event);     // 并发广播
    public <T> void enqueue(T event);    // 异步队列
}
```

**改动点**：
- `PassiveTurnPipeline.run()` 每个阶段前后加 `eventBus.emit(new BeforeTurnEvent(...))` / `eventBus.emit(new AfterTurnEvent(...))`
- 其他代码可以通过 `eventBus.on(AfterTurnEvent.class, ...)` 注册监听
- 不影响主循环逻辑

**为什么中等难度**：需要在 PassiveTurnPipeline 里插入事件发布点，但不影响核心流程。

---

### 扩展 5：插件系统（难度：★★★★☆，3-4 天）

**依赖 EventBus**，需要先完成扩展 4。

```java
// 新增文件：plugin/
├── Plugin.java                    # 抽象基类，提供生命周期钩子
├── PluginManager.java             # 发现、加载、管理插件
├── PluginContext.java             # 注入到每个插件的上下文
└── annotations/
    ├── OnTool.java                # @OnTool 注解，注册新工具
    ├── OnBeforeTurn.java          # @OnBeforeTurn 生命周期钩子
    └── OnAfterTurn.java           # @OnAfterTurn 生命周期钩子
```

```java
// 插件示例：plugins/MyPlugin.java
public class MyPlugin extends Plugin {
    @OnTool(name = "custom_search", description = "自定义搜索")
    public String customSearch(@Param("query") String query) {
        return "搜索结果: " + query;
    }

    @OnAfterTurn
    public void logTurn(AfterTurnEvent event) {
        System.out.println("Turn completed: " + event.getTurnId());
    }
}
```

**改动点**：
- `ToolRegistry` 增加 `registerFromPlugin(Tool tool)` 方法（或复用已有 `register()`）
- `AppConfig` 启动时调用 `pluginManager.discover("plugins/")` 扫描目录
- 用 `ServiceLoader` 或 `@ComponentScan` 自动发现插件类

**为什么较难**：需要实现注解扫描、动态类加载、插件生命周期管理。

---

### 扩展 6：MCP 协议（难度：★★★☆☆，2-3 天）

```java
// 新增文件：mcp/
├── McpClient.java                 # stdio 子进程 + JSON-RPC 2.0
├── McpServerRegistry.java         # 管理多个 MCP 服务连接
└── McpToolWrapper.java            # 远程工具 → 本地 Tool 接口适配
```

```java
// McpClient 核心流程
public class McpClient {
    public void connect(String command, List<String> args) {
        // 启动子进程 → 发送 initialize → 收到 initialized → 调用 tools/list
    }

    public String call(String toolName, Map<String, Object> args) {
        // JSON-RPC: tools/call → 返回结果
    }
}
```

**改动点**：
- `ToolRegistry` 的 `register()` 已经支持动态注册，MCP 工具注册进去就行
- `AppConfig` 从 `mcp_servers.json` 读取配置，启动时连接
- 其他代码不动

**为什么中等难度**：需要实现 JSON-RPC 协议和子进程管理，但对现有代码零侵入。

---

### 扩展 7：SubAgent 子代理（难度：★★★☆☆，2-3 天）

```java
// 新增文件：agent/SubAgent.java
public class SubAgent {
    // 复用 Reasoner，但使用独立的工具集和 system prompt
    private final Reasoner reasoner;
    private final List<Tool> tools;          // 限定工具集
    private final String systemPrompt;
    private final int maxIterations;

    public String run(String task) {
        // 1. 构建独立的消息数组
        // 2. 调用 reasoner.run()（与主 Agent 相同的 ReAct 循环）
        // 3. 返回结果
    }
}

// 新增文件：agent/SubAgentManager.java
@Component
public class SubAgentManager {
    public String spawnSync(String profile, String task);   // 同步执行
    public CompletableFuture<String> spawn(String profile, String task); // 异步执行
    // profile: "research" / "scripting" / "general"
}
```

**改动点**：
- `Reasoner` 已经是独立组件，SubAgent 直接复用
- `AgentLoop` 里增加对 `SpawnCompletionItem` 的处理
- 结果通过 `MessageBus.publishInbound()` 回传

**为什么中等难度**：Reasoner 已经是独立的，SubAgent 只是创建新的实例并传不同的参数。

---

### 扩展 8：A2A 对等代理（难度：★★★★☆，3-4 天）

```java
// 新增文件：peer/
├── AgentCard.java                 # /.well-known/agent.json 解析
├── PeerAgentRegistry.java         # 启动时发现所有对等代理
├── PeerAgentTool.java             # 远程代理 → 本地 Tool 接口
├── PeerAgentPoller.java           # 后台轮询任务状态
└── PeerProcessManager.java        # 子进程生命周期管理
```

**改动点**：
- `PeerAgentTool` 注册到 `ToolRegistry`
- `AgentLoop` 里处理异步任务回传
- 配置文件加 peer agent 列表

---

### 扩展 9：工具钩子系统（难度：★★☆☆☆，1-2 天）

```java
// 新增文件：tool/ToolHook.java
public interface ToolHook {
    boolean matches(String toolName);
    HookOutcome run(HookContext ctx);  // APPROVE / DENY / MODIFY
}

// 修改文件：ToolRegistry.java
public String execute(String name, Map<String, Object> args) {
    // 新增：执行前遍历 preHooks
    for (ToolHook hook : preHooks) {
        if (hook.matches(name)) {
            HookOutcome outcome = hook.run(ctx);
            if (outcome.isDenied()) return "Blocked: " + outcome.reason();
        }
    }
    String result = tool.execute(args);
    // 新增：执行后遍历 postHooks
    for (ToolHook hook : postHooks) { ... }
    return result;
}
```

---

### 扩展 10：流式响应（难度：★★★☆☆，2-3 天）

```java
// 修改文件：LlmProvider.java
public Flux<String> chatStreaming(List<Map<String, Object>> messages, ...) {
    return chatClient.prompt()
        .messages(messages)
        .stream()
        .content();
}

// 修改文件：CliChannel.java
// 每收到一个 delta，实时打印到控制台（不等完整回复）
```

---

### 扩展优先级建议

| 顺序 | 功能 | 难度 | 天数 | 价值 |
|------|------|------|------|------|
| 1 | 向量记忆 | ★☆☆☆☆ | 1-2 | 检索更精准 |
| 2 | Telegram 渠道 | ★★☆☆☆ | 2-3 | 随时随地用 |
| 3 | 技能系统 | ★★☆☆☆ | 1-2 | 可定制行为 |
| 4 | 工具钩子 | ★★☆☆☆ | 1-2 | 插件化的基础 |
| 5 | EventBus | ★★★☆☆ | 2-3 | 插件化的前提 |
| 6 | MCP 协议 | ★★★☆☆ | 2-3 | 接入外部工具 |
| 7 | SubAgent | ★★★☆☆ | 2-3 | 后台任务能力 |
| 8 | 插件系统 | ★★★★☆ | 3-4 | 完整扩展性 |
| 9 | 流式响应 | ★★★☆☆ | 2-3 | 用户体验提升 |
| 10 | A2A 对等代理 | ★★★★☆ | 3-4 | 多 Agent 协作 |

**全部加完预计再花 3-4 周**，但核心循环（AgentLoop → PassiveTurnPipeline → Reasoner）始终不变。

---

## 验证计划

每个阶段完成后验证：

| 阶段 | 验证方式 |
|------|---------|
| Phase 1 | `mvn spring-boot:run` → 应用启动，CLI 接受输入并回显 |
| Phase 2 | 发送 "hello" → LLM 回复；发送 "read file X" → 工具执行并显示结果 |
| Phase 3 | 重启应用 → 加载之前的对话历史；记忆事实 → 检查 MEMORY.md |
| Phase 4 | 完整对话流程（system prompt + 历史 + 记忆 → LLM → 回复） |
| Phase 5 | 等待 7+ 分钟 → Agent 主动发送消息 |
| Phase 6 | 对话后 → MEMORY.md 中出现提取的事实 |

### 端到端测试

```
$ mvn spring-boot:run
> My name is Loki, I'm a Java developer
Agent: Nice to meet you, Loki! I'll remember that.
> /stop
$ cat ~/.loki-agent/workspace/memory/MEMORY.md
## Long-term Memory
- User's name is Loki
- User is a Java developer
$ mvn spring-boot:run  # 重启
> Do you remember me?
Agent: Of course! You're Loki, a Java developer.
```

---

## 工作量预估

### MVP 阶段

| 阶段 | 天数 | 代码行数 |
|------|------|---------|
| 1. 骨架搭建 | 1-2 | ~300 |
| 2. LLM + 工具 | 2-3 | ~800 |
| 3. 会话 + 记忆 | 2-3 | ~700 |
| 4. 上下文 + 被动对话 | 2-3 | ~600 |
| 5. 主动系统 | 3-4 | ~500 |
| 6. 记忆合并 | 1-2 | ~300 |
| **MVP 合计** | **~2 周** | **~3200** |

### 扩展阶段

| 功能 | 天数 | 代码行数 |
|------|------|---------|
| 向量记忆 | 1-2 | ~300 |
| Telegram 渠道 | 2-3 | ~500 |
| 技能系统 | 1-2 | ~200 |
| EventBus | 2-3 | ~300 |
| MCP 协议 | 2-3 | ~400 |
| SubAgent | 2-3 | ~400 |
| 插件系统 | 3-4 | ~600 |
| 工具钩子 | 1-2 | ~200 |
| 流式响应 | 2-3 | ~300 |
| A2A 对等代理 | 3-4 | ~500 |
| **扩展合计** | **~3-4 周** | **~3700** |
| **总计** | **~5-6 周** | **~6900** |

---

## 与 Python 原版对比

| 维度 | Python 原版 | Java MVP | Java 完整版 |
|------|-----------|---------|------------|
| 总代码量 | ~23,000 行 | ~3,200 行 | ~6,900 行 |
| 核心功能 | 被动对话 + 记忆 + 主动聊天 | 相同 | 相同 |
| 渠道 | Telegram/CLI/IPC | 仅 CLI | CLI + Telegram |
| 插件 | 完整插件系统 | 无 | 完整插件系统 |
| 子代理 | SubAgent + A2A | 无 | SubAgent + A2A |
| 记忆 | Markdown + 向量 | 仅 Markdown | Markdown + 向量 |
| 工具 | 动态注册 + 搜索 | 静态注册 | 动态注册 + MCP |
| 可扩展性 | 高（插件化） | 中 | 高（插件化） |

**MVP 核心体验与原版完全相同**，用户分不出区别。扩展后功能对齐。
