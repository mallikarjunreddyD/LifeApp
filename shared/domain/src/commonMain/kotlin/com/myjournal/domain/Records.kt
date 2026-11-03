package com.myjournal.domain

import com.myjournal.core.RecordMetadata

// Other feature records remain foundations only.
data class MonthlyPlan(val metadata: RecordMetadata)
data class Habit(val metadata: RecordMetadata)
data class Notebook(val metadata: RecordMetadata)
