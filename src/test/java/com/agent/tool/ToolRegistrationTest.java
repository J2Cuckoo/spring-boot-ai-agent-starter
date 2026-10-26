package com.agent.tool;

import com.agent.Agent;
import com.agent.contract.Tool;
import com.agent.model.ModelConfig;
import com.agent.model.Provider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRegistrationTest {

    static final class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "回显文本";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text"));
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            return "echo:" + arguments.get("text");
        }
    }

    @Test
    void declarativeRegistrationViaBuilder() {
        Agent agent = Agent.builder()
                .registerProvider(Provider.openai("k"))
                .registerModel(ModelConfig.of("m", "openai", "gpt"))
                .defaultModel("openai:m")
                .tool(new EchoTool())              // Builder 声明式，无需 @PostConstruct
                .build();
        try {
            assertThat(agent.tools().isRegistered("echo")).isTrue();
        } finally {
            agent.close();
        }
    }

    @Test
    void declarativeRegistrationViaAgentMethod() {
        Agent agent = Agent.builder()
                .registerProvider(Provider.openai("k"))
                .registerModel(ModelConfig.of("m", "openai", "gpt"))
                .defaultModel("openai:m")
                .build();
        try {
            agent.registerTool(new EchoTool());     // 也可运行时注册
            assertThat(agent.tools().isRegistered("echo")).isTrue();
        } finally {
            agent.close();
        }
    }

    @Test
    void specDerivedFromTool() {
        EchoTool tool = new EchoTool();
        assertThat(tool.spec().name()).isEqualTo("echo");
        assertThat(tool.spec().parameters()).containsKey("required");
    }
}
