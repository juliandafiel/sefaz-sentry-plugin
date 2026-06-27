package br.gov.ba.sefaz.sentry.settings

import com.intellij.util.messages.Topic

interface SentrySettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<SentrySettingsListener> =
            Topic.create("SEFAZ Sentry settings", SentrySettingsListener::class.java)
    }
}
