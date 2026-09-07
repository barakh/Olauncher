package app.olauncher.data

import org.json.JSONObject
import java.util.UUID

data class FutureReminder(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var timestamp: Long, // epoch millis when it should appear
    var completed: Boolean = false
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("text", text)
        json.put("timestamp", timestamp)
        json.put("completed", completed)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): FutureReminder {
            val json = JSONObject(jsonString)
            return FutureReminder(
                json.getString("id"),
                json.getString("text"),
                json.getLong("timestamp"),
                json.optBoolean("completed", false)
            )
        }
    }
}