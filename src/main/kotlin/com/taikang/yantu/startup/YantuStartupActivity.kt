package com.taikang.yantu.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.taikang.yantu.telemetry.TelemetryApplicationService

class YantuStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) { TelemetryApplicationService.get().startOnce() }
}
