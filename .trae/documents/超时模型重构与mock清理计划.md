# 厂商无关稳定性重构 + 零配置启动 + mock 全删

## Context

用户三项需求（已澄清）：

1. **超大 token 稳定性**：DeepSeek 几十万~上百万 token 请求「响应超时」、硅基流动响应 2 分钟以上被打断。根因是三重超时在 prefill 静默期（首字节前无数据流动）必然杀掉请求：
   - `ResilienceConfig.requestTimeout` 默认 120s 总超时（`UserTimeoutGuard`）
   - `HeartbeatKeepAlive` 事件间超时（另有隐性 bug：reactor 3.6.5 无 `Flux.timeout(Duration, Function)` 重载，lambda 被解析为 `timeout(Duration, Publisher)`，超时后切换到永不发信号的 fallback → 流静默挂起而非报错，需整体重写）
   - `NettyChannelPoolHandler` L51 `IdleStateHandler(0,0,60)` ALL_IDLE + `StreamBridge` 收到任何 idle 事件就失败
2. **零配置启动**：宿主不配 `spring.agent.*` 也能正常启动（装配层已基本容忍，需实测兜底 + 补测试）
3. **mock 全删**：内置 calculator/search 演示技能、全部 5 个 MockWebServer 测试、FakeRedis 测试、mockwebserver 依赖

已核实事实：pom 当前版本 2.2.0（Central 不可覆盖，本次升 2.3.0）；`NettyHttpConfig.requestTimeoutMs` 与 `Provider.timeoutMs` 均为死配置；`ChatEventParserTest` 已覆盖 OpenAI/Claude/Ollama/Gemini 解析器，缺口是 OpenAIResponsesEventParser；MockWebServer 测试共 5 个：`AgentGraphTest`、`DynamicModelWorkflowTest`、`AgentJsonFormatFieldTest`、`ConversationOutputTest`、`NettyHttpClientTest`。

## 步骤 1：Resilience 层三段式超时（核心，先行）

- `resilience/ResilienceConfig.java`：删 `heartbeatMaxIdle`，新增 `firstTokenTimeout`（默认 PT10M）、`interEventIdle`（默认 PT2M）；`requestTimeout` 默认改 `Duration.ZERO`（0=关闭）。Builder 同步，旧方法标 `@Deprecated` 转发。
- 重写 `resilience/HeartbeatKeepAlive.java`：用 `events.timeout(Mono.delay(firstToken), t -> Mono.delay(interEventIdle))`（Publisher+Function 重载）实现首事件/事件间分离；`onErrorMap(TimeoutException)` 按「是否已收首事件」区分 `FIRST_TOKEN_TIMEOUT` / `IDLE_TIMEOUT` 错误码。
- `resilience/UserTimeoutGuard.java`：guard(Flux) 改为真总超时 `flux.takeUntilOther(Mono.delay(t).then(Mono.error(...)))`（`Flux.timeout(Duration)` 是事件间语义，不能表达总超时）；Mono 路径保持。
- `resilience/ResilienceManager.java`：组合顺序 retry(外) > breaker > 总超时 > 首字节/事件间。
- `pipeline/impl/TimeoutInterceptor.java`：同步新策略。
- `config/AgentProperties.java` + `config/AgentAutoConfiguration.resilienceManager()`：新增 `first-token-timeout` / `inter-event-idle` 绑定；`heartbeat-max-idle` 标 `@Deprecated` 转发；`request-timeout` 默认 0。
- 自检：新增 `ResilienceTimeoutTest`（StepVerifier.withVirtualTime：静默 11min 触发首字节超时、首事件后静默 121s 触发空闲超时、总超时关闭时不生效）。

## 步骤 2：Netty 层 idle 门控与并发参数

- `transport/NettyChannelPoolHandler.java` L51：ALL_IDLE → READER_IDLE（`IdleStateHandler(idleTimeoutSec, 0, 0)`）。
- `transport/NettyHttpClient.java`：
  - `StreamBridge` 加 `volatile boolean responseStarted`（收到 HttpResponse 置位），`userEventTriggered` 仅在 `responseStarted` 后因 idle 失败，首字节前忽略（prefill 静默交给 Resilience 层）
  - `BlockingBridge` 加同样标志；acquire 后用 `ch.eventLoop().schedule()` 实现首响应超时（收到 headers 时 cancel），激活 `requestTimeoutMs`（语义改为首响应超时），修复非流式永久挂起
