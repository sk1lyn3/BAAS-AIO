package moe.baas.aio

import android.app.Activity
import android.os.Bundle
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Debug-only checks for the actual Android store and original BAAS file contract. */
class ProfileProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val report = JSONObject()
        try {
            val store = ProfileStore(this); store.initialize()
            if (intent.getStringExtra("action") == "toggle") {
                val id = requireNotNull(intent.getStringExtra("profileId"))
                val events = store.events(store.get(id))
                val index = (0 until events.length()).single {
                    events.getJSONObject(it).getString("func_name") == intent.getStringExtra("task")
                }
                store.setEnabled(id, index, intent.getBooleanExtra("enabled", false))
                File(filesDir, "profile-toggle-report.json").writeText(
                    JSONObject().put("state", "completed").put("profileId", id)
                        .put("enabled", store.events(store.get(id)).getJSONObject(index).getBoolean("enabled")).toString())
                finish(); return
            }
            val before = store.list(); val selected = store.selected().id
            val imported = File(filesDir, "profile-test-import.zip").inputStream().use { store.importZip(it) }
            check(imported.size == 4)
            check(before.all { old -> store.get(old.id).path == old.path })
            report.put("imported", JSONArray(imported.map { JSONObject().put("id", it.id).put("name", it.name).put("runnable", it.runnable) }))
            val original = imported.first { it.name.startsWith("JP_Phone") }
            val config = File(original.path, "config.json").readText()
            val events = store.events(original)
            val task = events.getJSONObject(0); val old = task.getBoolean("enabled")
            val nextTick = task.get("next_tick"); val priority = task.get("priority")
            store.setEnabled(original.id, 0, !old)
            val changed = store.events(original).getJSONObject(0)
            check(changed.getBoolean("enabled") == !old && changed.get("next_tick") == nextTick && changed.get("priority") == priority)
            store.setEnabled(original.id, 0, old)
            check(File(original.path, "config.json").readText() == config)
            val output = ByteArrayOutputStream(); store.exportZip(original.id, output)
            val roundTrip = store.importZip(ByteArrayInputStream(output.toByteArray())).single()
            check(File(roundTrip.path, "config.json").readText() == config)
            check(store.events(roundTrip).toString() == store.events(original).toString())
            store.rename(roundTrip.id, "Round trip"); store.delete(roundTrip.id)
            val copy = store.duplicate(original.id)
            check(copy.id != original.id && File(copy.path, "config.json").readText() == config)
            store.delete(copy.id)
            val count = store.list().size
            val bad = ByteArrayOutputStream()
            ZipOutputStream(bad).use { zip ->
                zip.putNextEntry(ZipEntry("../config.json")); zip.write(config.toByteArray()); zip.closeEntry()
            }
            check(runCatching { store.importZip(ByteArrayInputStream(bad.toByteArray())) }.isFailure)
            val incomplete = ByteArrayOutputStream()
            ZipOutputStream(incomplete).use { zip ->
                zip.putNextEntry(ZipEntry("OnlyConfig/config.json")); zip.write(config.toByteArray()); zip.closeEntry()
            }
            check(runCatching { store.importZip(ByteArrayInputStream(incomplete.toByteArray())) }.isFailure)
            check(store.list().size == count)
            TaskRuntime.publish("running", "Profile store verification")
            try {
                store.setEnabled(original.id, 0, !old)
                check(store.events(original).getJSONObject(0).getBoolean("enabled") == !old)
                store.setEnabled(original.id, 0, old)
            } finally { TaskRuntime.publish("idle", "Profile store verification finished") }
            store.select(selected)
            report.put("state", "completed").put("roundTripPreserved", true).put("schedulePreserved", true)
                .put("existingProfilesPreserved", true).put("invalidArchiveRejected", true).put("liveTaskToggleAllowed", true)
        } catch (error: Throwable) { report.put("state", "failed").put("error", android.util.Log.getStackTraceString(error)) }
        File(filesDir, "profile-check-report.json").writeText(report.toString(2))
        finish()
    }
}
