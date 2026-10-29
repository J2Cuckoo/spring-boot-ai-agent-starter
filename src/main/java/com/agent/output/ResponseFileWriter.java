package com.agent.output;

import com.agent.serialize.JacksonConfig;
import com.agent.util.Sm3Utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 以响应信封格式把结果写磁盘（pretty JSON），自动创建目录，
 * 返回含绝对路径/相对路径/文件名/类型/大小/国密 SM3 摘要/完成时间的 {@link AgentFile}。
 */
public final class ResponseFileWriter {

    public AgentFile write(AgentFileRef fileRef, ResponseEnvelope envelope) {
        try {
            Path dir = Path.of(fileRef.path());
            Files.createDirectories(dir);
            Path file = dir.resolve(fileRef.fileName());
            byte[] bytes = JacksonConfig.mapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(envelope);
            Files.write(file, bytes);

            String absolutePath = file.toAbsolutePath().toString();
            String relativePath = fileRef.path();
            String fileName = file.getFileName().toString();
            String fileType = fileTypeOf(fileName);
            long sizeBytes = Files.size(file);
            String sm3 = Sm3Utils.sm3Hex(bytes);
            String finishedAt = Instant.now().toString();
            return new AgentFile(absolutePath, relativePath, fileName,
                    fileType, sizeBytes, sm3, finishedAt);
        } catch (IOException e) {
            throw new IllegalStateException("写出响应文件失败: " + fileRef.path() + "/" + fileRef.fileName(), e);
        }
    }

    /** 从文件名推断类型（小写扩展名，无扩展名为 unknown）。 */
    private static String fileTypeOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot < fileName.length() - 1
                ? fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : "unknown";
    }
}
