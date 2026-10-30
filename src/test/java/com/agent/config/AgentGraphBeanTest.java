package com.agent.config;

import com.agent.Agent;
import com.agent.workflow.AgentGraph;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentGraph 以 prototype Bean 注入：{@code @Autowired private AgentGraph agentGraph;}
 * 每个注入点获得独立实例，各自定义互不影响。
 */
class AgentGraphBeanTest {

    static class HolderA {
        @Autowired AgentGraph agentGraph;
    }

    static class HolderB {
        @Autowired AgentGraph agentGraph;
    }

    @Configuration
    static class Wiring {
    }

    @Test
    void prototypeBeanPerInjectionPoint() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
                .withUserConfiguration(Wiring.class, HolderA.class, HolderB.class)
                .withPropertyValues(
                        "spring.agent.default-model=openai:main",
                        "spring.agent.providers[0].openai.type=OPENAI",
                        "spring.agent.providers[0].openai.base-url=http://localhost:18080",
                        "spring.agent.providers[0].openai.api-key=k",
                        "spring.agent.providers[0].openai.models[0]=gpt")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    HolderA a = ctx.getBean(HolderA.class);
                    HolderB b = ctx.getBean(HolderB.class);

                    // 每个注入点是独立实例（prototype）
                    assertThat(a.agentGraph).isNotSameAs(b.agentGraph);

                    // 各自独立完成 start → end 定义
                    AgentGraph ga = a.agentGraph.start("q")
                            .modelInference("out", ctx2 -> "p", "openai:main", "out")
                            .end("out");
                    AgentGraph gb = b.agentGraph.start("input2").end("input2");

                    // A 定义不影响 B；同容器直接 getBean 两次也是新实例
                    assertThat(ctx.getBean(AgentGraph.class))
                            .isNotSameAs(ctx.getBean(AgentGraph.class));
                    assertThat(ga.outputKey()).isEqualTo("out");
                    assertThat(gb.inputKey()).isEqualTo("input2");

                    // 定义完成后可执行（execute 校验通过）
                    assertThat(ga.execute()).isNotNull();
                });
    }
}
