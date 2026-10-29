package com.taikang.yantu.skills

import com.google.gson.JsonParser
import java.nio.file.Path

object SkillUpdateFixture {
    @JvmStatic fun main(args: Array<String>) {
        val project = SkillScanner.LocalSkill("review", "1.0.0", Path.of("project/review"), "project", true, 10)
        val global = SkillScanner.LocalSkill("review", "2.0.0", Path.of("global/review"), "global", true, 20)
        val remote = JsonParser.parseString("""{"items":[{"skillKey":"review","displayName":"代码评审","latestVersionId":20,"latestVersion":"2.0.0"}]}""")
            .asJsonObject.getAsJsonArray("items")
        val candidates = SkillUpdateService().plan(listOf(project, global), remote)
        check(candidates.size == 1 && candidates.single().targets.size == 2)
        check(candidates.single().localVersion == "1.0.0" && candidates.single().latestVersionId == 20L)
        check(SkillUpdateService().plan(listOf(global), remote).isEmpty())
    }
}
