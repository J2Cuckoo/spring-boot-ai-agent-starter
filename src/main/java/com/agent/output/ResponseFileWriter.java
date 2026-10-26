package com.agent.output;

import com.agent.serialize.JacksonConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以响应信封格式把结果写磁盘（pretty JSON），自动创建目录。
 */
public final class ResponseFileWriter {

    public void write(String path, String fileName, ResponseEnvelope envelope) {
        try {
            Path dir = Path.of(path);
            Files.createDirectories(dir);
            String json = JacksonConfig.mapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(envelope);
            Files.writeString(dir.resolve(fileName), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写出响应文件失败: " + path + "/" + fileName, e);
        }
    }
}
