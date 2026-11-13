package com.myjournal.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.myjournal.ui.JournalApp

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "LifeApp", icon = painterResource("lifeapp.png")) { JournalApp() }
}
