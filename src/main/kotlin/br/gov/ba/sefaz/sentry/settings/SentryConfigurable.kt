package br.gov.ba.sefaz.sentry.settings

import br.gov.ba.sefaz.sentry.api.SentryClient
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class SentryConfigurable : Configurable {

    private val settings = SentrySettings.getInstance()

    private val prodEnabled = JBCheckBox("Habilitar Producao")
    private val prodUrl = JBTextField()
    private val prodToken = JBPasswordField()

    private val homEnabled = JBCheckBox("Habilitar Homologacao")
    private val homUrl = JBTextField()
    private val homToken = JBPasswordField()

    private val devEnabled = JBCheckBox("Habilitar Desenvolvimento")
    private val devUrl = JBTextField()
    private val devToken = JBPasswordField()

    private val orgField = JBTextField()
    private val projectField = JBTextField()
    private val queryField = JBTextField()
    private val periodField = JBTextField()
    private val refreshField = JBTextField()

    private val notifyBox = JBCheckBox("Habilitar notificacoes de erro novo")
    private val notifyProd = JBCheckBox("Producao")
    private val notifyHom = JBCheckBox("Homologacao")
    private val notifyDev = JBCheckBox("Desenvolvimento")
    private val notifyInterval = JBTextField()

    override fun getDisplayName(): String = "SEFAZ Sentry"

    override fun createComponent(): JComponent {
        reset()
        return panel {
            group("Producao") {
                row { cell(prodEnabled) }
                row("URL:") { cell(prodUrl).align(AlignX.FILL) }
                row("Token:") { cell(prodToken).align(AlignX.FILL) }
            }
            group("Homologacao") {
                row { cell(homEnabled) }
                row("URL:") { cell(homUrl).align(AlignX.FILL) }
                row("Token:") { cell(homToken).align(AlignX.FILL) }
            }
            group("Desenvolvimento") {
                row { cell(devEnabled) }
                row("URL:") { cell(devUrl).align(AlignX.FILL) }
                row("Token:") { cell(devToken).align(AlignX.FILL) }
            }
            group("Comum aos ambientes") {
                row("Organizacao (slug):") { cell(orgField).align(AlignX.FILL) }
                row("Projetos (slugs, virgula):") { cell(projectField).align(AlignX.FILL) }
                row("Query:") { cell(queryField).align(AlignX.FILL) }
                row("Periodo (ex.: 14d, 24h, 90d):") { cell(periodField).align(AlignX.FILL) }
                row("Auto-atualizar (segundos, min. 5):") { cell(refreshField).align(AlignX.FILL) }
            }
            group("Notificacoes (erro novo)") {
                row { cell(notifyBox) }
                row("Vigiar ambientes:") { cell(notifyProd); cell(notifyHom); cell(notifyDev) }
                row("Intervalo (segundos, min. 15):") { cell(notifyInterval).align(AlignX.FILL) }
            }
            row {
                button("Testar conexao") { testConnection() }
                button("Testar notificacao") { testNotification() }
            }
            row {
                comment("Prod: <b>https://sentry.sefaz.ba.gov.br</b> &middot; Hom: <b>https://hsentry.sefaz.ba.gov.br</b>. Cada ambiente tem token proprio. Projetos: <b>efiscalizacao, web-api</b>. Token via Personal Token (org:read, project:read, event:read).")
            }
        }
    }

    private fun testNotification() {
        val project = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.firstOrNull()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SEFAZ Sentry")
            .createNotification(
                "SEFAZ Sentry",
                "Notificacao de teste - se voce ve este balloon, as notificacoes funcionam.",
                NotificationType.WARNING
            )
            .notify(project)
    }

    private fun testConnection() {
        val org = orgField.text.trim()
        val projects = projectField.text.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }
        val envs = buildList {
            if (prodEnabled.isSelected) add(Triple(SentryEnv.PROD, prodUrl.text, String(prodToken.password)))
            if (homEnabled.isSelected) add(Triple(SentryEnv.HOM, homUrl.text, String(homToken.password)))
            if (devEnabled.isSelected) add(Triple(SentryEnv.DEV, devUrl.text, String(devToken.password)))
        }
        if (envs.isEmpty()) {
            Messages.showErrorDialog("Habilite ao menos um ambiente.", "SEFAZ Sentry")
            return
        }
        try {
            val msg = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable {
                    envs.joinToString("\n") { (env, url, tok) ->
                        val r = try {
                            "OK (" + SentryClient(url, tok).testConnection(org, projects) + ")"
                        } catch (e: Exception) {
                            "ERRO - ${e.message}"
                        }
                        "${env.label}: $r"
                    }
                },
                "Testando conexao com o Sentry...", true, null
            )
            Messages.showInfoMessage(msg, "SEFAZ Sentry")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: "Falha ao conectar", "SEFAZ Sentry")
        }
    }

    override fun isModified(): Boolean =
        prodEnabled.isSelected != settings.enabled(SentryEnv.PROD) ||
            prodUrl.text != settings.url(SentryEnv.PROD) ||
            String(prodToken.password) != settings.token(SentryEnv.PROD) ||
            homEnabled.isSelected != settings.enabled(SentryEnv.HOM) ||
            homUrl.text != settings.url(SentryEnv.HOM) ||
            String(homToken.password) != settings.token(SentryEnv.HOM) ||
            devEnabled.isSelected != settings.enabled(SentryEnv.DEV) ||
            devUrl.text != settings.url(SentryEnv.DEV) ||
            String(devToken.password) != settings.token(SentryEnv.DEV) ||
            orgField.text != settings.organization ||
            projectField.text != settings.project ||
            queryField.text != settings.query ||
            periodField.text != settings.statsPeriod ||
            refreshField.text != settings.refreshSeconds.toString() ||
            notifyBox.isSelected != settings.notifyEnabled ||
            notifyProd.isSelected != settings.isNotify(SentryEnv.PROD) ||
            notifyHom.isSelected != settings.isNotify(SentryEnv.HOM) ||
            notifyDev.isSelected != settings.isNotify(SentryEnv.DEV) ||
            notifyInterval.text != settings.notifySeconds.toString()

    override fun apply() {
        settings.setEnabled(SentryEnv.PROD, prodEnabled.isSelected)
        settings.setUrl(SentryEnv.PROD, prodUrl.text)
        settings.setToken(SentryEnv.PROD, String(prodToken.password))
        settings.setEnabled(SentryEnv.HOM, homEnabled.isSelected)
        settings.setUrl(SentryEnv.HOM, homUrl.text)
        settings.setToken(SentryEnv.HOM, String(homToken.password))
        settings.setEnabled(SentryEnv.DEV, devEnabled.isSelected)
        settings.setUrl(SentryEnv.DEV, devUrl.text)
        settings.setToken(SentryEnv.DEV, String(devToken.password))
        settings.organization = orgField.text
        settings.project = projectField.text
        settings.query = queryField.text
        settings.statsPeriod = periodField.text
        settings.refreshSeconds = refreshField.text.trim().toIntOrNull() ?: 30
        settings.notifyEnabled = notifyBox.isSelected
        settings.setNotify(SentryEnv.PROD, notifyProd.isSelected)
        settings.setNotify(SentryEnv.HOM, notifyHom.isSelected)
        settings.setNotify(SentryEnv.DEV, notifyDev.isSelected)
        settings.notifySeconds = notifyInterval.text.trim().toIntOrNull() ?: 60
        ApplicationManager.getApplication().messageBus
            .syncPublisher(SentrySettingsListener.TOPIC).settingsChanged()
    }

    override fun reset() {
        prodEnabled.isSelected = settings.enabled(SentryEnv.PROD)
        prodUrl.text = settings.url(SentryEnv.PROD)
        prodToken.text = settings.token(SentryEnv.PROD)
        homEnabled.isSelected = settings.enabled(SentryEnv.HOM)
        homUrl.text = settings.url(SentryEnv.HOM)
        homToken.text = settings.token(SentryEnv.HOM)
        devEnabled.isSelected = settings.enabled(SentryEnv.DEV)
        devUrl.text = settings.url(SentryEnv.DEV)
        devToken.text = settings.token(SentryEnv.DEV)
        orgField.text = settings.organization
        projectField.text = settings.project
        queryField.text = settings.query
        periodField.text = settings.statsPeriod
        refreshField.text = settings.refreshSeconds.toString()
        notifyBox.isSelected = settings.notifyEnabled
        notifyProd.isSelected = settings.isNotify(SentryEnv.PROD)
        notifyHom.isSelected = settings.isNotify(SentryEnv.HOM)
        notifyDev.isSelected = settings.isNotify(SentryEnv.DEV)
        notifyInterval.text = settings.notifySeconds.toString()
    }
}
