package app.olauncher.data

import org.json.JSONObject
import java.util.UUID

data class DailyReminder(
    val id: String = UUID.randomUUID().toString(),
    var text: String,
    var time: String, // HH:mm
    var lastCompletedDay: Int = -1,
    var countStreak: Boolean = false,
    var streakCount: Int = 0,
    var lastCompletedEpochDay: Long = 0L,
    var completedEpochDays: List<Long> = emptyList()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("text", text)
        json.put("time", time)
        json.put("lastCompletedDay", lastCompletedDay)
        json.put("countStreak", countStreak)
        json.put("streakCount", streakCount)
        json.put("lastCompletedEpochDay", lastCompletedEpochDay)
        json.put("completedEpochDays", org.json.JSONArray(completedEpochDays))
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): DailyReminder {
            val json = JSONObject(jsonString)
            val completedDaysJson = json.optJSONArray("completedEpochDays")
            val completedDays = mutableListOf<Long>()
            if (completedDaysJson != null) {
                for (i in 0 until completedDaysJson.length()) {
                    completedDays.add(completedDaysJson.getLong(i))
                }
            }
            return DailyReminder(
                json.getString("id"),
                json.getString("text"),
                json.getString("time"),
                json.optInt("lastCompletedDay", -1),
                json.optBoolean("countStreak", false),
                json.optInt("streakCount", 0),
                json.optLong("lastCompletedEpochDay", 0L),
                completedDays
            )
        }
    }
}
