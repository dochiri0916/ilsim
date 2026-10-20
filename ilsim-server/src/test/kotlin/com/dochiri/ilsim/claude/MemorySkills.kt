package com.dochiri.ilsim.claude

import com.dochiri.ilsim.claude.application.port.out.ClaudeSkillStore
import com.dochiri.ilsim.claude.domain.ClaudeSkill

class MemorySkills : ClaudeSkillStore {
    private val skills = linkedMapOf<String, ClaudeSkill>()
    override fun all() = skills.values.toList()
    override fun save(skill: ClaudeSkill) { skills[skill.name] = skill }
    override fun delete(name: String) = skills.remove(name) != null
}
