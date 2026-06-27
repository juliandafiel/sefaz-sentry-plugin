package br.gov.ba.sefaz.sentry.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Configuracao da integracao com o Sentry self-hosted.
 * URL/org/projeto ficam no XML; o token fica no PasswordSafe (armazenamento seguro da IDE).
 */
@State(name = "SefazSentrySettings", storages = [Storage("sefazSentry.xml")])
class SentrySettings : PersistentStateComponent<SentrySettings.State> {

    class State {
        var url: String = "https://sentry.sefaz.ba.gov.br"
        var organization: String = "sefaz"
        var project: String = "efiscalizacao"
        var query: String = "is:unresolved"
        var statsPeriod: String = "14d"
        var limit: Int = 25
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
    }

    var url: String
        get() = state.url
        set(v) { state.url = v.trim() }
    var organization: String
        get() = state.organization
        set(v) { state.organization = v.trim() }
    var project: String
        get() = state.project
        set(v) { state.project = v.trim() }
    var query: String
        get() = state.query
        set(v) { state.query = v.trim() }
    var statsPeriod: String
        get() = state.statsPeriod
        set(v) { state.statsPeriod = v.trim() }
    var limit: Int
        get() = state.limit
        set(v) { state.limit = v.coerceIn(1, 100) }

    var token: String
        get() = PasswordSafe.instance.getPassword(tokenAttributes()) ?: ""
        set(v) = PasswordSafe.instance.setPassword(tokenAttributes(), v.ifBlank { null })

    fun isConfigured(): Boolean =
        url.isNotBlank() && organization.isNotBlank() && project.isNotBlank() && token.isNotBlank()

    private fun tokenAttributes(): CredentialAttributes =
        CredentialAttributes(generateServiceName("SEFAZ Sentry", "auth-token"))

    companion object {
        @JvmStatic
        fun getInstance(): SentrySettings =
            ApplicationManager.getApplication().getService(SentrySettings::class.java)
    }
}
