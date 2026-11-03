package com.myjournal.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.myjournal.ui.JournalApp

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "My Journal") { JournalApp() }
}
