package com.agent.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 技能注册表：classpath ServiceLoader 自动发现 + jar 热加载。
 */
public final class SkillRegistry {

    private final Map<String, Skill> skills = new ConcurrentHashMap<>();
    private final List<SkillLoader> loaders = new CopyOnWriteArrayList<>();

    public SkillRegistry() {
        loaders.add(new ClasspathSkillLoader());
    }

    public void register(Skill skill) {
        skills.put(skill.id(), skill);
    }

    public Optional<Skill> find(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public List<Skill> all() {
        return new ArrayList<>(skills.values());
    }

    /** 初始化时装载所有 loader 的技能（重复 id 以先注册者为准）。 */
    public void loadAll() {
        for (SkillLoader loader : loaders) {
            for (Skill skill : loader.load()) {
                skills.putIfAbsent(skill.id(), skill);
            }
        }
    }

    /** 热加载外部 jar 并立即注册。 */
    public List<Skill> hotLoad(java.io.File jar) {
        List<Skill> loaded = new JarSkillLoader(jar).load();
        loaded.forEach(this::register);
        return loaded;
    }

    /** classpath ServiceLoader 装载。 */
    static final class ClasspathSkillLoader implements SkillLoader {
        @Override
        public List<Skill> load() {
            List<Skill> found = new ArrayList<>();
            for (Skill skill : java.util.ServiceLoader.load(Skill.class)) {
                found.add(skill);
            }
            return found;
        }
    }
}
