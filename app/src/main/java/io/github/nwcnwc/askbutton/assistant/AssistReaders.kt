package io.github.nwcnwc.askbutton.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import io.github.nwcnwc.askbutton.logic.WebUrls

object AssistReaders {
    data class Dump(val parts: List<String>, val urls: List<String>)

    fun pageUrl(content: AssistContent?): String? {
        if (content == null) return null
        val web = content.webUri?.toString()
        if (!web.isNullOrBlank()) return web
        return content.intent?.dataString
    }

    fun dump(structure: AssistStructure): Dump {
        val parts = ArrayList<String>()
        for (i in 0 until structure.windowNodeCount) {
            val window = structure.getWindowNodeAt(i)
            window.title?.toString()?.let { parts.add(it) }
            walk(window.rootViewNode, parts)
        }
        return Dump(parts, WebUrls.urlsIn(parts))
    }

    private fun walk(node: AssistStructure.ViewNode?, parts: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.let { parts.add(it) }
        val description = node.contentDescription?.toString()
        if (!description.isNullOrBlank()) parts.add(description)
        val domain = node.webDomain
        if (!domain.isNullOrBlank()) parts.add("domain: $domain")
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), parts)
        }
    }
}
