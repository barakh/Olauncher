package app.olauncher.data

import org.json.JSONObject
import java.util.UUID

data class DailyReminder(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var time: String, // HH:mm
    var lastCompletedDay: Int = -1
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("text", text)
        json.put("time", time)
        json.put("lastCompletedDay", lastCompletedDay)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): DailyReminder {
            val json = JSONObject(jsonString)
            return DailyReminder(
                json.getString("id"),
                json.getString("text"),
                json.getString("time"),
                json.getInt("lastCompletedDay")
            )
        }
    }
}
