package com.myjournal.domain

import com.myjournal.core.RecordMetadata

// Feature content and handwriting formats are deliberately deferred.
data class JournalEntry(val metadata: RecordMetadata)
data class MonthlyPlan(val metadata: RecordMetadata)
data class Habit(val metadata: RecordMetadata)
data class Notebook(val metadata: RecordMetadata)
