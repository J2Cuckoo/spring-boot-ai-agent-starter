package com.agent.skill;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 技能管理契约。
 */
public interface SkillManager {

    void register(Skill skill);

    Optional<Skill> find(String id);

    List<Skill> all();

    SkillResult execute(String skillId, Map<String, Object> args, SkillProgress progress);
}
