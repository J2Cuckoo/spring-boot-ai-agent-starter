package com.agent.config;

import com.agent.pipeline.impl.SkillInjectInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

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
    void noHostSkillsStillWorksAndIncludesRegistry() {
        runner.run(ctx -> {
            SkillInjectInterceptor interceptor = ctx.getBean(SkillInjectInterceptor.class);
            // 未配置宿主路径时仍有内置技能清单（include-registry 默认 true）
            assertThat(interceptor.skillDocs()).anySatisfy(doc -> assertThat(doc).contains("calculator"));
        });
    }

    @Test
    void includeRegistryCanBeDisabled() {
        runner.withPropertyValues("spring.agent.skills.include-registry=false")
                .run(ctx -> {
                    SkillInjectInterceptor interceptor = ctx.getBean(SkillInjectInterceptor.class);
                    assertThat(interceptor.skillDocs()).isEmpty();
                });
    }
}
