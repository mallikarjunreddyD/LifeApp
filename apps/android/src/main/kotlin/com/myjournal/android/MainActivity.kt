package com.myjournal.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.myjournal.ui.JournalApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = viewModelFactory {
            initializer { JournalViewModel((application as JournalApplication).editor, createSavedStateHandle()) }
        }
        val model = ViewModelProvider(this, factory)[JournalViewModel::class.java]
        setContent { JournalApp(dailyJournal = { DailyJournalScreen(model) }) }
    }
}
