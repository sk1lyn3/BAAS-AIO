package moe.baas.aio

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp

/** One page implementation, shared by portrait Activities and the landscape pane. */
abstract class PanePage(protected val activity: Activity, val intent: Intent, private val close: () -> Unit) : ContextWrapper(activity) {
    var hasView = false
        private set
    /** True when the page root is UiTheme.page (pale, 12dp padding); false for a bare ScrollView root. */
    protected open val pageRoot = true
    protected fun show() { hasView = true }
    protected fun finish() = close()
    protected fun runOnUiThread(action: () -> Unit) = activity.runOnUiThread(action)
    @Suppress("DEPRECATION")
    protected fun startActivityForResult(intent: Intent, request: Int) = activity.startActivityForResult(intent, request)
    override fun startActivity(intent: Intent) = activity.startActivity(intent)
    open fun onCreate(state: Bundle?) {}
    open fun onResume() {}
    open fun onSaveInstanceState(state: Bundle) {}
    open fun onActivityResult(request: Int, result: Int, data: Intent?) {}
    open fun onBackPressed() = finish()
    open fun onDestroy() {}
    @Composable protected abstract fun Content()

    /** Page root inside HomeActivity's landscape pane. */
    @Composable
    fun PaneRoot() {
        Box(Modifier.fillMaxSize().then(if (pageRoot) Modifier.haloBackdrop().padding(12.dp) else Modifier)) { Ui.Ripples { Content() } }
    }

    /** Root view for a portrait PaneActivity (insets are added to this view's padding). */
    fun createView(): ComposeView = ComposeView(activity).apply {
        if (pageRoot) {
            val p = UiTheme.dp(activity, 12); setPadding(p, p, p, p)
        }
        setContent { Ui.Ripples { this@PanePage.Content() } }
    }
}

abstract class PaneActivity : ComponentActivity() {
    private lateinit var page: PanePage
    protected abstract fun createPage(intent: Intent, close: () -> Unit): PanePage
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        LiveTask.start()
        page = createPage(intent) { finish() }
        page.onCreate(state)
        if (page.hasView) UiTheme.setContentView(this, page.createView())
    }
    override fun onResume() { super.onResume(); page.onResume() }
    override fun onSaveInstanceState(state: Bundle) { page.onSaveInstanceState(state); super.onSaveInstanceState(state) }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data); page.onActivityResult(requestCode, resultCode, data)
    }
    @Deprecated("Platform back API") override fun onBackPressed() = page.onBackPressed()
    override fun onDestroy() { page.onDestroy(); super.onDestroy() }
}

class PaneHost(private val activity: Activity, private val returnHome: () -> Unit) {
    val pages = mutableStateListOf<PanePage>()
    fun handles(intent: Intent) = intent.component?.className in setOf(ProfileActivity::class.java.name, ConfigActivity::class.java.name, ScheduleActivity::class.java.name)
    fun open(intent: Intent, state: Bundle? = null) {
        val page = when (intent.component?.className) {
            ProfileActivity::class.java.name -> ProfilePage(activity, intent) { pop() }
            ConfigActivity::class.java.name -> ConfigPage(activity, intent) { pop() }
            ScheduleActivity::class.java.name -> SchedulePage(activity, intent) { pop() }
            else -> return
        }
        page.onCreate(state)
        if (!page.hasView) return
        pages.add(page)
        page.onResume()
    }
    private fun pop() {
        pages.removeLastOrNull()?.onDestroy()
        if (pages.isEmpty()) returnHome() else pages.last().onResume()
    }
    fun back(): Boolean {
        val page = pages.lastOrNull() ?: return false
        page.onBackPressed(); return true
    }
    fun resume() { pages.lastOrNull()?.onResume() }
    fun result(request: Int, result: Int, data: Intent?) { pages.lastOrNull()?.onActivityResult(request, result, data) }
    fun save(): Bundle = Bundle().apply {
        putInt("count", pages.size)
        pages.forEachIndexed { index, page ->
            putBundle(index.toString(), Bundle().apply { putParcelable("intent", page.intent); page.onSaveInstanceState(this) })
        }
    }
    @Suppress("DEPRECATION")
    fun restore(state: Bundle?) {
        for (index in 0 until (state?.getInt("count") ?: 0)) {
            val saved = state?.getBundle(index.toString()) ?: continue
            val intent = saved.getParcelable<Intent>("intent") ?: continue
            open(intent, saved)
        }
    }
    fun destroy() { pages.forEach { it.onDestroy() }; pages.clear() }
}
