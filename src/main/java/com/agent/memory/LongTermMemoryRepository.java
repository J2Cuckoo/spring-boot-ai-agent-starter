package com.agent.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 长期记忆文件仓库：正文存宿主应用根目录 {@code .memory/} 下，
 * 按 {namespace}/{key}.json 组织；namespace/key 中的非法路径字符会被替换。
 */
public final class LongTermMemoryRepository {

    private final Path baseDir;

    public LongTermMemoryRepository() {
        this(Path.of(System.getProperty("user.dir"), ".memory"));
    }

    public LongTermMemoryRepository(Path baseDir) {
        this.baseDir = baseDir;
    }

    public Path baseDir() {
        return baseDir;
    }

    public void write(String namespace, String key, String json) {
        try {
            Path file = resolve(namespace, key);
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写长期记忆失败: " + namespace + "/" + key, e);
        }
    }

    public Optional<String> read(String namespace, String key) {
        Path file = resolve(namespace, key);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public boolean delete(String namespace, String key) {
        try {
            return Files.deleteIfExists(resolve(namespace, key));
        } catch (IOException e) {
            return false;
        }
    }

    private Path resolve(String namespace, String key) {
        return baseDir.resolve(sanitize(namespace)).resolve(sanitize(key) + ".json");
    }

    private static String sanitize(String s) {
        return s == null ? "null" : s.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}
