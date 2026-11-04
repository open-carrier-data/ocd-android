package io.github.open_carrier_data.check.owner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.open_carrier_data.check.owner.core.WeekQuestion
import io.github.open_carrier_data.check.ui.OcdCheckTheme

/**
 * Round 48: the one screen with the question, Yes and No equal. Leaving it answers nothing, and the answer stays no. No
 * extra, intent or script can say yes here: only a tap on Yes does.
 */
class WeekQuestionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OwnerWeekReport.mayAnswer(applicationContext)) {
            finish()
            return
        }
        enableEdgeToEdge()
        OwnerWeekReport.shown(applicationContext)
        setContent { OcdCheckTheme { WeekQuestionScreen(::answer) } }
    }

    private fun answer(yes: Boolean) {
        OwnerWeekReport.answer(applicationContext, yes)
        finish()
    }
}

@Composable
private fun WeekQuestionScreen(answer: (Boolean) -> Unit) {
    var details by rememberSaveable { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(WeekQuestion.TITLE, style = MaterialTheme.typography.headlineSmall)
            Text(WeekQuestion.BODY, style = MaterialTheme.typography.bodyLarge)
            if (details) Text(WeekQuestion.DETAILS_TEXT, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { details = !details }) { Text(WeekQuestion.DETAILS) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { answer(false) }, modifier = Modifier.weight(1f)) { Text(WeekQuestion.NO) }
                OutlinedButton(onClick = { answer(true) }, modifier = Modifier.weight(1f)) { Text(WeekQuestion.YES) }
            }
        }
    }
}
