# spring-boot-ai-agent-starter

一款面向 Spring Boot 的多模型 Agent Starter，统一封装多厂商大模型能力，提供流式、JSON、文件等多种返回形式，内置工具调用、技能扩展、记忆管理、RAG 检索、工作流编排与容错治理。
**使用前提**：项目需基于 JDK 17 或 21、Spring Boot 3.x；密钥、服务端点均由业务方外部配置，SDK 不硬编码任何接入信息。
## 目录

1. [引入依赖](#1-引入依赖)
2. [快速开始](#2-快速开始)
3. [输出方式](#3-输出方式)
4. [使用指南](#4-使用指南)
5. [非 Spring 环境](#5-非-spring-环境)
6. [错误码](#6-错误码)
7. [配置说明](#7-配置说明)

## 1. 引入依赖

### 1.1 环境要求
 JDK 17 及以上（17、21 均可正常使用），Spring Boot 3.x。
### 1.2 Maven 引入

```xml
<dependency>
    <groupId>io.github.j2cuckoo</groupId>
    <artifactId>spring-boot-ai-agent-starter</artifactId>
    <version>2.2.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'io.github.j2cuckoo:spring-boot-ai-agent-starter:2.2.0'
```

以下依赖随之上自动传递，宿主无需添加：

| 传递依赖 | 用途 |
| --- | --- |
| `io.netty:netty-all` | 传输层 |
| `com.fasterxml.jackson.core:jackson-{databind,core,annotations}` | JSON |
| `io.projectreactor:reactor-core`、`org.reactivestreams:reactive-streams` | 响应式 API |
| `org.bouncycastle:bcprov-jdk18on` | 国密 SM3 文件摘要 |
| `org.slf4j:slf4j-api` | 日志门面 |
| `org.springframework.boot:spring-boot-autoconfigure` | 自动装配，版本由宿主 BOM 管理 |

### 1.3 可选依赖

记忆和 RAG 两个能力依赖按需引入，SDK 内标记为 optional：

| 能力 | 依赖 | 开关 |
| --- | --- | --- |
| 永久记忆 | `spring-boot-starter-data-redis` | `memory.enabled`，默认 true，缺 Redis 自动降级 |
| RAG | `io.milvus:milvus-sdk-java:2.4.1` | `rag.enabled`，默认 false |

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>2.4.1</version>
</dependency>
```

### 1.4 依赖问题排查

宿主启动时报 `ClassNotFoundException: io.netty...` 或 Jackson 类找不到，通常是宿主本地仓库里 SDK 的 pom 丢了依赖列表。常见原因是用 `mvn install:install-file` 装包时没带 `-DpomFile`，Maven 会生成一个没有依赖的占位 pom。处理方式：

```bash
# 方式一：在 SDK 工程正常构建，把整个版本目录拷到宿主机器
mvn clean install
# 拷贝 io/github/j2cuckoo/spring-boot-ai-agent-starter/<version>/ 整个目录

# 方式二：手动装包时带 pom
mvn install:install-file -Dfile=spring-boot-ai-agent-starter-1.0.4.jar \
    -DgroupId=io.github.j2cuckoo -DartifactId=spring-boot-ai-agent-starter \
    -Dversion=1.0.4 -Dpackaging=jar -DpomFile=pom.xml
```

检查方法：打开宿主仓库中的 `spring-boot-ai-agent-starter-<version>.pom`，`<dependencies>` 里应有 netty-all、jackson-databind、bcprov 等。

## 2. 快速开始

### 2.1 最小配置

```yaml
spring:
  agent:
    default-model: deepseek:deepseek-chat
    providers:
      - deepseek:
          type: deepseek            # 写厂商名即按 Chat Completions 兼容格式处理并告警
          base-url: https://api.deepseek.com
          api-key: ${DEEPSEEK_API_KEY}
          models:
            - deepseek-chat         # 字符串形式，别名等于真实模型名
```

### 2.2 最小代码

```java
import com.agent.Agent;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
public class ChatController {

    @Autowired Agent agent;

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return agent.chat(q).write();
    }
}
```

Starter 自动装配 `Agent` Bean，宿主不需要 `@Import` 或手工创建。

### 2.3 整体执行流程

SDK 的所有入口（`chat` 单次调用与 AgentGraph 工作流）都遵循同一条执行管线：

```
用户输入
  ↓
系统解析意图（AgentGraph 用 intent 节点显式化；单次调用由拦截器链隐式处理）
  ↓
匹配 / 加载 Skill（SkillManager，内置 calculator/search + 宿主 SPI）
  ↓
Skill 初始化（参数、工具、上下文）
  ↓
Skill 内部加载提示词模板（skill.md / systemTemplate + variable）
  ↓
模板 + 上下文 → 最终 Prompt（拦截器链注入：技能清单、记忆、RAG 召回）
  ↓
调用大模型（协议适配 → Netty 传输 → 事件流）
  ↓
输出结果（write / write(Class) / write(AgentFileRef) / stream().subscribe(...).write()）
```

单次调用中 2-4 步由拦截器链隐式完成（SkillInjectInterceptor 注入技能清单、MemoryInterceptor 注入记忆、RagInterceptor 注入召回片段，均可继承重载）；AgentGraph 中可用 `intent` 节点把意图解析与技能匹配显式编排为独立步骤。

## 3. 输出方式

链式调用一律以 `.write` 结尾。`.write` 之前可以接任意数量的配置方法。默认写法为阻塞式；需要流式时先调 `.stream()` 声明回调，最后仍以 `.write()` 收尾。

### 3.1 阻塞写法

```java
// 返回字符串
String text = agent.chat("Java 17 密封接口的作用?").write();

// 返回反序列化后的对象；输出不合法时自动纠偏重试一次，仍失败抛 JsonOutputException
Weather weather = agent.chat("北京今天天气如何").write(Weather.class);

// 写文件，返回 AgentFile 元数据
AgentFile file = agent.chat("生成季度报告")
        .write(AgentFileRef.of("./output", "report.json"));
```

### 3.2 流式写法

`write()` 是链尾。流式时 `.stream()` 之后、`.write()` 之前用 `.subscribe(...)` 声明回调，可多次叠加；`.write()` 阻塞到事件流消费完毕：

```java
import com.agent.contract.ChatEvent;

agent.chat("写一个快速排序")
        .stream()
        .subscribe(e -> {
            if (e instanceof ChatEvent.TokenEvent t) {
                System.out.print(t.delta());
            }
        })
        .write();

// 结构化回调（onToken/onToolCall/onUsage/onHeartbeat/onComplete/onError）
agent.chat("讲故事")
        .stream()
        .subscribe(new ChatStreamSubscriber() {
            @Override public void onToken(String delta) { System.out.print(delta); }
            @Override public void onComplete(String finishReason) { System.out.println("\n[完成] " + finishReason); }
        })
        .write();
```

调用 `.stream()` 之后不能再改配置或改用阻塞 write，否则抛 `IllegalStateException`；链已终结后再次调用 write 同样抛异常。

### 3.3 AgentFile

`write(AgentFileRef)` 返回 `AgentFile`：

| 字段 | 说明 |
| --- | --- |
| `absolutePath` | 文件绝对路径 |
| `relativePath` | 传入的目录，原样保留 |
| `fileName` | 文件名 |
| `fileType` | 扩展名小写，如 `json`，无扩展名为 `unknown` |
| `sizeBytes` | 文件大小，字节 |
| `sm3` | 国密 SM3 摘要，64 位十六进制 |
| `finishedAt` | 写入完成时间，ISO-8601 |

### 3.4 配置方法

以下方法都出现在 `.write` 之前，任意组合：

| 方法 | 说明 |
| --- | --- |
| `.system(String)` | 静态系统提示词 |
| `.systemTemplate(String)` 配合 `.variable(k, v)` / `.variables(Map)` | 模板提示词，支持 `{var}` 占位符 |
| `.temperature(double)`、`.maxTokens(int)` | 覆盖模型默认参数 |
| `.tool(Tool)`、`.tool(ToolSpec)` | 声明本次可用工具 |
| `.using("provider:model")`、`.using(ModelRef)` | 切换模型 |
| `.rag()`、`.rag(Consumer)` | 本次调用的 RAG 参数，见 4.4 |
| `.memory()`、`.memory(Consumer)` | 本次调用的记忆参数，见 4.4 |
| `.stream()` | 进入流式订阅阶段 |

## 4. 使用指南

### 4.1 多提供商多模型

```yaml
spring:
  agent:
    default-model: openai:gpt-4o-mini
    providers:
      - openai:
          type: chat-completions
          base-url: https://api.openai.com/v1
          api-key: ${OPENAI_API_KEY}
          models:
            - gpt-4o-mini
            - gpt-4o
      - anthropic:
          type: anthropic-messages
          base-url: https://api.anthropic.com/v1
          api-key: ${ANTHROPIC_API_KEY}
          models:
            - claude-sonnet-4-5
      - deepseek:
          type: deepseek
          base-url: https://api.deepseek.com/v1
          api-key: ${DEEPSEEK_API_KEY}
          max-tokens: 4096
          models:
            - deepseek-v4-flash
            - deepseek-v4-pro
```

`type` 按协议格式填写：`chat-completions`（OpenAI Chat Completions，含各类兼容厂商）、`responses`（OpenAI Responses API）、`anthropic-messages`（Anthropic Messages）、`gemini`、`ollama`。旧值 OPENAI/CLAUDE/CUSTOM 等仍可解析，写厂商名（如 deepseek）按 chat-completions 处理并告警。provider 级的 `temperature`、`max-tokens` 作为其下所有模型的默认值。

```java
import com.agent.model.ModelRef;

String a = agent.chat("你好").write();
String b = agent.chat("复杂推理").using("openai:strong").write();
String c = agent.chat("写作").using(ModelRef.of("openai", "strong")).write();
```

引用格式 `provider:别名`，按第一个 `:` 分割，别名里可以再带冒号（如 `llama3:8b`）。DeepSeek、Moonshot、通义等 OpenAI 兼容厂商的 `type` 写 `CUSTOM`、`OPENAI` 或厂商名都可以。

### 4.2 提示词模板与温度

```java
String answer = agent.chat("你好")
        .systemTemplate("你是一名{role}，请用{lang}回答")
        .variable("role", "资深架构师")
        .variable("lang", "中文")
        .temperature(0.3)
        .maxTokens(2048)
        .write();
```

### 4.3 JSON 字段注解

`write(Class)` 的目标对象的字段需要标注 `@AgentJsonFormatFiled`，模型按注解字段生成 JSON。声明了字段但一个注解都没有的类，调用时直接抛 `IllegalStateException`，不会发出请求：

```java
import com.agent.contract.AgentJsonFormatFiled;

public class Weather {
    @AgentJsonFormatFiled(desc = "城市名称")
    private String city;

    @AgentJsonFormatFiled(desc = "观测日期", format = "yyyy-MM-dd")
    private LocalDate date;

    @AgentJsonFormatFiled(desc = "摄氏温度")
    private Double temp;
}

Weather w = agent.chat("北京今天天气").write(Weather.class);
// 请求中注入了 city/date/temp 的字段说明，date 要求 yyyy-MM-dd 格式
```

`desc` 必填，描述字段含义；`format` 可选，约束内容格式（时间、编号等）。Map、集合、基本类型等没有字段的目标不受此限制。

### 4.4 RAG 与记忆的单次调用参数

在 `.chat` 与 `.write` 之间可以对单次调用单独设置 RAG 和记忆参数（前提是全局已装配对应拦截器）：

```java
import com.agent.memory.MemoryTier;

// RAG：最多召回 5 条、分数下限 0.6
String a = agent.chat("查知识库")
        .rag().maxResults(5).minScore(0.6)
        .write();

// 等价的 Consumer 写法
String b = agent.chat("查知识库")
        .rag(r -> r.maxResults(5).minScore(0.6))
        .write();

// 记忆：只查长期层、换命名空间、或本次不读写记忆
String c = agent.chat("回忆一下")
        .memory().tier(MemoryTier.LONG_TERM).namespace("user-42")
        .write();
String d = agent.chat("别记这次").memory(m -> m.enabled(false)).write();
```

RAG 的查询、检索、过滤、组装四个环节都可以通过继承 `RagInterceptor` 改写：

| 钩子 | 默认行为 | 常见改写 |
| --- | --- | --- |
| `buildQuery(request, ctx)` | 取最后一条 user 消息 | 拼接历史消息 |
| `retrieve(query, topK)` | RagStore 混合检索 | 换检索引擎 |
| `filter(hits, minScore)` | 按分数下限过滤 | 黑白名单、租户隔离 |
| `formatHits(hits)` | Markdown 列表 | 自定义注入模板 |

记忆拦截器可改写 `loadMemory`、`saveMemory`，见 4.6。

### 4.5 工具

实现 `Tool` 接口并注册为 Spring Bean，SDK 自动收集执行逻辑，模型发起 tool_call 时自动执行并回填，最多 8 轮：

```java
import com.agent.contract.Tool;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class CalculatorTool implements Tool {

    @Override public String name() { return "calculator"; }
    @Override public String description() { return "计算四则运算表达式"; }

    @Override public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of("expression", Map.of("type", "string")),
            "required", List.of("expression"));
    }

    @Override public String execute(Map<String, Object> arguments) {
        return eval((String) arguments.get("expression"));
    }
}
```

```java
@Autowired CalculatorTool calculatorTool;

String result = agent.chat("帮我算 (1+2)*3")
        .tool(calculatorTool)
        .write();
```

非 Spring 场景用 `Agent.builder().tool(...)` 或 `agent.registerTool(tool)`。`parameters()` 里声明了 `required` 和 `properties.type` 时会做参数校验。

### 4.6 拦截器定制

记忆、RAG、门禁三个内置拦截器都支持继承后改写对应方法，装配时机和顺序沿用默认：

```java
@Component
public class MyMemoryInterceptor extends MemoryInterceptor {
    public MyMemoryInterceptor(MemoryStore store, MemoryConsolidator c) { super(store, c); }
    @Override protected String loadMemory(String ns, String key, MemoryTier tier) {
        return mySource.get(ns, key, tier);
    }
}

@Component
public class MyRagInterceptor extends RagInterceptor {
    public MyRagInterceptor(RagStore store, int topK) { super(store, topK); }
    @Override protected List<RagHit> retrieve(String query, int topK) {
        return mySearch.search(query, topK);
    }
}

@Component
public class MyHumanizeInterceptor extends HumanizeInterceptor {
    public MyHumanizeInterceptor() { super(true, "openai:gate-model"); }
    @Override protected String humanize(String content, InterceptorContext ctx) {
        return myRewrite(content);
    }
}
```

与内置功能无关的拦截直接 `implements Interceptor`，`order()` 默认 0，需要控制顺序时再覆盖。内置 Bean 可被宿主同名 `@Bean` 覆盖。

### 4.7 记忆

引入 `spring-boot-starter-data-redis` 后自动生效：

```yaml
spring:
  agent:
    memory:
      enabled: true
      database: 15
      reverse-optimize: true
```

- 临时记忆 3 天、短期 7 天，存 Redis；长期正文存宿主根目录 `.memory` 目录，Redis 只存索引。
- 查询顺序：内存热缓存、临时、短期、长期，命中即止。
- 轮次达到 `consolidation.min-rounds` 时在后台把临时、短期记忆整理为长期。
- 换存储实现 `MemoryStore` 注册为 Bean。

### 4.8 RAG

三个条件：引入 milvus-sdk、配置 embedding 模型、`rag.enabled: true`：

```yaml
spring:
  agent:
    embedding:
      provider: ollama
      model: nomic-embed-text
      dimensions: 768
    rag:
      enabled: true
      type: milvus
      top-k: 4
      milvus:
        host: localhost
        port: 19530
```

```java
@Autowired RagStore ragStore;
ragStore.index("doc-1", "要检索的文档内容……");
```

检索结果在请求前自动注入上下文。Milvus Java SDK 官方没有连接池，SDK 内置了一个（`rag.milvus.pool.max-size` 个 gRPC 客户端借还使用，`acquire-timeout-ms` 借用超时，故障实例自动替换）。

### 4.9 技能

skill.md 由宿主提供，SDK 不内置。放在 `skills.paths` 配置的文件或目录里，或放到宿主 `resources/skills/*.skill.md` 自动发现。`include-registry` 默认 true，会把已注册技能（含内置 calculator、search）的清单一并注入。

### 4.10 工作流定义与执行分离（AgentGraph）

Spring 环境下直接注入使用（prototype Bean，每个注入点获得独立实例，互不影响）：

```java
@Autowired
private AgentGraph agentGraph;          // 本类专属的空白图

AgentGraph graph = agentGraph.start("question")
        .modelInference("answer", ctx -> "回答：" + ctx.get("question"), "openai:fast", "answer")
        .end("answer");
String result = graph.execute().input("question", "什么是密封接口").output();
```

多个 Agent Bean 时遵循 Spring 规则：有 `@Primary` 的 Agent 被绑定；多个且无主 Bean 时启动报错并提示——要么给某个 Agent 加 `@Primary`，要么放弃注入改为手动构造 `new AgentGraph(指定的Agent)`（非 Spring 环境同样用此构造）。

定义契约：必须以 `.start(输入键)` 开始、`.end(输出键)` 结尾，中间随意编排；未 start 不能 end、未 end 不能 execute、end 后不能再改定义、执行缺输入抛异常。

```java
AgentGraph graph = new AgentGraph(agent, "qa")       // 带名构造便于诊断
        .start("question")
        .intent("openai:fast", "question")            // 意图解析：从技能清单匹配 Skill
        .modelInference("answer",
                ctx -> "回答：" + ctx.get("question"),
                "openai:fast", "answer")
        .end("answer");

String result = graph.execute().input("question", "什么是密封接口").output();
```

可用节点方法：`node`、`modelInference`（静态字符串 / ModelRef / 运行时函数三种模型指定）、`intent`、`skill`、`tool`、`when`、`parallel`、`sequential`、`loop`、`awaitHuman`、`aiGate`、`judgeAdversarial`、`subWorkflow`。`intent(model, 输入键)` 为意图解析节点：用 LLM 从已注册技能清单选出一项，写入 `intent.skill` 与 `intent.args`。

七类模式：

```java
graph.sequential(stepA, stepB, stepC);
graph.when(ctx -> (int) ctx.get("score") >= 60, pass, retry);
graph.parallel(stepA, stepB, stepC);
graph.loop(ctx -> ctx.current("i") < 3, body, 10);
graph.awaitHuman("是否发布？", publish, rollback);
graph.aiGate("openai:strong", "anthropic:sonnet", 0.85, 5);
graph.judgeAdversarial("openai:strong", "openai:fast", "ollama:local", 6);
```

嵌套引用另一个工作流，多个工作流状态完全隔离（子图获得全新上下文，只交换声明的一对输入输出）：

```java
AgentGraph sub = new AgentGraph(agent, "sub")
        .start("subIn")
        .modelInference("subOut", ctx -> "处理：" + ctx.get("subIn"), "openai:fast", "subOut")
        .end("subOut");

AgentGraph main = new AgentGraph(agent, "main")
        .start("q")
        .subWorkflow(sub, "q", "subResult")
        .end("subResult");

Object out = main.execute().input("q", "输入").output();
```

工作流内的模型调用走完整 Agent 链路，记忆、RAG、技能拦截器同样生效。详见 [doc/workflow-orchestration.md](./doc/workflow-orchestration.md)。

## 5. 非 Spring 环境

```java
import com.agent.Agent;
import com.agent.model.Provider;
import com.agent.model.ModelConfig;
import com.agent.model.ProviderType;

Agent agent = Agent.builder()
        .registerProvider(Provider.builder("openai", ProviderType.OPENAI)
                .baseUrl("https://api.openai.com/v1")
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build())
        .registerModel(ModelConfig.builder("mini", "openai", "gpt-4o-mini").build())
        .defaultModel("openai:mini")
        .build();

try {
    String answer = agent.chat("你好").write();
} finally {
    agent.close();
}
```

Builder 还支持 `.addInterceptor(...)`、`.tool(...)`、`.transport(...)`、`.resilience(...)`、`.memoryStore(...)`、`.ragStore(...)`、`.gate(...)`、`.skillManager(...)`。Provider 有 `Provider.openai(key)`、`Provider.claude(key)`、`Provider.gemini(key)`、`Provider.ollama(baseUrl)` 四个快捷方法。

## 6. 错误码

异常统一为 `AgentException`，带 `code` 与 `retryable`；事件流中为 `ChatEvent.ErrorEvent`：

| code | 含义 | 可重试 |
| --- | --- | --- |
| `CIRCUIT_OPEN` | 熔断开启 | 是 |
| `RATE_LIMITED` | 触发限流 | 是 |
| `IDLE_TIMEOUT` | 空闲超时 | 是 |
| `TIMEOUT` | 请求超时 | 是 |
| `TRANSPORT_ERROR` | 网络异常 | 是 |
| `JSON_OUTPUT_ERROR` | JSON 反序列化失败 | 是 |
| `BAD_REQUEST` | 请求非法 | 否 |
| `MODEL_ERROR` | 模型侧错误 | 视情况 |

```java
try {
    agent.chat("...").write();
} catch (com.agent.contract.AgentException e) {
    System.err.println("code=" + e.code() + " retryable=" + e.retryable());
}
```

## 7. 配置说明

配置前缀 `spring.agent.*`，`application.yml`、`application.properties`、环境变量（`SPRING_AGENT_XXX`）三种写法等价。除标注必填的项，其余可省略。

### 7.1 application.yml 全量示例

```yaml
spring:
  data:                                    # 宿主自己的 Redis 连接（Spring Boot 标准配置，非 SDK 配置）
    redis:                                 # 启用记忆（memory.enabled=true）时必须有；未引入 Redis 依赖时整段删除
      host: 127.0.0.1                      # 必填，Redis 地址
      port: 6379                           # 可省，默认 6379
      password: secret                     # 可省，无密码时删除
      database: 0                          # 可省，默认 0；宿主程序用这个库，SDK 记忆用独立的 spring.agent.memory.database

  agent:                                   # ===== 以下全部为 SDK 配置 =====
    default-model: openai:my-model         # 必填（除非每次调用都用 .using(...) 指定）；格式 provider:模型别名

    # ---------- 提供商列表：每个元素是"provider 名: 配置"，至少配一个 ----------
    providers:
      - openai:                            # provider 名（自定义，供 default-model 和 .using 引用）
          type: chat-completions           # 可省，默认 chat-completions；协议格式：chat-completions / responses / anthropic-messages / gemini / ollama，写厂商名（如 deepseek）按 chat-completions 处理并告警
          base-url: https://api.openai.com/v1   # 必填，API 端点，不带路径
          api-key: ${OPENAI_API_KEY}       # 必填（ollama 本地模型除外）；建议环境变量注入
          timeout: 120s                    # 可省，默认 120s，单次请求超时
          max-retries: 0                   # 可省，默认 0，提供商级重试次数（容错层还有独立的 resilience 重试）
          temperature: 0.7                 # 可省，该提供商下所有模型的默认温度，模型级可覆盖
          max-tokens: 4096                 # 可省，该提供商下所有模型的默认最大 token，模型级可覆盖
          headers:                         # 可省，附加到每个请求的额外请求头
            X-Org: my-org                  # 示例：请求头名: 值
          models:                          # 必填（该提供商可用的模型），字符串列表，别名等于厂商真实模型名
            - gpt-4o-mini                  # 引用方式 openai:gpt-4o-mini
            - gpt-4o
      - anthropic:                         # 第二个提供商，按需增删
          type: anthropic-messages         # Anthropic Messages 协议
          base-url: https://api.anthropic.com/v1   # 必填
          api-key: ${ANTHROPIC_API_KEY}    # 必填
          models:
            - claude-sonnet-4-5
      - ollama:                            # 本地模型示例
          type: ollama                     # Ollama 原生 /api/chat 协议（NDJSON 流）
          base-url: http://localhost:11434 # 必填，本地 Ollama 地址
          models:                          # 无 api-key；模型别名可含冒号（如 llama3:8b）
            - llama3:8b                     # 引用方式 ollama:llama3:8b（别名含冒号按第一个冒号分割）
      - deepseek:                          # 第三方 OpenAI 兼容厂商示例
          type: chat-completions           # 也可直接写 deepseek，效果相同（会打告警日志）
          base-url: https://api.deepseek.com   # 必填
          api-key: ${DEEPSEEK_API_KEY}     # 必填

    # ---------- 顶层模型配置（旧结构，可选；与 provider 内嵌 models 并存，用于需要别名≠真实名的场景） ----------
    models:
      my-model:                            # 本地别名，引用为 openai:my-model
        provider: openai                   # 必填，指向 providers 里的名字
        model-name: gpt-4o-mini            # 必填，厂商真实模型名
        temperature: 0.5                   # 可省，默认温度，覆盖 provider 级
        max-tokens: 2048                   # 可省，默认最大 token，覆盖 provider 级
        stream: true                       # 可省，默认 true，是否以流式请求
        extra:                             # 可省，厂商特定参数透传
          top_p: 0.9                        # 示例：原样透传给厂商的参数
        memory:
          database: 1                      # 可省，模型级记忆库号，覆盖全局 memory.database

    # ---------- 记忆（整段可省略，全部有默认值；需引入 spring-boot-starter-data-redis） ----------
    memory:
      enabled: true                        # 可省，默认 true；false 时即使引入了 Redis 也不启用记忆
      database: 15                         # 可省，默认 15；SDK 记忆专用 Redis 库号，与宿主程序默认库隔离
      reverse-optimize: false              # 可省，默认 false；true 时用户输入与记忆冲突以用户为准
      consolidation:
        min-rounds: 100                    # 可省，默认 100；对话轮次达到后触发后台整合浓缩为长期记忆
      cache:
        ttl: 30m                           # 可省，默认 30m；热记忆 JVM 内存缓存时效
        max-size: 20MB                     # 可省，默认 20MB；热记忆上限，超限写回 Redis（容量单位大写）
      temporary:
        ttl: 3d                            # 可省，默认 3d；临时记忆 Redis 过期时间
      short-term:
        ttl: 7d                            # 可省，默认 7d；短期记忆 Redis 过期时间

    # ---------- 向量化（启用 RAG 时必配，否则整段可删） ----------
    embedding:
      provider: ollama                     # 可省，默认 ollama；向量化提供商：openai 或 ollama
      model: nomic-embed-text              # RAG 启用时必填，向量模型名
      dimensions: 768                      # 可省，默认 768；向量维度，需与模型一致

    # ---------- RAG（默认关闭；需引入 milvus-sdk-java） ----------
    rag:
      enabled: false                       # 可省，默认 false；true 才启用 RAG 检索注入
      type: milvus                         # 可省，默认 milvus；向量库类型
      top-k: 4                             # 可省，默认 4；每次检索召回条数（单次调用可用 .rag().maxResults(n) 覆盖）
      milvus:
        host: localhost                    # 可省，默认 localhost；Milvus 地址
        port: 19530                        # 可省，默认 19530；Milvus 端口
        collection: agent-memory           # 可省，默认 agent-memory；集合名，不存在时自动创建
        pool:
          max-size: 4                      # 可省，默认 4；连接池中 gRPC 客户端数量
          acquire-timeout-ms: 5000         # 可省，默认 5000；借用客户端超时（毫秒）

    # ---------- 真人门禁（去 AI 味，默认关闭） ----------
    gate:
      human-gate: false                    # 可省，默认 false；true 时输出先经门禁模型改写
      model: openai:my-gate                # human-gate=true 时必填；门禁模型引用

    # ---------- 宿主技能（整段可省略） ----------
    skills:
      paths:                               # 可省；宿主 skill.md 路径列表，元素为文件或目录（目录扫描 *.skill.md）
        - /opt/my-app/skills
      include-registry: true               # 可省，默认 true；是否附加注入已注册技能清单（含内置 calculator/search）

    # ---------- Netty 传输（整段可省略，一般无需调整） ----------
    transport:
      worker-threads: 16                   # 可省，默认 CPU 核数*2；EventLoop 线程数
      max-connections-per-endpoint: 64     # 可省，默认 CPU 核数*4；单端点连接池上限
      max-pending-acquires: 65536          # 可省，默认 65536；连接获取排队上限
      acquire-timeout-ms: 10000            # 可省，默认 10000；从池中获取连接的超时
      connect-timeout-ms: 10000            # 可省，默认 10000；TCP 建连超时
      request-timeout-ms: 120000           # 可省，默认 120000；请求总超时
      idle-timeout-sec: 60                 # 可省，默认 60；连接空闲回收时间

    # ---------- 容错（整段可省略） ----------
    resilience:
      enabled: true                        # 可省，默认 true；容错总开关
      retry-max-attempts: 3                # 可省，默认 3；可重试错误的最大重试次数
      retry-backoff: 100ms                 # 可省，默认 100ms；重试初始退避（指数增长加抖动）
      request-timeout: 120s                # 可省，默认 120s；请求总超时（触发 TIMEOUT 错误码）
      heartbeat-max-idle: 30s              # 可省，默认 30s；流式事件最大空闲（触发 IDLE_TIMEOUT）
      breaker-enabled: true                # 可省，默认 true；熔断开关
      breaker-window-size: 20              # 可省，默认 20；熔断统计窗口请求数
      breaker-failure-rate-threshold: 50   # 可省，默认 50；窗口内失败率阈值（%），超过则熔断
      breaker-wait-duration: 10s           # 可省，默认 10s；熔断开启持续时间，过后进入半开探测
      rate-limit-permits-per-second: 0     # 可省，默认 0（关闭）；每模型每秒允许的请求数
      rate-limit-burst: 10                 # 可省，默认 10；限流突发容量
``````

### 7.2 配置项默认值

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `default-model` | 无，必填 | `provider:别名` |
| `providers[].<name>.type` | chat-completions | chat-completions / responses / anthropic-messages / gemini / ollama；旧值与厂商名可解析 |
| `providers.<name>.base-url` | 无，必填 | 端点 |
| `providers.<name>.api-key` | 空 | OLLAMA 可省 |
| `providers.<name>.timeout` | 120s | 单次请求超时 |
| `providers.<name>.max-retries` | 0 | 提供商级重试 |
| `providers.<name>.headers` | 空 | 额外请求头 |
| `providers[].<name>.models[]` | 空 | 模型名列表，别名等于真实模型名，继承 provider 级默认 |
| `models.<别名>.model-name` | 无，必填 | 厂商真实名 |
| `models.<别名>.temperature` | 空 | 默认温度 |
| `models.<别名>.max-tokens` | 空 | 最大 token |
| `models.<别名>.stream` | true | 默认流式请求 |
| `models.<别名>.extra` | 空 | 厂商参数透传 |
| `models.<别名>.memory.database` | 空 | 覆盖全局记忆库 |
| `memory.enabled` | true | 记忆开关 |
| `memory.database` | 15 | 记忆隔离库 |
| `memory.reverse-optimize` | false | 冲突以用户输入为准 |
| `memory.consolidation.min-rounds` | 100 | 整理阈值 |
| `memory.cache.ttl` | 30m | 热缓存时效 |
| `memory.cache.max-size` | 20MB | 热缓存上限 |
| `memory.temporary.ttl` | 3d | 临时记忆过期 |
| `memory.short-term.ttl` | 7d | 短期记忆过期 |
| `embedding.provider` | ollama | openai 或 ollama |
| `embedding.model` | 无，RAG 时必填 | 向量模型名 |
| `embedding.dimensions` | 768 | 向量维度 |
| `rag.enabled` | false | RAG 开关 |
| `rag.type` | milvus | 向量库类型 |
| `rag.top-k` | 4 | 召回条数 |
| `rag.milvus.host/port/collection` | localhost / 19530 / agent-memory | Milvus 连接 |
| `rag.milvus.pool.max-size/acquire-timeout-ms` | 4 / 5000 | Milvus 连接池 |
| `gate.human-gate` | false | 门禁开关 |
| `gate.model` | 无，开启时必填 | 门禁模型 |
| `skills.paths` | 空 | skill.md 路径 |
| `skills.include-registry` | true | 注入技能清单 |
| `transport.*` | 见 7.1 | Netty 参数 |
| `resilience.*` | 见 7.1 | 容错参数 |

## 8. 约定与边界

- 命名沿用 `Agent` 体系，包根 `com.agent`。
- SDK 不内置 Key 和端点，配置全部来自宿主；所有 Bean 带 `@ConditionalOnMissingBean`，可覆盖。
- 输出链一律以 `.write` 结尾；默认阻塞，`.stream().subscribe(...).write()` 为流式。
- 执行流程全局统一（见 2.3）：用户输入 → 意图解析 → 匹配/加载 Skill → Skill 初始化 → 提示词模板 → 模板 + 上下文 → 最终 Prompt → 调用大模型 → 输出；`chat` 单次调用由拦截器链隐式完成，AgentGraph 可用 `intent` 节点显式编排。
- 线程模型全局统一：网络 IO 全程在 Netty EventLoop（纯非阻塞，SSE 帧零拷贝切分、背压由 request(n) 驱动）；协议 JSON 解析与事件组装、记忆 Redis 读写、RAG 检索、工具执行、模型阻塞等待全部在 SDK 专属 `SdkSchedulers` IO 线程池（与宿主线程池完全隔离，不使用虚拟线程）。三层互不占用：网络线程不会被业务解析占满，业务也不阻塞在网络上。可用系统属性 `agent.io.threads`、`agent.io.queue` 调整。
- 扩展点：Provider 走 `ProtocolAdapter`，拦截器走 `Interceptor` 或继承内置拦截器，存储走 `MemoryStore`、`RagStore`，向量化走 `EmbeddingModel`，技能走 `Skill`，工具走 `Tool`。
- 并发设计上没有全局锁（非阻塞 DNS、无锁限流、独立 IO 线程池等），单进程支撑超大连接数仍需水平扩展，细节见 [doc/performance-tuning.md](./doc/performance-tuning.md)。

## 9. 深入阅读

| 文档 | 内容 |
| --- | --- |
| [doc/contract-specification.md](./doc/contract-specification.md) | 接口契约、SPI 签名、错误码 |
| [doc/architecture.md](./doc/architecture.md) | 分层与依赖方向 |
| [doc/design-revision.md](./doc/design-revision.md) | 架构决策记录 |
| [doc/module-index.md](./doc/module-index.md) | 模块、包、类索引 |
| [doc/workflow-orchestration.md](./doc/workflow-orchestration.md) | 工作流七类模式 |
| [doc/performance-tuning.md](./doc/performance-tuning.md) | 调优与并发治理 |
| [doc/sdk-packaging-release.md](./doc/sdk-packaging-release.md) | 打包与发布 |
| [doc/design-patterns.md](./doc/design-patterns.md) | 设计模式与对应类 |
