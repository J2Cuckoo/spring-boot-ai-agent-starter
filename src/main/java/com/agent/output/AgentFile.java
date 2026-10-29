package com.agent.output;

import java.nio.file.Path;

/**
 * 文件写出的结果元数据：绝对路径、相对路径、文件名、文件类型（扩展名）、
 * 文件大小（字节）、国密 SM3 唯一摘要、写入完成时间（ISO-8601）。
 */
public record AgentFile(String absolutePath, String relativePath, String fileName,
                        String fileType, long sizeBytes, String sm3, String finishedAt) {

    /** 便捷：转 {@link Path}（绝对路径形态）。 */
    public Path toPath() {
        return Path.of(absolutePath);
    }

    @Override
    public String toString() {
        return "AgentFile{" + absolutePath + ", " + sizeBytes + "B, " + fileType
                + ", sm3=" + sm3 + ", at=" + finishedAt + '}';
    }
}
