package com.agent.config;

import com.agent.pipeline.impl.SkillInjectInterceptor;
import com.agent.skill.DefaultSkillManager;
import com.agent.skill.Skill;
import com.agent.skill.SkillManager;
import com.agent.skill.SkillProgress;
import com.agent.skill.SkillResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillHostLoadingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class))
            .withPropertyValues(
                    "spring.agent.default-model=openai:main",
                    "spring.agent.providers.openai.type=OPENAI",
                    "spring.agent.providers.openai.base-url=http://localhost:18080",
                    "spring.agent.providers.openai.api-key=k",
                    "spring.agent.models.main.provider=openai",
                    "spring.agent.models.main.model-name=gpt");

    @Test
    void loadsHostSkillMdFromConfiguredDirectory(@TempDir Path dir) throws Exception {
        Path skillFile = dir.resolve("host-tool.skill.md");
        Files.writeString(skillFile, "# 宿主技能\n宿主自带的专属工具说明。");

        runner.withPropertyValues("spring.agent.skills.paths[0]=" + dir.toAbsolutePath())
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SkillInjectInterceptor.class);
                    SkillInjectInterceptor interceptor = ctx.getBean(SkillInjectInterceptor.class);
                    assertThat(interceptor.skillDocs())
                            .anySatisfy(doc -> assertThat(doc).contains("宿主技能"));
                });
    }

    @Test
    void noHostSkillMdStillInjectsRegisteredSkillRegistry() {
        runner.withUserConfiguration(HostSkillConfig.class)
                .run(ctx -> {
                    SkillInjectInterceptor interceptor = ctx.getBean(SkillInjectInterceptor.class);
                    // include-registry 默认 true：宿主注册的技能清单注入上下文
                    assertThat(interceptor.skillDocs())
                            .anySatisfy(doc -> assertThat(doc).contains("greet"));
                });
    }

    @Test
    void includeRegistryCanBeDisabled() {
        runner.withUserConfiguration(HostSkillConfig.class)
                .withPropertyValues("spring.agent.skills.include-registry=false")
                .run(ctx -> {
                    SkillInjectInterceptor interceptor = ctx.getBean(SkillInjectInterceptor.class);
                    assertThat(interceptor.skillDocs()).isEmpty();
                });
    }

    /** 宿主用自己的 SkillManager Bean 覆盖默认装配，注册测试技能。 */
    @Configuration(proxyBeanMethods = false)
    static class HostSkillConfig {
        @Bean
        SkillManager hostSkillManager() {
            DefaultSkillManager manager = new DefaultSkillManager();
            manager.register(new GreetSkill());
            return manager;
        }
    }

    static class GreetSkill implements Skill {
        @Override
        public String id() {
            return "greet";
        }

        @Override
        public String name() {
            return "问候";
        }

        @Override
        public String description() {
            return "测试用宿主技能";
        }

        @Override
        public SkillResult execute(Map<String, Object> args, SkillProgress progress) {
            return SkillResult.ok("hi");
        }
    }
}
