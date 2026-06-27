package br.gov.ba.sefaz.sentry.notify

import br.gov.ba.sefaz.sentry.api.SentryClient
import br.gov.ba.sefaz.sentry.api.SentryRow
import br.gov.ba.sefaz.sentry.settings.SentryEnv
import br.gov.ba.sefaz.sentry.settings.SentrySettings
import br.gov.ba.sefaz.sentry.settings.SentrySettingsListener
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm

/**
 * Servico de fundo: faz polling dos ambientes marcados para notificacao e,
 * ao detectar issues novos, dispara um balloon no IntelliJ.
 * O Sentry nao faz push, entao a deteccao e por polling + diff de ids vistos.
 */
@Service(Service.Level.PROJECT)
class SentryNotifier(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val seenByEnv = HashMap<String, MutableSet<String>>()
    private val baselined = HashSet<String>()

    fun start() {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SentrySettingsListener.TOPIC, object : SentrySettingsListener {
                override fun settingsChanged() = reschedule()
            })
        reschedule()
    }

    fun reschedule() {
        alarm.cancelAllRequests()
        val s = SentrySettings.getInstance()
        if (!s.notifyEnabled || s.notifyEnvs().isEmpty()) return
        alarm.addRequest({ poll() }, s.notifySeconds.coerceAtLeast(15) * 1000)
    }

    private fun poll() {
        val s = SentrySettings.getInstance()
        try {
            if (s.notifyEnabled) s.notifyEnvs().forEach { env -> runCatching { checkEnv(s, env) } }
        } finally {
            reschedule()
        }
    }

    private fun checkEnv(s: SentrySettings, env: SentryEnv) {
        if (!s.isConfigured(env)) return
        val rows = SentryClient(s.url(env), s.token(env))
            .listIssues(s.organization, s.projectList(), "is:unresolved", s.statsPeriod, 25, "new")
        val ids = rows.mapNotNull { it.issueId }.toSet()
        val known = seenByEnv.getOrPut(env.name) { HashSet() }
        if (env.name !in baselined) {
            // primeira passada: registra o estado atual sem notificar (evita enxurrada)
            known.addAll(ids)
            baselined.add(env.name)
            return
        }
        val fresh = ids - known
        if (fresh.isEmpty()) return
        known.addAll(ids)
        notify(env, rows.filter { it.issueId in fresh })
    }

    private fun notify(env: SentryEnv, rows: List<SentryRow>) {
        if (rows.isEmpty()) return
        val first = rows.first()
        val title = "SEFAZ Sentry - ${rows.size} erro(s) novo(s) em ${env.label}"
        ApplicationManager.getApplication().invokeLater {
            val n = NotificationGroupManager.getInstance()
                .getNotificationGroup("SEFAZ Sentry")
                .createNotification(title, first.label, NotificationType.WARNING)
            if (first.permalink.isNotBlank()) {
                n.addAction(NotificationAction.createSimple("Abrir no Sentry") {
                    BrowserUtil.browse(first.permalink)
                })
            }
            n.notify(project)
        }
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }
}
