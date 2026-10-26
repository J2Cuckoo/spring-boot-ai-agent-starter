package com.agent.skill;

import java.util.List;

/**
 * 技能加载 SPI：默认实现从 classpath 的 ServiceLoader 装载。
 */
public interface SkillLoader {

    List<Skill> load();
}
