package com.myjournal.domain

import kotlin.uuid.Uuid

interface NotebookRepository {
    suspend fun all(): List<Notebook>
    suspend fun pages(notebookId: Uuid): List<NotebookPage>
    suspend fun loadStrokes(pageId: Uuid): List<InkStroke>
    suspend fun apply(edit: NotebookEdit)
}