- `transport/NettyHttpConfig.java` + `AgentProperties.Transport`：`requestTimeoutMs` 默认 600_000；`maxConnectionsPerEndpoint` 默认 `max(32, cores*8)`；`acquireTimeoutMs` 10s→30s（大上下文长连接占满 CPU×4 池是 acquire FAIL 的根因）。
- 自检：编译 + 步骤 5 的解析器测试。

## 步骤 3：零配置启动（可与步骤 2 并行）

- 新增 `src/test/java/com/agent/config/ZeroConfigStartupTest.java`：ApplicationContextRunner 空 property 断言 `hasSingleBean(Agent/ModelRegistry/SkillManager/AgentHolder)`、`doesNotHaveBean(MemoryStore)`；`chat().write()` 报错信息含 `spring.agent.default-model` 指引。
- 若暴露装配问题（重点：AgentHolder、agentGraph prototype、DefaultSkillManager 空注册表），修复对应 Bean 方法。
- 自检：`mvn test -Dtest=ZeroConfigStartupTest`。

## 步骤 4：删除所有 mock

- 删 `skill/CalculatorSkill.java`、`skill/SearchSkill.java`；`skill/DefaultSkillManager.java` 构造器去掉两行注册，保留 `registry.loadAll()` 与宿主 SPI 能力。
- 删 5 个测试：`workflow/AgentGraphTest.java`、`workflow/DynamicModelWorkflowTest.java`、`output/AgentJsonFormatFieldTest.java`、`output/ConversationOutputTest.java`、`transport/NettyHttpClientTest.java`、`memory/DefaultMemoryStoreTest.java`（FakeRedis）。
- `pom.xml`：删 mockwebserver 依赖与 `mockwebserver.version` 属性。
- 清理引用与文案：`AgentAutoConfiguration.java` L311、`AgentProperties.java` L148 的「内置 calculator/search」字样；检查 `SkillHostLoadingTest` 是否引用内置技能（是则改用测试内假技能类）。
- 自检：`mvn test-compile`；全局 grep 无 `CalculatorSkill|SearchSkill|FakeRedis|MockWebServer` 残留。

## 步骤 5：协议解析纯单测补齐

- 扩展 `protocol/ChatEventParserTest.java`：新增 OpenAIResponsesEventParser 全生命周期（response.created / output_text.delta / tool / function_arguments / output.done）；补各解析器边界：分片 JSON 帧、error 帧、`[DONE]`、ping 心跳、usage 尾帧。直接喂 SSE 帧字符串，无 mock 框架。
- 自检：`mvn test -Dtest=ChatEventParserTest,ProviderTypeBindingTest`。

## 步骤 6：文档与版本

- `doc/README.md`：零配置启动说明、内置技能移除、新超时配置。
- `doc/architecture.md`：三段超时模型。
- `doc/contract-specification.md`：配置项变更 + heartbeat-max-idle → inter-event-idle 迁移表。
- `doc/module-index.md`：删两个 Skill 类条目。
- `doc/performance-tuning.md`：新超时模型、并发默认值、超大 token 调参指引。
- `doc/workflow-orchestration.md`：去 calculator/search 内置引用。
- `pom.xml` version 2.2.0 → 2.3.0。
- 文档风格：去 AI 味，保留全部技术细节。

## 验证

```
set JAVA_HOME=C:\Program Files\Java\jdk-17
C:\Users\wang\.cache\maven\apache-maven-3.9.9\bin\mvn.cmd -f c:\Projects\AiCoding\spring-boot-ai-agent-starter\pom.xml clean verify
```

全量 surefire 通过、无 MockWebServer 残留编译错、javadoc 无新警告。发布（用户确认后）：`mvn clean deploy`，密钥与 keyserver 已就绪。
