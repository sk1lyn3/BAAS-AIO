package moe.baas.aio

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

/** One page implementation, shared by portrait Activities and the landscape pane. */
abstract class PanePage(protected val activity: Activity, val intent: Intent, private val close: () -> Unit) : ContextWrapper(activity) {
    lateinit var view: View
        private set
    val hasView get() = ::view.isInitialized
    protected fun show(value: View) { view = value }
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
}

abstract class PaneActivity : Activity() {
    private lateinit var page: PanePage
    protected abstract fun createPage(intent: Intent, close: () -> Unit): PanePage
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        page = createPage(intent) { finish() }
        page.onCreate(state)
        if (page.hasView) UiTheme.setContentView(this, page.view)
    }
    override fun onResume() { super.onResume(); page.onResume() }
    override fun onSaveInstanceState(state: Bundle) { page.onSaveInstanceState(state); super.onSaveInstanceState(state) }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data); page.onActivityResult(requestCode, resultCode, data)
    }
    @Deprecated("Platform back API") override fun onBackPressed() = page.onBackPressed()
    override fun onDestroy() { page.onDestroy(); super.onDestroy() }
}

class PaneHost(private val activity: Activity, private val container: FrameLayout, private val home: View, private val returnHome: () -> Unit) {
    private val pages = mutableListOf<PanePage>()
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
        container.removeAllViews(); container.addView(page.view, FrameLayout.LayoutParams(-1, -1))
        page.onResume()
    }
    private fun pop() {
        pages.removeLastOrNull()?.onDestroy()
        container.removeAllViews()
        container.addView(pages.lastOrNull()?.view ?: home, FrameLayout.LayoutParams(-1, -1))
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
