package moe.baas.aio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small configuration host; imported BAAS settings stay in their original files. */
class ProfileActivity : Activity() {
    private lateinit var store: ProfileStore
    private lateinit var content: LinearLayout
    private var exporting: String? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store = ProfileStore(this); store.initialize()
        exporting = state?.getString("exporting")
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 16) }
        setContentView(ScrollView(this).apply { addView(content) })
    }
    override fun onResume() { super.onResume(); render() }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("exporting", exporting) }
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; setOnClickListener { safely(action) }
    }
    private fun render() {
        content.removeAllViews(); val selected = store.selected()
        content.addView(TextView(this).apply { text = "Configurations\nSelected: $selected"; textSize = 21f })
        content.addView(button("Import BAAS configuration ZIP") {
            check(!TaskRuntime.isActive) { "Stop the workflow before importing" }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,
                    arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")), 201)
        })
        for (profile in store.list()) {
            content.addView(button(if (profile.id == selected.id) "✓ $profile" else profile.toString()) {
                store.select(profile.id); render()
            })
        }
        content.addView(TextView(this).apply {
            text = if (selected.runnable) "Daily tasks and managed mode use this configuration."
                else "Saved configuration. Runtime support for this server is coming later."
        })
        content.addView(button("Rename") {
            val input = EditText(this).apply { setText(selected.name) }
            AlertDialog.Builder(this).setTitle("Configuration name").setView(input).setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ -> safely { store.rename(selected.id, input.text.toString()); render() } }.show()
        })
        content.addView(button("Duplicate") { store.select(store.duplicate(selected.id).id); render() })
        content.addView(button("Export ZIP") {
            check(!TaskRuntime.isActive) { "Stop the workflow before exporting" }; exporting = selected.id
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "${selected.name}.zip"), 202)
        })
        content.addView(button("Delete configuration") {
            check(!TaskRuntime.isActive) { "Stop the workflow before deleting" }
            AlertDialog.Builder(this).setMessage("Delete ${selected.name}?").setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> safely { store.delete(selected.id); render() } }.show()
        })
        content.addView(TextView(this).apply { text = "Tasks"; textSize = 20f })
        val events = store.events(selected)
        for (index in 0 until events.length()) {
            val event = events.getJSONObject(index)
            content.addView(CheckBox(this).apply {
                text = event.getString("event_name")
                isChecked = event.getBoolean("enabled")
                setOnCheckedChangeListener { _, enabled -> safely { store.setEnabled(selected.id, index, enabled) } }
            })
        }
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        safely {
            when (requestCode) {
                201 -> contentResolver.openInputStream(uri).use { input ->
                    val profiles = store.importZip(requireNotNull(input)); store.select(profiles.first().id)
                }
                202 -> contentResolver.openOutputStream(uri, "wt").use { output ->
                    store.exportZip(requireNotNull(exporting), requireNotNull(output)); exporting = null
                }
            }
            render()
        }
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (error: Exception) {
            AlertDialog.Builder(this).setMessage(error.message ?: "Configuration operation failed").setPositiveButton("OK", null).show()
        }
    }
}
