# spring-boot-ai-agent-starter

一个 Java 17 的 Agent 编排多模型客户端 SDK，以 Spring Boot Starter 形式分发。底层用 Netty 4.1 自建 HTTP 客户端（连接池、SSE 零拷贝、背压），向上封装 Reactor 的 `Flux`/`Mono` 风格 API。

支持多提供商多模型、四种输出方式、工具调用、技能注入、永久记忆（Redis）、外挂 RAG（Milvus）、工作流编排与完整容错。被宿主项目依赖时直接读取宿主的 `spring.agent.*` 配置，不内置任何固定 Key 或端点。

**本文是使用者指南：从引入依赖、写配置到每一种能力的代码用法。** 架构设计与实现细节见文末「深入阅读」。

---

## 目录

1. [引入依赖](#1-引入依赖)
2. [快速开始](#2-快速开始)
3. [完整配置参考](#3-完整配置参考)
4. [使用指南](#4-使用指南)
5. [非 Spring 环境（纯 Java Builder）](#5-非-spring-环境纯-java-builder)
6. [错误码](#6-错误码)
7. [约定与边界](#7-约定与边界)
8. [深入阅读](#8-深入阅读)

---

## 1. 引入依赖

### 1.1 构建并安装 SDK 到本地仓库

SDK 尚未发布到中央仓库，先在 SDK 工程目录（`pom.xml` 所在处）执行：

```bash
# 需要 JDK 17
mvn clean install
```

产物 `io.github.j2cuckoo:spring-boot-ai-agent-starter:1.0.0` 会安装到本地 Maven 仓库（`~/.m2/repository`）。

### 1.2 Maven 引入

在宿主工程 `pom.xml` 加：

```xml
<dependency>
    <groupId>io.github.j2cuckoo</groupId>
    <artifactId>spring-boot-ai-agent-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle（Groovy DSL）：

```groovy
implementation 'io.github.j2cuckoo:spring-boot-ai-agent-starter:1.0.0'
```

`spring-boot-autoconfigure` 版本由宿主的 Spring Boot BOM / parent 统一管理，Starter 内不写死。传输层（Netty）、响应式（Reactor）、JSON（Jackson）等会随本依赖自动传递。

### 1.3 可选依赖（按需引入）

以下能力依赖宿主自行引入（SDK 把它们标记为 `optional`，不强制传递）：

| 能力 | 需宿主引入的依赖 | 开关（默认值） |
| --- | --- | --- |
| 永久记忆（Redis） | `org.springframework.boot:spring-boot-starter-data-redis` | `memory.enabled`（**默认 true 开启**，缺 Redis 时自动降级关闭） |
| 外挂 RAG（默认 Milvus） | `io.milvus:milvus-sdk-java:2.4.1` | `rag.enabled`（**默认 false 关闭**，须显式开启） |

```xml
<!-- 启用永久记忆（memory.enabled 默认 true） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<!-- 启用默认 Milvus RAG（还须配 rag.enabled: true） -->
<dependency>
    <groupId>io.milvus</groupId>
    <artifactId>milvus-sdk-java</artifactId>
    <version>2.4.1</version>
</dependency>
```

---

## 2. 快速开始

### 2.1 最小配置（`application.yml`）

```yaml
spring:
  agent:
    default-model: openai:my-model          # 默认模型引用 provider:别名
    providers:
      openai:
        type: OPENAI
        base-url: https://api.openai.com/v1
        api-key: ${OPENAI_API_KEY}
    models:
      my-model:
        provider: openai
        model-name: gpt-4o-mini             # 厂商真实模型名
```

- `providers.<名字>`：提供商，`type` 决定协议适配器；`api-key` 从环境变量读取，禁止硬编码。
- `models.<别名>`：模型，`provider` 指向上面声明的提供商，`model-name` 是厂商真实名。
- 引用语法 `provider:别名`，以第一个 `:` 分割（模型别名可再含 `:`，如 Ollama 标签 `llama3:8b`）。

### 2.2 最小代码

```java
import com.agent.Agent;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
public class ChatController {

    @Autowired Agent agent;

    @GetMapping("/ask")
    public String ask(@RequestParam String q) {
        return agent.chat(q).write();       // 阻塞，返回纯字符串
    }
}
```

Starter 通过 `META-INF/spring/...AutoConfiguration.imports` 自动装配 `Agent` Bean，宿主无需任何 `@Import` 或手工 `new`。

### 2.3 跑起来

启动宿主应用，请求 `GET /ask?q=你好` 即走完整链路：默认模型解析 → 拦截器链 → 协议适配 → Netty 连接池发请求 → 流式读取 → 聚合返回。

---

## 3. 完整配置参考

所有配置前缀为 `spring.agent.*`。除标注「必填」外，其余均可省略（取默认值）。属性名大小写不敏感（Spring Boot relaxed binding），`application.properties` 用 `spring.agent.memory.database=15` 等价，环境变量用 `SPRING_AGENT_MEMORY_DATABASE=15` 等价。

### 3.1 顶层

| 配置 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `spring.agent.default-model` | String | 无 | 默认模型引用 `provider:别名`。代码里未 `.using(...)` 时必填 |

### 3.2 providers（提供商，按需声明多个）

```yaml
spring.agent.providers.<name>:
  type: OPENAI          # OPENAI / CLAUDE / GEMINI / OLLAMA / CUSTOM（CUSTOM 走 OpenAI 兼容协议）
  base-url: ...         # 端点，必填
  api-key: ...          # 认证；OLLAMA 本地模型可省略
  timeout: 120s         # 单次请求超时（Duration）
  max-retries: 0        # 提供商级重试次数
  headers:              # 额外请求头（Map）
    X-Custom: v
```

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `type` | enum | 无（必填） | 决定协议适配器与认证方式 |
| `base-url` | String | 无（必填） | 端点，不写协议路径（如 `https://api.openai.com/v1`） |
| `api-key` | String | 空 | 认证密钥；`OLLAMA` 本地无 api-key |
| `timeout` | Duration | 120s | 单次请求超时 |
| `max-retries` | int | 0 | 提供商级重试次数 |
| `headers` | Map | 空 | 额外请求头 |

### 3.3 models（模型，按需声明多个）

```yaml
spring.agent.models.<别名>:
  provider: openai        # 必填，指向 providers 下某个名字
  model-name: gpt-4o      # 必填，厂商真实模型名
  temperature: 0.7        # 默认温度
  max-tokens: 4096        # 最大 token
  stream: true            # 默认流式（写文件/流式输出用）
  extra:                  # 厂商特定参数透传
    top_p: 0.9
  memory:
    database: 1           # 模型级记忆库，覆盖全局 memory.database
```

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `provider` | String | 无（必填） | 指向 `providers.<name>` |
| `model-name` | String | 无（必填） | 厂商真实模型名 |
| `temperature` | double | 空 | 默认温度，可被 `.temperature()` 覆盖 |
| `max-tokens` | int | 空 | 最大 token |
| `stream` | boolean | true | 默认流式 |
| `extra` | Map | 空 | 厂商特定参数透传 |
| `memory.database` | int | 空 | 覆盖全局记忆库 |

### 3.4 memory（永久记忆，全部可省略）

```yaml
spring.agent.memory:
  enabled: true             # 记忆功能总开关，默认开启
  database: 15              # 记忆隔离库，与宿主程序默认库分离
  reverse-optimize: false   # 记忆反向优化（冲突时以用户为准）
  consolidation:
    min-rounds: 100         # 达到该轮次触发后台整合浓缩
  cache:
    ttl: 30m                # 热记忆内存缓存时效
    max-size: 20MB          # 热记忆上限，超限写回 Redis
  temporary:
    ttl: 3d                 # 临时记忆过期
  short-term:
    ttl: 7d                 # 短期记忆过期
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | 记忆功能总开关；`false` 时即使引入 Redis 也不装配记忆 |
| `database` | 15 | 记忆隔离库；模型级 `memory.database` 优先 |
| `reverse-optimize` | false | 用户输入与记忆冲突时以用户为准 |
| `consolidation.min-rounds` | 100 | 轮次阈值，触发后台整合浓缩 |
| `cache.ttl` | 30m | 热记忆缓存时效 |
| `cache.max-size` | 20MB | 热记忆上限（注意单位大写 `MB`） |
| `temporary.ttl` | 3d | 临时记忆过期 |
| `short-term.ttl` | 7d | 短期记忆过期 |

**整个 `memory` 段可整体省略，也可只覆盖某个子项。** 记忆**默认开启**：复用宿主 Redis 连接（`spring.data.redis.*`）；未引入 Redis 时自动降级关闭（不报错）。设 `enabled: false` 可显式关闭记忆（即使已引入 Redis）。

### 3.5 gate（真人门禁，去 AI 味）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `human-gate` | false | 真人门禁开关 |
| `model` | 无 | 门禁模型 `provider:别名`，`human-gate=true` 时必填 |

### 3.6 skills（宿主技能文档）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `paths` | `[]` | 宿主 skill.md 路径列表（文件或目录，目录下扫描 `*.skill.md`） |
| `include-registry` | true | 是否把已注册技能清单（含内置 calculator/search）注入上下文 |

skill.md 全部来自宿主，SDK 不内置任何 skill.md（详见 [4.8](#48-技能宿主-skillmd-与内置技能)）。

### 3.7 embedding（RAG 向量化模型）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `provider` | ollama | 向量化提供商（openai 或 ollama） |
| `model` | 无（启用 RAG 时必填） | 向量化模型名 |
| `dimensions` | 768 | 向量维度 |

### 3.8 rag（外挂 RAG，默认关闭）

```yaml
spring.agent.rag:
  enabled: true             # RAG 功能总开关，默认关闭，须显式开启
  type: milvus              # 向量库类型
  top-k: 4                  # 检索召回条数
  milvus:
    host: localhost
    port: 19530
    collection: agent-memory
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | false | RAG 功能总开关；默认关闭，须显式 `true` 开启 |
| `type` | milvus | 向量库类型 |
| `top-k` | 4 | 检索召回条数 |
| `milvus.host` | localhost | Milvus 主机 |
| `milvus.port` | 19530 | Milvus 端口 |
| `milvus.collection` | agent-memory | 集合名 |

### 3.9 transport（Netty 传输）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `worker-threads` | CPU×2 | EventLoop 线程数 |
| `max-connections-per-endpoint` | CPU×4 | 单端点连接池上限 |
| `max-pending-acquires` | 65536 | 连接获取排队上限 |
| `acquire-timeout-ms` | 10000 | 获取连接超时 |
| `connect-timeout-ms` | 10000 | 建连超时 |
| `request-timeout-ms` | 120000 | 请求总超时 |
| `idle-timeout-sec` | 60 | 连接空闲回收 |

### 3.10 resilience（容错）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | true | 容错总开关 |
| `retry-max-attempts` | 3 | 重试次数（仅可重试错误） |
| `retry-backoff` | 100ms | 重试初始退避（指数 + 抖动） |
| `request-timeout` | 120s | 请求总超时 |
| `heartbeat-max-idle` | 30s | 事件流空闲阈值 |
| `breaker-enabled` | true | 熔断开关 |
| `breaker-window-size` | 20 | 熔断统计窗口 |
| `breaker-failure-rate-threshold` | 50 | 失败率阈值（%） |
| `breaker-wait-duration` | 10s | 熔断等待期 |
| `rate-limit-permits-per-second` | 0 | 限流（token/s），0=关闭 |
| `rate-limit-burst` | 10 | 限流突发容量 |

---

## 4. 使用指南

### 4.1 四种输出方式（互斥，一条链只能以一种收尾）

```java
import com.agent.Agent;
import com.agent.contract.ChatEvent;
import reactor.core.publisher.Flux;

@Autowired Agent agent;

// 1) 阻塞，返回纯字符串
String text = agent.chat("Java 17 密封接口的作用?").write();

// 2) 阻塞，注入 JSON 约束并反序列化为目标类型（失败自动纠偏重试一次）
Weather w = agent.chat("北京今天天气如何").writeJson(Weather.class);

// 3) 非阻塞，流式返回事件流
Flux<ChatEvent> stream = agent.chat("写一个快速排序").writeStream();
stream.subscribe(e -> {
    if (e instanceof ChatEvent.TokenEvent t) {
        System.out.print(t.delta());          // 增量 token
    }
});

// 4) 阻塞，以响应信封格式写文件
agent.chat("生成季度报告").writeFile("./output", "report.json");
```

`ChatEvent` 是 sealed interface，主要事件：

| 事件 | 说明 |
| --- | --- |
| `TokenEvent(delta, timestamp)` | 增量文本 |
| `ToolCallDeltaEvent` / `ToolCallCompleteEvent` | 工具调用增量 / 完整调用 |
| `UsageEvent(promptTokens, completionTokens, totalTokens)` | token 用量 |
| `DoneEvent(finishReason)` | 流正常结束 |
| `ErrorEvent(code, message, retryable)` | 错误事件 |
| `HeartbeatEvent(timestamp)` | 心跳（Claude ping 等） |

### 4.2 多提供商多模型与运行时切换

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

// 用默认模型
String a = agent.chat("你好").write();

// 运行时切到其他模型：字符串引用
String b = agent.chat("复杂推理").using("openai:strong").write();

// 或类型安全的 ModelRef 配置对象（替代硬编码字符串）
ModelRef strong = ModelRef.of("openai", "strong");
String c = agent.chat("写作").using(strong).write();
```

### 4.3 系统提示词模板与温度

```java
String answer = agent.chat("你好")
        .systemTemplate("你是一名{role}，请用{lang}回答")   // {var} 占位符
        .variable("role", "资深架构师")
        .variable("lang", "中文")
        .temperature(0.3)                                 // 覆盖 ModelConfig 默认温度
        .maxTokens(2048)
        .write();

// 静态系统提示词
String r = agent.chat("...").system("你是一个严谨的助手").write();
```

### 4.4 工具调用（自动闭环，声明式注册）

实现 `Tool` 接口并注册为 Spring Bean（**无需 `@PostConstruct`**），SDK 自动收集注册执行逻辑。模型返回 `tool_call` 时 SDK 自动执行并回填结果，最多 8 轮。

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
        String expr = (String) arguments.get("expression");
        return String.valueOf(eval(expr));   // 你的计算实现
    }
}
```

调用时声明本次可用工具（直接传 `Tool`，或传 `tool.spec()`）：

```java
@Autowired Agent agent;
@Autowired CalculatorTool calculatorTool;

String result = agent.chat("帮我算 (1+2)*3")
        .tool(calculatorTool)          // 声明式工具，内部取 tool.spec()
        .write();
```

参数校验：`Tool` 的 `parameters()` 含 `required` 与 `properties.type` 时，SDK 会自动校验模型返回的参数。非 Spring 场景用 `Agent.builder().tool(...)` 或 `agent.registerTool(tool)` 注册。

### 4.5 自定义拦截器

按功能定制对应的拦截器，**继承 SDK 提供的功能拦截器，覆盖其默认处理逻辑**即可，无需实现整个 `Interceptor` 接口，也无需关心 `order()` 与请求/响应的装配细节。

**记忆功能**：继承 `MemoryInterceptor`，覆盖记忆读写逻辑：

```java
import com.agent.memory.MemoryConsolidator;
import com.agent.memory.MemoryStore;
import com.agent.memory.MemoryTier;
import com.agent.pipeline.impl.MemoryInterceptor;
import org.springframework.stereotype.Component;

@Component
public class MyMemoryInterceptor extends MemoryInterceptor {

    public MyMemoryInterceptor(MemoryStore store, MemoryConsolidator consolidator) {
        super(store, consolidator);
    }

    @Override
    protected String loadMemory(String namespace, String key, MemoryTier tier) {
        // 自定义记忆读取来源（默认走 Redis 三层记忆）
        return myMemorySource.get(namespace, key, tier);
    }
}
```

**RAG 功能**：继承 `RagInterceptor`，覆盖检索逻辑：

```java
import com.agent.pipeline.impl.RagInterceptor;
import com.agent.rag.RagHit;
import com.agent.rag.RagStore;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class MyRagInterceptor extends RagInterceptor {

    public MyRagInterceptor(RagStore store, int topK) {
        super(store, topK);
    }

    @Override
    protected List<RagHit> retrieve(String query, int topK) {
        // 自定义检索实现（默认走 Milvus 混合检索）
        return mySearchEngine.search(query, topK);
    }
}
```

**真人门禁（后置拦截器）**：继承 `HumanizeInterceptor`，覆盖去 AI 味处理：

```java
import com.agent.pipeline.InterceptorContext;
import com.agent.pipeline.impl.HumanizeInterceptor;
import org.springframework.stereotype.Component;

@Component
public class MyHumanizeInterceptor extends HumanizeInterceptor {

    public MyHumanizeInterceptor() {
        super(true, "openai:gate-model");
    }

    @Override
    protected String humanize(String content, InterceptorContext ctx) {
        // 自定义去 AI 味逻辑（默认走门禁模型改写）
        return myRewrite(content);
    }
}
```

- 记忆/RAG 是前置处理（请求前注入上下文），门禁是后置处理（响应后改写）；继承对应类后装配时机、`order()` 均继承默认，无需重复声明。
- 完全自定义（不基于内置功能）时，直接 `implements Interceptor`，`order()` 默认 0、仅在需要控制顺序时覆盖。
- 注册方式：`@Component`/`@Bean`（Spring 自动收集）或 `Agent.Builder.addInterceptor(...)`（纯 Java）。

内置拦截器：`LoggingInterceptor`、`RateLimitInterceptor`、`RetryInterceptor`、`TimeoutInterceptor`、`SkillInjectInterceptor`、`MemoryInterceptor`、`RagInterceptor`、`HumanizeInterceptor`。内置 Bean 均可被宿主同名 `@Bean` 覆盖。

### 4.6 永久记忆（默认开启）

记忆由内置 `MemoryInterceptor` 自动读写，宿主只需引入 Redis 依赖，无需写代码；**默认开启**，可显式关闭：

```yaml
spring:
  agent:
    memory:
      enabled: true               # 默认 true；设为 false 关闭记忆
      database: 15
      reverse-optimize: true      # 可选：用户输入与记忆冲突时以用户为准
```

- 三层记忆：临时（3d）/ 短期（7d）存 Redis，长期正文存宿主根目录 `.memory` 文件（Redis 只存长期索引）。
- 查询顺序：内存热记忆 → 临时 → 短期 → 长期。
- 整合浓缩：轮次达 `consolidation.min-rounds` 时后台把临时/短期交给默认模型浓缩为长期，异步不阻塞对话。
- 自定义存储：实现 `MemoryStore` 并注册为 `@Bean` 即替换默认 `DefaultMemoryStore`。
- **关闭记忆**：`memory.enabled: false`（即使已引入 Redis）；或直接不引入 Redis 依赖，SDK 自动降级关闭。

### 4.7 外挂 RAG（默认关闭）

**RAG 默认关闭**，需显式开启 + 引入 Milvus 依赖 + 配置 embedding 模型：

```yaml
spring:
  agent:
    embedding:
      provider: ollama
      model: nomic-embed-text
      dimensions: 768
    rag:
      enabled: true               # 必须显式开启
      type: milvus
      top-k: 4
      milvus:
        host: localhost
        port: 19530
```

- 检索发生在请求前，命中的知识片段以 system 形式注入上下文。
- 自定义检索：实现 `RagStore` 并注册为 `@Bean` 即替换默认 `MilvusRagStore`（同样需 `rag.enabled: true` 才生效）。
- 手动入库：

```java
import com.agent.rag.RagStore;

@Autowired RagStore ragStore;

ragStore.index("doc-1", "这是要检索的文档内容……");
```

### 4.8 技能（宿主 skill.md 与内置技能）

**skill.md 全部由宿主提供，SDK 不内置。** 两种提供方式：

1. **配置路径**：

```yaml
spring:
  agent:
    skills:
      paths:
        - /opt/my-app/skills          # 目录，扫描 *.skill.md
        - /opt/my-app/special.skill.md
      include-registry: true          # 是否附加注入内置/宿主 SPI 技能清单
```

2. **宿主 classpath**：放到宿主工程的 `resources/skills/*.skill.md`，自动被发现。

注入内容由内置 `SkillInjectInterceptor` 在构造期预计算，请求期无额外开销。

**内置技能** `calculator`（安全四则运算）与 `search`（本地文本检索）实现了 `Skill` 接口，注册在 `SkillManager`。它们可经工作流 `skillStep` 节点显式调用：

```java
// 见 4.9 工作流示例
```

也可实现 `Tool` 接口暴露为模型可自动调用的工具（见 4.4）。

### 4.9 工作流编排

工作流是有向图（DAG）多节点编排，入口 `agent.newWorkflow()`，支持 7 类执行模式。每个 `modelInference` 节点可用**静态模型**（字符串/`ModelRef`）或**动态模型**（运行时按上下文切换）：

```java
import com.agent.model.ModelRef;
import com.agent.workflow.Workflow;
import com.agent.workflow.WorkflowContext;

// 顺序：先检索，再推理
Workflow wf = agent.newWorkflow()
        .skillStep("search", "search",
                ctx -> Map.of("query", ctx.get("topic")), null)
        .modelInference("answer",
                ctx -> "根据以下检索结果回答：" + ctx.get("output.search"),
                null, null)                                   // 静态：null 用默认模型
        .build();

WorkflowContext ctx = new WorkflowContext();
ctx.put("topic", "Spring Boot 自动装配");
wf.runBlocking(ctx);
String answer = (String) ctx.get("output.answer");

// 动态模型：根据上下文在运行时切换模型
Workflow dynamic = agent.newWorkflow()
        .modelInference("plan",
                ctx -> "先制定方案",
                ctx -> (String) ctx.get("plannerModel"),       // 运行时解析模型
                null)
        .modelInference("execute",
                ctx -> "按方案执行：" + ctx.get("output.plan"),
                ModelRef.of("openai", "strong"),               // 配置对象，非硬编码
                null)
        .build();
```

7 类模式一览：

```java
// 1) 顺序
agent.newWorkflow().sequential(stepA, stepB, stepC);

// 2) 条件
agent.newWorkflow().when(ctx -> (int) ctx.get("score") >= 60)
        .then(stepPass).otherwise(stepRetry);

// 3) 并发（fan-in）
agent.newWorkflow().parallel(stepA, stepB, stepC);

// 4) 循环
agent.newWorkflow().loop(ctx -> ctx.current("i") < 3, body, 10);

// 5) 人类干预（暂停等待人工审批）
agent.newWorkflow().awaitHuman("是否发布？")
        .onApprove(publishStep).onReject(rollbackStep);

// 6) AI 门禁：门禁定方向 → 执行产出 → 判定循环
agent.newWorkflow().aiGate()
        .gatekeeper("openai:strong")
        .worker("anthropic:sonnet")
        .threshold(0.85).maxIterations(5).end();

// 7) 裁判对抗：裁判 + 两方对抗 + 优缺点迭代
agent.newWorkflow().judgeAdversarial()
        .judge("openai:strong").sideA("openai:fast").sideB("ollama:local")
        .maxIterations(6).end();
```

工作流内 `ModelInferenceStep` 走完整 `Agent` 链路（记忆/RAG/skill 拦截器同样生效）。详见 [workflow-orchestration.md](./workflow-orchestration.md)。

### 4.10 真人门禁（去 AI 味）

```yaml
spring:
  agent:
    gate:
      human-gate: true
      model: openai:gate-model        # 门禁模型，必填
```

开启后，除门禁模型自身外，所有交互型模型的返回内容都先经门禁模型改写为自然人类口吻再返回（默认关闭；门禁模型自身不二次门禁，避免递归）。注意：开启后流式输出会先聚合再整体返回。

---

## 5. 非 Spring 环境（纯 Java Builder）

不依赖 Spring Boot 时，用 `Agent.builder()` 全代码装配：

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
        .registerModel(ModelConfig.builder("mini", "openai", "gpt-4o-mini")
                .temperature(0.7)
                .build())
        .defaultModel("openai:mini")
        .build();

try {
    String answer = agent.chat("你好").write();
} finally {
    agent.close();     // 释放 Netty 连接池与线程
}
```

Builder 同样支持：`.addInterceptor(...)`、`.transport(...)`、`.resilience(...)`、`.memoryStore(...)`、`.memoryConfig(...)`、`.ragStore(...)`、`.gate(...)`、`.skillManager(...)`。三种内置 Provider 快捷方式：`Provider.openai(key)`、`Provider.claude(key)`、`Provider.ollama(baseUrl)`。

---

## 6. 错误码

统一通过 `AgentException`（含 `code` 与 `retryable`）或 `ChatEvent.ErrorEvent` 暴露：

| code | 含义 | 可重试 |
| --- | --- | --- |
| `CIRCUIT_OPEN` | 熔断开启 | ✓ |
| `RATE_LIMITED` | 触发限流 | ✓ |
| `IDLE_TIMEOUT` | 空闲超时 | ✓ |
| `TIMEOUT` | 请求超时 | ✓ |
| `TRANSPORT_ERROR` | 网络/连接失败 | ✓ |
| `JSON_OUTPUT_ERROR` | `.writeJson` 反序列化失败 | ✓ |
| `BAD_REQUEST` | 请求非法 | ✗ |
| `MODEL_ERROR` | 模型侧错误 | 视情况 |

```java
try {
    agent.chat("...").write();
} catch (com.agent.contract.AgentException e) {
    System.err.println("code=" + e.code() + " retryable=" + e.retryable());
}
```

---

