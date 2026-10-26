package com.agent.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LongTermMemoryRepositoryTest {

    @Test
    void writeReadDelete(@TempDir Path dir) {
        LongTermMemoryRepository repo = new LongTermMemoryRepository(dir);
        // namespace 含冒号等非法路径字符，应被净化
        repo.write("openai:m", "fact-1", "{\"summary\":\"用户偏好简洁\"}");
        assertThat(repo.read("openai:m", "fact-1")).contains("{\"summary\":\"用户偏好简洁\"}");
        assertThat(repo.read("openai:m", "missing")).isEmpty();
        assertThat(repo.delete("openai:m", "fact-1")).isTrue();
        assertThat(repo.read("openai:m", "fact-1")).isEmpty();
    }
}
