package br.gov.ba.sefaz.sentry.navigation

import br.gov.ba.sefaz.sentry.api.SentryFrame
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

object SentryNavigator {

    fun navigate(project: Project, frame: SentryFrame) {
        val file = ReadAction.compute<VirtualFile?, RuntimeException> { findFile(project, frame) }
        if (file == null) {
            notify(project, "Arquivo nao encontrado no projeto: ${fileName(frame)}")
            return
        }
        val line = (frame.lineNo - 1).coerceAtLeast(0)
        OpenFileDescriptor(project, file, line, 0).navigate(true)
    }

    private fun findFile(project: Project, frame: SentryFrame): VirtualFile? {
        val name = fileName(frame)
        if (name.isBlank()) return null
        val matches = FilenameIndex.getVirtualFilesByName(name, GlobalSearchScope.projectScope(project))
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches.first()
        // varios arquivos com o mesmo nome: escolher o que casa com o pacote (module)
        val pkgPath = frame.module.substringBeforeLast('.', "").replace('.', '/')
        return matches.firstOrNull { pkgPath.isNotBlank() && it.path.contains(pkgPath) } ?: matches.first()
    }

    private fun fileName(frame: SentryFrame): String {
        val fromFilename = frame.filename.substringAfterLast('/').substringAfterLast('\\')
        if (fromFilename.isNotBlank()) return fromFilename
        // deriva do module (FQN Java): pega a classe externa
        val cls = frame.module.substringAfterLast('.').substringBefore('$')
        return if (cls.isBlank()) "" else "$cls.java"
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SEFAZ Sentry")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }
}
