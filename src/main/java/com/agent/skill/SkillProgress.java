package com.agent.skill;

/**
 * 技能进度回调。
 */
public interface SkillProgress {

    void report(String stage, int percent);

    /** 丢弃进度的空实现。 */
    SkillProgress NOOP = (stage, percent) -> {
    };
}
