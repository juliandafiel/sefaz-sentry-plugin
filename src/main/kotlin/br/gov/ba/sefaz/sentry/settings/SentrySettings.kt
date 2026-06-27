package br.gov.ba.sefaz.sentry.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

enum class SentryEnv(val label: String) {
    PROD("Producao"),
    HOM("Homologacao"),
    DEV("Desenvolvimento"),
}

/**
 * Configuracao da integracao com o Sentry self-hosted.
 * Dois ambientes (Producao/Homologacao), cada um com URL e token proprios.
 * URLs/flags ficam no XML; os tokens ficam no PasswordSafe (armazenamento seguro da IDE).
 */
@State(name = "SefazSentrySettings", storages = [Storage("sefazSentry.xml")])
class SentrySettings : PersistentStateComponent<SentrySettings.State> {

    class State {
        var organization: String = "sefaz"
        var project: String = "efiscalizacao, web-api"
        var query: String = "is:unresolved"
        var statsPeriod: String = "14d"
        var limit: Int = 25
        var autoRefresh: Boolean = false
        var refreshSeconds: Int = 30
        var notifyEnabled: Boolean = false
        var notifySeconds: Int = 60
        var notifyProd: Boolean = false
        var notifyHom: Boolean = false
        var notifyDev: Boolean = false

        var prodEnabled: Boolean = true
        var prodUrl: String = "https://sentry.sefaz.ba.gov.br"
        var homEnabled: Boolean = false
        var homUrl: String = "https://hsentry.sefaz.ba.gov.br"
        var devEnabled: Boolean = false
        var devUrl: String = "https://dsentry.sefaz.ba.gov.br"
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) {
        state = s
    }

    var organization: String
        get() = state.organization
        set(v) { state.organization = v.trim() }
    var query: String
        get() = state.query
        set(v) { state.query = v.trim() }
    var statsPeriod: String
        get() = state.statsPeriod
        set(v) { state.statsPeriod = v.trim() }
    var limit: Int
        get() = state.limit
        set(v) { state.limit = v.coerceIn(1, 100) }
    var autoRefresh: Boolean
        get() = state.autoRefresh
        set(v) { state.autoRefresh = v }
    var refreshSeconds: Int
        get() = state.refreshSeconds
        set(v) { state.refreshSeconds = v.coerceIn(5, 3600) }
    var notifyEnabled: Boolean
        get() = state.notifyEnabled
        set(v) { state.notifyEnabled = v }
    var notifySeconds: Int
        get() = state.notifySeconds
        set(v) { state.notifySeconds = v.coerceIn(15, 3600) }
    var project: String
        get() = state.project
        set(v) { state.project = v.trim() }

    /** Projetos configurados (um ou mais slugs separados por virgula). */
    fun projectList(): List<String> =
        project.split(",", ";").map { it.trim() }.filter { it.isNotEmpty() }

    // ---- por ambiente ----
    fun url(env: SentryEnv): String = when (env) {
        SentryEnv.PROD -> state.prodUrl
        SentryEnv.HOM -> state.homUrl
        SentryEnv.DEV -> state.devUrl
    }

    fun setUrl(env: SentryEnv, v: String) = when (env) {
        SentryEnv.PROD -> state.prodUrl = v.trim()
        SentryEnv.HOM -> state.homUrl = v.trim()
        SentryEnv.DEV -> state.devUrl = v.trim()
    }

    fun enabled(env: SentryEnv): Boolean = when (env) {
        SentryEnv.PROD -> state.prodEnabled
        SentryEnv.HOM -> state.homEnabled
        SentryEnv.DEV -> state.devEnabled
    }

    fun setEnabled(env: SentryEnv, v: Boolean) = when (env) {
        SentryEnv.PROD -> state.prodEnabled = v
        SentryEnv.HOM -> state.homEnabled = v
        SentryEnv.DEV -> state.devEnabled = v
    }

    fun token(env: SentryEnv): String =
        PasswordSafe.instance.getPassword(tokenAttributes(env)) ?: ""

    fun setToken(env: SentryEnv, v: String) =
        PasswordSafe.instance.setPassword(tokenAttributes(env), v.ifBlank { null })

    fun enabledEnvs(): List<SentryEnv> = SentryEnv.values().filter { enabled(it) }

    fun isNotify(env: SentryEnv): Boolean = when (env) {
        SentryEnv.PROD -> state.notifyProd
        SentryEnv.HOM -> state.notifyHom
        SentryEnv.DEV -> state.notifyDev
    }

    fun setNotify(env: SentryEnv, v: Boolean) = when (env) {
        SentryEnv.PROD -> state.notifyProd = v
        SentryEnv.HOM -> state.notifyHom = v
        SentryEnv.DEV -> state.notifyDev = v
    }

    /** Ambientes que devem ser vigiados para notificacao (habilitados E marcados). */
    fun notifyEnvs(): List<SentryEnv> = enabledEnvs().filter { isNotify(it) }

    fun isConfigured(env: SentryEnv): Boolean =
        enabled(env) && url(env).isNotBlank() && organization.isNotBlank() &&
            projectList().isNotEmpty() && token(env).isNotBlank()

    fun isAnyConfigured(): Boolean = enabledEnvs().any { isConfigured(it) }

    private fun tokenAttributes(env: SentryEnv): CredentialAttributes =
        CredentialAttributes(generateServiceName("SEFAZ Sentry", "auth-token-${env.name}"))

    companion object {
        @JvmStatic
        fun getInstance(): SentrySettings =
            ApplicationManager.getApplication().getService(SentrySettings::class.java)
    }
}
