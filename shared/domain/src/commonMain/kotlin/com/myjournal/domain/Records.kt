package com.myjournal.domain

import com.myjournal.core.RecordMetadata
import kotlin.uuid.Uuid

data class Notebook(val metadata: RecordMetadata, val name: String, val description: String? = null,
    val defaultTemplate: PaperTemplate = PaperTemplate.RULED, val archived: Boolean = false) {
    init { require(name.isNotBlank() && name == name.trim() && name.length <= 120); require(description == null || description.length <= 2000) }
}

data class NotebookPage(val metadata: RecordMetadata, val notebookId: Uuid, val order: Int,
    val title: String? = null, val paper: PaperTemplate = PaperTemplate.RULED,
    val width: Float = 1000f, val height: Float = 1414f, val archived: Boolean = false) {
    init { require(order >= 0); require(title == null || title.length <= 120)
        require(width.isFinite() && height.isFinite() && width in 100f..10000f && height in 100f..10000f) }
    val surface get() = InkSurface(metadata.id, width, height, paper = paper)
}

data class NotebookEdit(val notebook: Notebook, val pages: List<NotebookPage>,
    val put: List<InkStroke> = emptyList(), val remove: Set<Uuid> = emptySet()) {
    init {
        require(pages.all { it.notebookId == notebook.metadata.id })
        require(pages.map { it.metadata.id }.distinct().size == pages.size)
        require(pages.map { it.order }.distinct().size == pages.size)
        require(put.all { stroke -> pages.any { it.metadata.id == stroke.pageId } })
        require(put.map { it.metadata.id }.distinct().size == put.size)
        require(put.none { it.metadata.id in remove })
    }
}
