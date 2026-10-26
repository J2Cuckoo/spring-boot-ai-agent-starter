package com.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认技能管理器：注册、查询与执行。
 */
public final class DefaultSkillManager implements SkillManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultSkillManager.class);

    private final SkillRegistry registry;

    public DefaultSkillManager() {
        this(new SkillRegistry());
    }

    public DefaultSkillManager(SkillRegistry registry) {
        this.registry = registry;
        this.registry.register(new CalculatorSkill());
        this.registry.register(new SearchSkill());
        this.registry.loadAll();
    }

    public SkillRegistry registry() {
        return registry;
    }

    @Override
    public void register(Skill skill) {
        registry.register(skill);
    }

    @Override
    public Optional<Skill> find(String id) {
        return registry.find(id);
    }

    @Override
    public List<Skill> all() {
        return registry.all();
    }

    @Override
    public SkillResult execute(String skillId, Map<String, Object> args, SkillProgress progress) {
        Skill skill = registry.find(skillId).orElse(null);
        if (skill == null) {
            return SkillResult.fail("技能不存在: " + skillId);
        }
        SkillProgress p = progress == null ? SkillProgress.NOOP : progress;
        log.debug("执行技能 {}: {}", skillId, args);
        return skill.execute(args, p);
    }
}
