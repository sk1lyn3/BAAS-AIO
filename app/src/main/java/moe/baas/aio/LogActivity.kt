package moe.baas.aio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

class LogActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(newBase); Appearance.apply(this, newBase) }
    private var follows by mutableStateOf(true)
    private var query by mutableStateOf("")
    private var level by mutableIntStateOf(0)
    private val model = LogModel()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LiveTask.start()
        follows = state?.getBoolean("follow") ?: intent.getBooleanExtra("follow", true)
        query = state?.getString("query") ?: intent.getStringExtra("query") ?: ""
        level = state?.getInt("level") ?: intent.getIntExtra("level", 0)
        val root = ComposeView(this).apply {
            val p = UiTheme.dp(this@LogActivity, 12); setPadding(p, p, p, p)
            setContent {
                Ui.Ripples {
                    model.Track(LiveTask.logs, query, level)
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.Top) {
                            BText("运行日志", 22f, weight = androidx.compose.ui.text.font.FontWeight.ExtraBold, modifier = Modifier.weight(1f).item())
                            ToggleButton("自动滚动", follows) { follows = !follows }
                            BButton("返回", flat = true) { finish() }
                        }
                        LogSearch(query, { query = it }, Modifier.fillMaxWidth().item())
                        Row(Modifier.fillMaxWidth().item(), verticalAlignment = Alignment.CenterVertically) {
                            LogLevels(level, Modifier.weight(1f).item()) { level = it }
                            LogCount(model, Modifier.width(80.dp))
                        }
                        LogOutput(model, follows, 8.dp, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
        UiTheme.setContentView(this, root)
    }
    override fun onSaveInstanceState(state: Bundle) {
        state.putBoolean("follow", follows); state.putString("query", query); state.putInt("level", level)
        super.onSaveInstanceState(state)
    }
}
