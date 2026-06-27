package br.gov.ba.sefaz.sentry.settings

import br.gov.ba.sefaz.sentry.api.SentryClient
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class SentryConfigurable : Configurable {

    private val settings = SentrySettings.getInstance()

    private val urlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val orgField = JBTextField()
    private val projectField = JBTextField()
    private val queryField = JBTextField()
    private val periodField = JBTextField()

    override fun getDisplayName(): String = "SEFAZ Sentry"

    override fun createComponent(): JComponent {
        reset()
        return panel {
            row("URL do servidor:") { cell(urlField).align(AlignX.FILL) }
            row("Token (Auth Token):") { cell(tokenField).align(AlignX.FILL) }
            row("Organizacao (slug):") { cell(orgField).align(AlignX.FILL) }
            row("Projeto (slug):") { cell(projectField).align(AlignX.FILL) }
            row("Query:") { cell(queryField).align(AlignX.FILL) }
            row("Periodo (ex.: 14d, 24h, 90d):") { cell(periodField).align(AlignX.FILL) }
            row {
                button("Testar conexao") { testConnection() }
            }
            row {
                comment("Ex.: https://sentry.sefaz.ba.gov.br - org <b>sefaz</b> - projeto <b>efiscalizacao</b> ou <b>web-api</b>. Token via Personal Token (org:read, project:read, event:read).")
            }
        }
    }

    private fun testConnection() {
        val client = SentryClient(urlField.text, String(tokenField.password))
        val org = orgField.text.trim()
        val project = projectField.text.trim()
        try {
            val name = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { client.testConnection(org, project) },
                "Testando conexao com o Sentry...", true, null
            )
            Messages.showInfoMessage("Conexao OK. Projeto: $name", "SEFAZ Sentry")
        } catch (e: Exception) {
            Messages.showErrorDialog(e.message ?: "Falha ao conectar", "SEFAZ Sentry")
        }
    }

    override fun isModified(): Boolean =
        urlField.text != settings.url ||
            String(tokenField.password) != settings.token ||
            orgField.text != settings.organization ||
            projectField.text != settings.project ||
            queryField.text != settings.query ||
            periodField.text != settings.statsPeriod

    override fun apply() {
        settings.url = urlField.text
        settings.token = String(tokenField.password)
        settings.organization = orgField.text
        settings.project = projectField.text
        settings.query = queryField.text
        settings.statsPeriod = periodField.text
    }

    override fun reset() {
        urlField.text = settings.url
        tokenField.text = settings.token
        orgField.text = settings.organization
        projectField.text = settings.project
        queryField.text = settings.query
        periodField.text = settings.statsPeriod
    }
}
