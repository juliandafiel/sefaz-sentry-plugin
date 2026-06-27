package br.gov.ba.sefaz.sentry.notify

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class SentryStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(SentryNotifier::class.java).start()
    }
}
