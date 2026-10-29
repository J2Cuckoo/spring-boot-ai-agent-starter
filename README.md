# spring-boot-ai-agent-starter

Java 17 的多模型 Agent 客户端 SDK，以 Spring Boot Starter 形式分发。传输层基于 Netty 4.1（连接池、SSE 零拷贝、背压、非阻塞 DNS），上层封装 Reactor 的 `Flux`/`Mono` API。

功能覆盖多提供商多模型、字符串/JSON/文件/流式四种输出、工具调用、技能注入、三层记忆（Redis）、外挂 RAG（Milvus）、工作流编排和容错（熔断、限流、重试、心跳）。宿主通过 `spring.agent.*` 提供配置，SDK 不内置任何 Key 或端点。

宿主引入本依赖即可使用：netty-all、Jackson、Reactor、BouncyCastle（国密 SM3）、SLF4J、spring-boot-autoconfigure 均随依赖自动传递。

## 目录

1. [引入依赖](#1-引入依赖)
2. [快速开始](#2-快速开始)
3. [输出方式](#3-输出方式)
4. [使用指南](#4-使用指南)
5. [非 Spring 环境](#5-非-spring-环境)
6. [错误码](#6-错误码)
7. [配置说明](#7-配置说明)
8. [约定与边界](#8-约定与边界)
9. [深入阅读](#9-深入阅读)

## 1. 引入依赖

### 1.1 构建安装

SDK 尚未发布到中央仓库，先在本工程执行（需要 JDK 17）：

```bash
mvn clean install
```

产物 `io.github.j2cuckoo:spring-boot-ai-agent-starter:1.0.4` 会安装到本地仓库（`~/.m2/repository`）。

### 1.2 Maven 引入

```xml
<dependency>
    <groupId>io.github.j2cuckoo</groupId>
    <artifactId>spring-boot-ai-agent-starter</artifactId>
    <version>2.0.1</version>
</dependency>
```

Gradle：

```groovy
implementation 'io.github.j2cuckoo:spring-boot-ai-agent-starter:1.0.4'
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
| 外挂 RAG | `io.milvus:milvus-sdk-java:2.4.1` | `rag.enabled`，默认 false |

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
    default-model: deepseek:chat
    providers:
      deepseek:
        type: CUSTOM      # OPENAI/CLAUDE/GEMINI/OLLAMA/CUSTOM；写厂商名会按 CUSTOM 处理并告警
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}
    models:
      chat:
        provider: deepseek
        model-name: deepseek-chat
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
    default-model: openai:fast
    providers:
      openai:
        type: OPENAI
        base-url: https://api.openai.com/v1
        api-key: ${OPENAI_API_KEY}
      anthropic:
        type: CLAUDE
        base-url: https://api.anthropic.com/v1
        api-key: ${ANTHROPIC_API_KEY}
    models:
      fast:
        provider: openai
        model-name: gpt-4o-mini
      strong:
        provider: openai
        model-name: gpt-4o
      sonnet:
        provider: anthropic
        model-name: claude-sonnet-4-5
        max-tokens: 4096
```

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

检索结果在请求前自动注入上下文。

### 4.9 技能

skill.md 由宿主提供，SDK 不内置。放在 `skills.paths` 配置的文件或目录里，或放到宿主 `resources/skills/*.skill.md` 自动发现。`include-registry` 默认 true，会把已注册技能（含内置 calculator、search）的清单一并注入。

### 4.10 工作流

```java
import com.agent.model.ModelRef;
import com.agent.workflow.Workflow;
import com.agent.workflow.WorkflowContext;

Workflow wf = agent.newWorkflow()
        .modelInference("plan",
                ctx -> "先制定方案",
                ctx -> (String) ctx.get("plannerModel"),   // 运行时解析模型
                null)
        .modelInference("execute",
                ctx -> "按方案执行：" + ctx.get("output.plan"),
                ModelRef.of("openai", "strong"),
                null)
        .build();

WorkflowContext ctx = new WorkflowContext();
ctx.put("plannerModel", "openai:fast");
wf.runBlocking(ctx);
```

七种模式：

```java
agent.newWorkflow().sequential(stepA, stepB, stepC);
agent.newWorkflow().when(ctx -> (int) ctx.get("score") >= 60).then(pass).otherwise(retry);
agent.newWorkflow().parallel(stepA, stepB, stepC);
agent.newWorkflow().loop(ctx -> ctx.current("i") < 3, body, 10);
agent.newWorkflow().awaitHuman("是否发布？").onApprove(publish).onReject(rollback);
agent.newWorkflow().aiGate().gatekeeper("openai:strong").worker("anthropic:sonnet")
        .threshold(0.85).maxIterations(5).end();
agent.newWorkflow().judgeAdversarial().judge("openai:strong").sideA("openai:fast")
        .sideB("ollama:local").maxIterations(6).end();
```

工作流内的模型调用走完整 Agent 链路，记忆、RAG、技能拦截器同样生效。详见 [doc/workflow-orchestration.md](./doc/workflow-orchestration.md)。

### 4.11 真人门禁

```yaml
spring:
  agent:
    gate:
      human-gate: true
      model: openai:gate-model
```

开启后，除门禁模型自身外的交互输出先由门禁模型改写成更自然的口吻再返回，默认关闭。开启后流式输出会先聚合再整体返回。

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
  data:                                 # 宿主自己的 Redis 连接，记忆复用
    redis:
      host: 127.0.0.1
      port: 6379
      password: secret
      database: 0
  agent:
    default-model: openai:my-model      # 必填，或在代码里 .using(...) 指定

    providers:
      openai:
        type: OPENAI                    # OPENAI/CLAUDE/GEMINI/OLLAMA/CUSTOM，大小写不敏感，可省略
        base-url: https://api.openai.com/v1   # 必填
        api-key: ${OPENAI_API_KEY}
        timeout: 120s
        max-retries: 0
        headers:
          X-Org: my-org
      anthropic:
        type: CLAUDE
        base-url: https://api.anthropic.com/v1
        api-key: ${ANTHROPIC_API_KEY}
      ollama:                           # 本地模型，无 api-key
        type: OLLAMA
        base-url: http://localhost:11434
      deepseek:                         # OpenAI 兼容厂商
        type: CUSTOM
        base-url: https://api.deepseek.com
        api-key: ${DEEPSEEK_API_KEY}

    models:
      my-model:                         # 本地别名，引用为 openai:my-model
        provider: openai                # 必填
        model-name: gpt-4o-mini         # 必填，厂商真实模型名
        temperature: 0.7
        max-tokens: 4096
        stream: true
        extra:                          # 厂商参数透传
          top_p: 0.9
        memory:
          database: 1                   # 模型级记忆库，覆盖全局
      my-claude:
        provider: anthropic
        model-name: claude-sonnet-4-5
        memory:
          database: 2
      my-local:
        provider: ollama
        model-name: llama3:8b

    memory:                             # 整段可省略
      enabled: true
      database: 15                      # 记忆隔离库
      reverse-optimize: false
      consolidation:
        min-rounds: 100
      cache:
        ttl: 30m
        max-size: 20MB                  # 容量单位大写
      temporary:
        ttl: 3d
      short-term:
        ttl: 7d

    embedding:                          # 启用 RAG 时必配
      provider: ollama                  # openai 或 ollama
      model: nomic-embed-text
      dimensions: 768

    rag:                                # 默认关闭
      enabled: false
      type: milvus
      top-k: 4
      milvus:
        host: localhost
        port: 19530
        collection: agent-memory

    gate:
      human-gate: false                 # 默认关闭
      model: openai:my-gate             # 开启时必填

    skills:
      paths:
        - /opt/my-app/skills
      include-registry: true

    transport:                          # 整段可省略
      worker-threads: 16                # 默认 CPU*2
      max-connections-per-endpoint: 64  # 默认 CPU*4
      max-pending-acquires: 65536
      acquire-timeout-ms: 10000
      connect-timeout-ms: 10000
      request-timeout-ms: 120000
      idle-timeout-sec: 60

    resilience:                         # 整段可省略
      enabled: true
      retry-max-attempts: 3
      retry-backoff: 100ms
      request-timeout: 120s
      heartbeat-max-idle: 30s
      breaker-enabled: true
      breaker-window-size: 20
      breaker-failure-rate-threshold: 50
      breaker-wait-duration: 10s
      rate-limit-permits-per-second: 0  # 0 为关闭限流
      rate-limit-burst: 10
```

### 7.2 配置项默认值

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `default-model` | 无，必填 | `provider:别名` |
| `providers.<name>.type` | CUSTOM | 未识别值按 CUSTOM 处理并告警 |
| `providers.<name>.base-url` | 无，必填 | 端点 |
| `providers.<name>.api-key` | 空 | OLLAMA 可省 |
| `providers.<name>.timeout` | 120s | 单次请求超时 |
| `providers.<name>.max-retries` | 0 | 提供商级重试 |
| `providers.<name>.headers` | 空 | 额外请求头 |
| `models.<别名>.provider` | 无，必填 | 指向 providers |
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
| `gate.human-gate` | false | 门禁开关 |
| `gate.model` | 无，开启时必填 | 门禁模型 |
| `skills.paths` | 空 | skill.md 路径 |
| `skills.include-registry` | true | 注入技能清单 |
| `transport.*` | 见 7.1 | Netty 参数 |
| `resilience.*` | 见 7.1 | 容错参数 |
