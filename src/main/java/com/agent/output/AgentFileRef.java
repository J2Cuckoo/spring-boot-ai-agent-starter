package com.agent.output;

/**
 * 文件输出引用：{@code conversation.write(AgentFileRef.of("./output", "report.json"))}
 * 的目标位置。path 可为相对或绝对目录，fileName 为写入的文件名。
 */
public record AgentFileRef(String path, String fileName) {

    public AgentFileRef {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        path = path == null || path.isBlank() ? "." : path;
    }

    public static AgentFileRef of(String path, String fileName) {
        return new AgentFileRef(path, fileName);
    }
}
