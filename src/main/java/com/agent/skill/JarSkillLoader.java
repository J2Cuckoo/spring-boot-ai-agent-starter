package com.agent.skill;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 从独立 jar 热加载技能：URLClassLoader + ServiceLoader。
 * 加载器在使用方关闭后失效，技能实例在本次会话内保持可用。
 */
public final class JarSkillLoader implements SkillLoader {

    private final File jar;

    public JarSkillLoader(File jar) {
        this.jar = jar;
    }

    @Override
    public List<Skill> load() {
        List<Skill> found = new ArrayList<>();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{jar.toURI().toURL()},
                Thread.currentThread().getContextClassLoader())) {
            java.util.ServiceLoader<Skill> spi = java.util.ServiceLoader.load(Skill.class, loader);
            for (Skill skill : spi) {
                found.add(skill);
            }
        } catch (Exception e) {
            throw new IllegalStateException("热加载技能 jar 失败: " + jar, e);
        }
        return found;
    }
}
