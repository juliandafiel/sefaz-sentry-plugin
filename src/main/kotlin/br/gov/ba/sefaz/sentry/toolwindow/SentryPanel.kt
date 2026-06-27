package br.gov.ba.sefaz.sentry.toolwindow

import br.gov.ba.sefaz.sentry.api.SentryClient
import br.gov.ba.sefaz.sentry.api.SentryFrame
import br.gov.ba.sefaz.sentry.api.SentryIssue
import br.gov.ba.sefaz.sentry.navigation.SentryNavigator
import br.gov.ba.sefaz.sentry.settings.SentryConfigurable
import br.gov.ba.sefaz.sentry.settings.SentrySettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent

class SentryPanel(private val project: Project) : SimpleToolWindowPanel(true, true) {

    private val issueModel = DefaultListModel<SentryIssue>()
    private val issueList = JBList(issueModel)
    private val detail = JEditorPane("text/html", "")
    private val frameModel = DefaultListModel<SentryFrame>()
    private val frameList = JBList(frameModel)

    init {
        issueList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        issueList.cellRenderer = IssueRenderer()
        issueList.addListSelectionListener { showIssue(issueList.selectedValue) }
        issueList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) issueList.selectedValue?.permalink?.takeIf { it.isNotBlank() }
                    ?.let { BrowserUtil.browse(it) }
            }
        })

        detail.isEditable = false
        detail.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it) }
        }

        frameList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        frameList.cellRenderer = FrameRenderer()
        frameList.emptyText.text = "Selecione um issue para ver o stacktrace"
        frameList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) frameList.selectedValue?.let { SentryNavigator.navigate(project, it) }
            }
        })

        val rightSplitter = OnePixelSplitter(true, 0.45f).apply {
            firstComponent = JBScrollPane(detail)
            secondComponent = JBScrollPane(frameList)
        }
        val mainSplitter = OnePixelSplitter(false, 0.40f).apply {
            firstComponent = JBScrollPane(issueList)
            secondComponent = rightSplitter
        }
        setContent(mainSplitter)
        toolbar = createToolbar()

        if (SentrySettings.getInstance().isConfigured()) refresh()
        else detail.text = html("Configure em <b>Settings &gt; Tools &gt; SEFAZ Sentry</b> e clique em Atualizar.")
    }

    private fun createToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Atualizar", "Recarregar issues", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = refresh()
        })
        group.add(object : AnAction("Configuracoes", "Abrir configuracoes do SEFAZ Sentry", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SentryConfigurable::class.java)
            }
        })
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this
        return tb.component
    }

    private fun refresh() {
        val s = SentrySettings.getInstance()
        if (!s.isConfigured()) {
            detail.text = html("Faltam configuracoes (URL/token/org/projeto). Abra as Configuracoes.")
            return
        }
        frameModel.clear()
        object : Task.Backgroundable(project, "Carregando issues do Sentry...", true) {
            private var result: List<SentryIssue> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    result = SentryClient(s.url, s.token)
                        .listIssues(s.organization, s.project, s.query, s.statsPeriod, s.limit)
                } catch (e: Exception) {
                    error = e.message
                }
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    if (error != null) {
                        detail.text = html("<span style='color:#c0392b'>Erro:</span> ${escape(error!!)}")
                        return@invokeLater
                    }
                    issueModel.clear()
                    result.forEach { issueModel.addElement(it) }
                    detail.text = html("${result.size} issue(s) de <b>${escape(s.project)}</b>. Selecione um para ver o stacktrace.")
                }
            }
        }.queue()
    }

    private fun showIssue(issue: SentryIssue?) {
        if (issue == null) return
        detail.text = html(buildString {
            append("<h3>${escape(issue.title)}</h3>")
            append("<b>${escape(issue.shortId)}</b> &middot; nivel: ${escape(issue.level)}<br/>")
            append("eventos: ${escape(issue.count)} &middot; usuarios: ${issue.userCount}<br/>")
            append("culprit: <code>${escape(issue.culprit)}</code><br/>")
            append("ultimo evento: ${escape(issue.lastSeen)}<br/><br/>")
            if (issue.permalink.isNotBlank())
                append("<a href='${escape(issue.permalink)}'>Abrir no Sentry &rarr;</a>")
        })
        loadFrames(issue.id)
    }

    private fun loadFrames(issueId: String) {
        frameModel.clear()
        val s = SentrySettings.getInstance()
        object : Task.Backgroundable(project, "Carregando stacktrace...", true) {
            private var frames: List<SentryFrame> = emptyList()

            override fun run(indicator: ProgressIndicator) {
                runCatching { frames = SentryClient(s.url, s.token).latestEventFrames(issueId) }
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    frameModel.clear()
                    frames.forEach { frameModel.addElement(it) }
                }
            }
        }.queue()
    }

    private class IssueRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, focus)
            if (value is SentryIssue) {
                text = "[${value.level}] ${value.shortId}  ${value.title}  (${value.count})"
            }
            return c
        }
    }

    private class FrameRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, focus)
            if (value is SentryFrame) {
                val cls = value.module.substringAfterLast('.').ifBlank { value.filename }
                text = "$cls.${value.function}  (${value.filename}:${value.lineNo})"
                border = JBUI.Borders.empty(1, 4)
                if (!selected) foreground = if (value.inApp) list?.foreground else Color.GRAY
            }
            return c
        }
    }

    companion object {
        private fun html(body: String) =
            "<html><body style='font-family:sans-serif; font-size:11px; margin:6px'>$body</body></html>"

        private fun escape(s: String) = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
