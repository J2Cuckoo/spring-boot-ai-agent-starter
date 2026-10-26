package com.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 内置技能：本地文本检索。args: query（必填）、dir（可选，默认 ./skills-corpus）、limit（默认 20）。
 */
public final class SearchSkill implements Skill {

    @Override
    public String id() {
        return "search";
    }

    @Override
    public String name() {
        return "本地检索";
    }

    @Override
    public String description() {
        return "在本地文本语料目录中按关键词检索，返回匹配行（dir/query/limit）";
    }

    @Override
    public SkillResult execute(Map<String, Object> args, SkillProgress progress) {
        String query = args == null || args.get("query") == null ? null : args.get("query").toString();
        if (query == null || query.isBlank()) {
            return SkillResult.fail("缺少参数 query");
        }
        String dir = args.get("dir") == null ? "skills-corpus" : args.get("dir").toString();
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 20;
        Path root = Path.of(dir);
        if (!Files.isDirectory(root)) {
            return SkillResult.fail("语料目录不存在: " + dir);
        }
        String needle = query.toLowerCase();
        List<String> hits = new ArrayList<>();
        progress.report("扫描语料", 50);
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(
                    f -> Files.isRegularFile(f)
                            && (f.toString().endsWith(".md")
                            || f.toString().endsWith(".txt")
                            || f.toString().endsWith(".json")))::iterator) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && hits.size() < limit; i++) {
                    if (lines.get(i).toLowerCase().contains(needle)) {
                        hits.add(file + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            }
        } catch (IOException e) {
            return SkillResult.fail("检索失败: " + e.getMessage());
        }
        progress.report("检索完成", 100);
        if (hits.isEmpty()) {
            return SkillResult.ok("未找到匹配: " + query);
        }
        return SkillResult.ok(String.join("\n", hits));
    }
}
