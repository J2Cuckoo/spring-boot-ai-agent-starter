package com.agent.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class Sm3UtilsTest {

    @Test
    void knownVector() {
        // GB/T 32905-2016 标准向量：sm3("abc") =
        // 66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0
        assertThat(Sm3Utils.sm3Hex("abc"))
                .isEqualTo("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0");
    }

    @Test
    void hexLengthAndStability() {
        String h1 = Sm3Utils.sm3Hex("同一内容");
        String h2 = Sm3Utils.sm3Hex("同一内容");
        String h3 = Sm3Utils.sm3Hex("不同内容");
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(h1).isEqualTo(h2);       // 稳定
        assertThat(h1).isNotEqualTo(h3);    // 雪崩
    }

    @Test
    void sameBytesSameDigestAsFileWrite() throws Exception {
        // 与 ResponseFileWriter 场景一致：对写出的字节算摘要，读回再算应一致
        Path tmp = Files.createTempFile("sm3", ".bin");
        byte[] data = "hello 国密".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(tmp, data);
        assertThat(Sm3Utils.sm3Hex(Files.readAllBytes(tmp)))
                .isEqualTo(Sm3Utils.sm3Hex(data));
        Files.deleteIfExists(tmp);
    }
}
