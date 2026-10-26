package com.agent.skill;

import java.util.Map;

/**
 * 技能 SPI。独立 jar 通过 {@code META-INF/services/com.agent.skill.Skill} 注册，
 * 也可经 {@code SkillRegistry.hotLoad(jarPath)} 热加载。
 */
public interface Skill {

    String id();

    String name();

    String description();

    SkillResult execute(Map<String, Object> args, SkillProgress progress);
}
