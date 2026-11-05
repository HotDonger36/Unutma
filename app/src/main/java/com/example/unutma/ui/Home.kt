package com.example.unutma.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.unutma.ui.theme.C
import com.example.unutma.ui.theme.Ty

/** The three main tabs along the bottom. */
enum class HomeTab(val label: String) { NOTES("Notes"), ROUTINES("Routines"), CALENDAR("Calendar") }

/** The main screen: the open tab's content above a bottom bar with Notes · Routines · Calendar. */
@Composable
fun HomeScaffold(tab: HomeTab, onTab: (HomeTab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(C.bg)) {
        // The bar below takes care of the navigation bar's space, so the screens above don't add it again.
        Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
        HDivider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            for (t in HomeTab.entries) {
                val on = t == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onTab(t) }.semantics { selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        when (t) { HomeTab.NOTES -> UI.Text; HomeTab.ROUTINES -> UI.Repeat; HomeTab.CALENDAR -> UI.Calendar },
                        null, tint = if (on) C.accent else C.muted, modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, style = Ty.mono, color = if (on) C.fg else C.muted)
                }
            }
        }
    }
}
