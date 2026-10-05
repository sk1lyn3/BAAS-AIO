package moe.baas.aio

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Compatibility entry: manual tasks now live in the inline settings sections. */
class ManualTaskActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startActivity(Intent(this, ConfigActivity::class.java)
            .putExtra("profileId", intent.getStringExtra("profileId") ?: ProfileStore(this).selected().id)
            .putExtra("group", 2))
        finish()
    }
}
