package br.gov.ba.sefaz.sentry.toolwindow

import br.gov.ba.sefaz.sentry.api.SentryClient
import br.gov.ba.sefaz.sentry.api.SentryFrame
import br.gov.ba.sefaz.sentry.api.SentryRow
import br.gov.ba.sefaz.sentry.navigation.SentryNavigator
import br.gov.ba.sefaz.sentry.settings.SentryConfigurable
import br.gov.ba.sefaz.sentry.settings.SentryEnv
import br.gov.ba.sefaz.sentry.settings.SentrySettings
import br.gov.ba.sefaz.sentry.settings.SentrySettingsListener
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.HyperlinkEvent

class SentryPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val autoCheckbox = JBCheckBox("Auto")
    private var updatingCombos = false

    private val rowModel = DefaultListModel<SentryRow>()
    private val rowList = JBList(rowModel)
    private val detail = JEditorPane("text/html", "")
    private val frameModel = DefaultListModel<SentryFrame>()
    private val frameList = JBList(frameModel)

    private val envCombo = JComboBox<SentryEnv>()
    private val sourceCombo = JComboBox(arrayOf("Issues", "Traces", "Logs"))
    private val sortCombo = JComboBox(arrayOf("Recentes", "Frequencia", "Novos", "Usuarios"))
    private val projectCombo = JComboBox<String>()
    private val searchField = JBTextField(22)

    init {
        rowList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        rowList.cellRenderer = RowRenderer()
        rowList.addListSelectionListener { showRow(rowList.selectedValue) }
        rowList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) rowList.selectedValue?.permalink?.takeIf { it.isNotBlank() }
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
            firstComponent = JBScrollPane(rowList)
            secondComponent = rightSplitter
        }
        setContent(mainSplitter)
        toolbar = buildToolbar()

        if (SentrySettings.getInstance().isAnyConfigured()) reload()
        else detail.text = html("Configure (habilite um ambiente) em <b>Settings &gt; Tools &gt; SEFAZ Sentry</b> e clique em Atualizar.")

        scheduleAuto()

        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SentrySettingsListener.TOPIC, object : SentrySettingsListener {
                override fun settingsChanged() = repopulateCombos()
            })
    }

    /** Repovoa as combos de ambiente/projeto quando as Settings mudam. */
    private fun repopulateCombos() {
        updatingCombos = true
        try {
            val s = SentrySettings.getInstance()
            val prevEnv = envCombo.selectedItem as? SentryEnv
            envCombo.removeAllItems()
            s.enabledEnvs().forEach { envCombo.addItem(it) }
            if (prevEnv != null && s.enabled(prevEnv)) envCombo.selectedItem = prevEnv

            val prevProj = projectCombo.selectedItem as? String
            projectCombo.removeAllItems()
            projectCombo.addItem(ALL)
            s.projectList().forEach { projectCombo.addItem(it) }
            if (prevProj != null && (prevProj == ALL || s.projectList().contains(prevProj))) {
                projectCombo.selectedItem = prevProj
            }

            autoCheckbox.isSelected = s.autoRefresh
        } finally {
            updatingCombos = false
        }
        scheduleAuto()
        reload()
    }

    /** Polling: o Sentry nao faz push pra IDE, entao re-consultamos a cada N segundos. */
    private fun scheduleAuto() {
        alarm.cancelAllRequests()
        if (!autoCheckbox.isSelected) return
        val sec = SentrySettings.getInstance().refreshSeconds.coerceAtLeast(5)
        alarm.addRequest({ tick() }, sec * 1000)
    }

    private fun tick() {
        if (!autoCheckbox.isSelected) return
        reload()
        scheduleAuto()
    }

    override fun dispose() {
        alarm.cancelAllRequests()
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Atualizar", "Recarregar", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = reload()
        })
        group.add(object : AnAction("Configuracoes", "Abrir configuracoes do SEFAZ Sentry", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SentryConfigurable::class.java)
            }
        })
        val tb = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        tb.targetComponent = this

        val s = SentrySettings.getInstance()
        s.enabledEnvs().forEach { envCombo.addItem(it) }
        envCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, sel: Boolean, focus: Boolean
            ): Component {
                val c = super.getListCellRendererComponent(list, value, index, sel, focus)
                if (value is SentryEnv) text = value.label
                return c
            }
        }
        projectCombo.addItem(ALL)
        s.projectList().forEach { projectCombo.addItem(it) }
        searchField.text = s.query
        searchField.emptyText.text = "buscar (ex.: NullPointer)"

        autoCheckbox.isSelected = s.autoRefresh
        autoCheckbox.toolTipText = "Atualizar automaticamente a cada N segundos (Settings)"

        searchField.addActionListener { if (!updatingCombos) reload() }
        envCombo.addActionListener { if (!updatingCombos) reload() }
        sourceCombo.addActionListener { if (!updatingCombos) reload() }
        sortCombo.addActionListener { if (!updatingCombos) reload() }
        projectCombo.addActionListener { if (!updatingCombos) reload() }
        autoCheckbox.addActionListener {
            if (updatingCombos) return@addActionListener
            SentrySettings.getInstance().autoRefresh = autoCheckbox.isSelected
            scheduleAuto()
            if (autoCheckbox.isSelected) reload()
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(tb.component)
            add(JLabel("Ambiente:")); add(envCombo)
            add(JLabel("Fonte:")); add(sourceCombo)
            add(JLabel("Projeto:")); add(projectCombo)
            add(JLabel("Ordenar:")); add(sortCombo)
            add(JLabel("Busca:")); add(searchField)
            add(autoCheckbox)
        }
    }

    private fun selectedProjects(): List<String> {
        val s = SentrySettings.getInstance()
        val sel = projectCombo.selectedItem as? String
        return if (sel == null || sel == ALL) s.projectList() else listOf(sel)
    }

    private fun selectedSort(): String = when (sortCombo.selectedIndex) {
        1 -> "freq"
        2 -> "new"
        3 -> "user"
        else -> "date"
    }

    private fun selectedEnv(): SentryEnv? = envCombo.selectedItem as? SentryEnv

    private fun sortLabel(source: Int, sort: String): String = when (source) {
        1 -> "Traces"
        2 -> "Logs"
        else -> "Issues/$sort"
    }

    private fun reload() {
        val s = SentrySettings.getInstance()
        val env = selectedEnv()
        if (env == null || !s.isConfigured(env)) {
            detail.text = html("Habilite/configure um ambiente em <b>Settings &gt; Tools &gt; SEFAZ Sentry</b>.")
            return
        }
        val projects = selectedProjects()
        val query = searchField.text.trim()
        val sort = selectedSort()
        val source = sourceCombo.selectedIndex
        frameModel.clear()

        object : Task.Backgroundable(project, "Carregando do Sentry (${env.label})...", true) {
            private var result: List<SentryRow> = emptyList()
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val client = SentryClient(s.url(env), s.token(env))
                    result = when (source) {
                        1 -> client.listTraces(s.organization, projects, query, s.statsPeriod, s.limit)
                        2 -> client.listLogs(s.organization, projects, query, s.statsPeriod, s.limit)
                        else -> client.listIssues(s.organization, projects, query, s.statsPeriod, s.limit, sort)
                    }
                } catch (e: Exception) {
                    error = e.message
                }
            }

            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    rowModel.clear()
                    rowList.clearSelection()
                    if (error != null) {
                        rowList.emptyText.text = "Erro ao carregar (veja o detalhe)"
                        detail.text = html("<span style='color:#c0392b'>Erro:</span> ${escape(error!!)}")
                        return@invokeLater
                    }
                    rowList.emptyText.text = "Nenhum resultado"
                    result.forEach { rowModel.addElement(it) }
                    detail.text = html("${result.size} resultado(s) de <b>${escape(projects.joinToString(", "))}</b> (${env.label} / ${sortLabel(source, sort)}).")
                }
            }
        }.queue()
    }

    private fun showRow(row: SentryRow?) {
        if (row == null) return
        detail.text = row.detailHtml
        frameModel.clear()
        val issueId = row.issueId ?: return
        val s = SentrySettings.getInstance()
        val env = selectedEnv() ?: return
        object : Task.Backgroundable(project, "Carregando stacktrace...", true) {
            private var frames: List<SentryFrame> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                runCatching { frames = SentryClient(s.url(env), s.token(env)).latestEventFrames(issueId) }
            }
            override fun onSuccess() {
                ApplicationManager.getApplication().invokeLater {
                    frameModel.clear()
                    frames.forEach { frameModel.addElement(it) }
                }
            }
        }.queue()
    }

    private class RowRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean
        ): Component {
            val c = super.getListCellRendererComponent(list, value, index, selected, focus)
            if (value is SentryRow) text = value.label
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
        private const val ALL = "Todos"

        private fun html(body: String) =
            "<html><body style='font-family:sans-serif; font-size:11px; margin:6px'>$body</body></html>"

        private fun escape(s: String) = s
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
