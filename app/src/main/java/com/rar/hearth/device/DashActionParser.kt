package com.rar.hearth.device

import com.rar.hearth.config.Panels
import com.rar.hearth.ui.DashView
import com.rar.hearth.ui.railViews
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parsing/policy for the HA->device actions this app owns (set-view / notify / notify-clear).
 * No android.* imports so it runs in plain-JVM unit tests; effects are applied by AppDeps. Field
 * semantics mirror the HTTP handlers in web/ConfigServer.kt so both paths behave identically.
 */
object DashActionParser {

    /** {view:"cameras"} -> DashView (case-insensitive, trimmed). Null when missing/unknown/non-object. */
    fun parseSetView(payload: JsonElement?): DashView? {
        val obj = payload as? JsonObject ?: return null
        val name = obj["view"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase(Locale.US) ?: return null
        return DashView.entries.firstOrNull { it.name.lowercase(Locale.US) == name }
    }

    /** True when [view] is currently a rail destination (its panel enabled / cameras configured). */
    fun isViewAllowed(view: DashView, panels: Panels, camerasConfigured: Boolean): Boolean =
        railViews(panels, camerasConfigured).contains(view)

    data class NotifyCommand(
        val id: String?,
        val title: String,
        val message: String?,
        val severity: String?,
        val timeoutSeconds: Int?,
    )

    /** Mirrors ConfigServer.handleNotify: blank/missing title -> null (ignore); timeout <=0 -> null. */
    fun parseNotify(payload: JsonElement?): NotifyCommand? {
        val obj = payload as? JsonObject ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isBlank()) return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonPrimitive?.contentOrNull
        val severity = obj["severity"]?.jsonPrimitive?.contentOrNull
        val timeout = obj["timeout"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
        return NotifyCommand(id, title, message, severity, timeout)
    }

    sealed interface NotifyClear {
        data object All : NotifyClear
        data class One(val id: String) : NotifyClear
    }

    /** Mirrors ConfigServer.handleNotifyClear: all==true wins; else a non-blank id; else null. */
    fun parseNotifyClear(payload: JsonElement?): NotifyClear? {
        val obj = payload as? JsonObject ?: return null
        if (obj["all"]?.jsonPrimitive?.booleanOrNull == true) return NotifyClear.All
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank()) return null
        return NotifyClear.One(id)
    }
}
