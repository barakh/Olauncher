package app.olauncher

import android.app.Application
import android.app.Service.USAGE_STATS_SERVICE
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.provider.CalendarContract
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AntiDoomBlockedInfo
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.helper.SingleLiveEvent
import app.olauncher.helper.WallpaperWorker
import app.olauncher.helper.convertEpochToMidnight
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getAppsList
import app.olauncher.helper.hasBeenMinutes
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.TimeUnit
import kotlin.math.max


import android.database.ContentObserver
import android.os.Handler
import android.os.Looper

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // Some providers take a moment to update instances after events change
            Handler(Looper.getMainLooper()).postDelayed({
                getNextCalendarEvent()
            }, 1000)
        }
    }

    init {
        appContext.contentResolver.registerContentObserver(
            CalendarContract.CONTENT_URI,
            true,
            calendarObserver
        )
    }

    override fun onCleared() {
        super.onCleared()
        appContext.contentResolver.unregisterContentObserver(calendarObserver)
    }

    val firstOpen = MutableLiveData<Boolean>()
    val refreshHome = MutableLiveData<Boolean>()
    val toggleDateTime = MutableLiveData<Unit>()
    val updateSwipeApps = MutableLiveData<Any>()
    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val antiDoomApps = MutableLiveData<List<AppModel>?>()
    val quarantinedApps = MutableLiveData<List<AppModel>?>()
    val isOlauncherDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()
    val homeAppAlignment = MutableLiveData<Int>()
    val screenTimeValue = MutableLiveData<String>()
    val autoOrderedApps = MutableLiveData<List<AppModel>>()
    val quarantineCount = MutableLiveData<Int>()
    val calendarEvent = MutableLiveData<List<String>?>()
    val testCalendarEvents = SingleLiveEvent<List<String>>()

    val showDialog = SingleLiveEvent<String>()
    val checkForMessages = SingleLiveEvent<Unit?>()
    val resetLauncherLiveData = SingleLiveEvent<Unit?>()
    val showAntiDoomDialog = SingleLiveEvent<AntiDoomBlockedInfo>()
    val clearPermanentNoteFocus = SingleLiveEvent<Unit?>()

    fun selectedApp(appModel: AppModel, flag: Int, trackLaunch: Boolean = true): Boolean {
        when (flag) {
            Constants.FLAG_LAUNCH_APP, Constants.FLAG_ANTIDOOM_APPS, Constants.FLAG_QUARANTINED_APPS -> {
                return launchSelectedApp(appModel, trackLaunch)
            }

            Constants.FLAG_HIDDEN_APPS -> {
                launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
            }

            in Constants.HOME_APP_FLAGS -> {
                setHomeApp(appModel, flag)
            }

            Constants.FLAG_SET_SWIPE_LEFT_APP -> {
                setSwipeLeftApp(appModel)
            }

            Constants.FLAG_SET_SWIPE_RIGHT_APP -> {
                setSwipeRightApp(appModel)
            }

            Constants.FLAG_SET_CLOCK_APP -> {
                prefs.clockAppPackage = appModel.appPackage
                prefs.clockAppUser = appModel.user.toString()
                prefs.clockAppClassName = appModel.activityClassName
            }

            Constants.FLAG_SET_CALENDAR_APP -> {
                prefs.calendarAppPackage = appModel.appPackage
                prefs.calendarAppUser = appModel.user.toString()
                prefs.calendarAppClassName = appModel.activityClassName
            }
        }
        return true
    }

    private fun launchSelectedApp(appModel: AppModel, trackLaunch: Boolean = true): Boolean {
        if (trackLaunch) {
            prefs.setLastClickedTime(appModel.appPackage, appModel.user.toString(), System.currentTimeMillis())
            if (prefs.autoOrderApps) {
                getAutoOrderedApps()
            }
        }
        if (prefs.isAntiDoomApp(appModel.appPackage, appModel.user.toString())) {
            val hiddenUntil = prefs.getAntiDoomHiddenUntil(appModel.appPackage, appModel.user.toString())
            if (hiddenUntil > System.currentTimeMillis()) {
                val remainingMinutes = ((hiddenUntil - System.currentTimeMillis()) / 60000).toInt()
                showAntiDoomDialog.postValue(AntiDoomBlockedInfo(appModel, remainingMinutes.coerceAtLeast(1)))
                return false
            }
            prefs.setAntiDoomHiddenUntil(appModel.appPackage, appModel.user.toString(), System.currentTimeMillis() + Constants.ONE_HOUR_IN_MILLIS)
            getAppList()
            getHiddenApps()
            refreshHome(false)
        }
        launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
        return true
    }

    private fun setHomeApp(appModel: AppModel, flag: Int) {
        val location = flag - Constants.FLAG_SET_HOME_APP_1 + 1
        prefs.setAppName(location, appModel.appLabel)
        prefs.setAppPackage(location, appModel.appPackage)
        prefs.setAppUser(location, appModel.user.toString())
        prefs.setAppActivityClassName(location, appModel.activityClassName)
        prefs.pinLocation(location)
        refreshHome(false)
    }

    private fun setSwipeLeftApp(appModel: AppModel) {
        prefs.appNameSwipeLeft = appModel.appLabel
        prefs.appPackageSwipeLeft = appModel.appPackage
        prefs.appUserSwipeLeft = appModel.user.toString()
        prefs.appActivityClassNameSwipeLeft = appModel.activityClassName
        updateSwipeApps()
    }

    private fun setSwipeRightApp(appModel: AppModel) {
        prefs.appNameSwipeRight = appModel.appLabel
        prefs.appPackageSwipeRight = appModel.appPackage
        prefs.appUserSwipeRight = appModel.user.toString()
        prefs.appActivityClassNameRight = appModel.activityClassName
        updateSwipeApps()
    }

    fun forceLaunchApp(appModel: AppModel) {
        if (prefs.isAntiDoomApp(appModel.appPackage, appModel.user.toString())) {
            prefs.setAntiDoomHiddenUntil(appModel.appPackage, appModel.user.toString(), System.currentTimeMillis() + Constants.ONE_HOUR_IN_MILLIS)
        }
        launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
    }

    fun firstOpen(value: Boolean) {
        firstOpen.postValue(value)
    }

    fun refreshHome(appCountUpdated: Boolean) {
        refreshHome.value = appCountUpdated
    }

    fun toggleDateTime() {
        toggleDateTime.postValue(Unit)
    }

    private fun updateSwipeApps() {
        updateSwipeApps.postValue(Unit)
    }

    private fun launchApp(packageName: String, activityClassName: String?, userHandle: UserHandle) {
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityInfo = launcher.getActivityList(packageName, userHandle)

        val component = if (activityClassName.isNullOrBlank()) {
            // activityClassName will be null for hidden apps.
            when (activityInfo.size) {
                0 -> {
                    appContext.showToast(appContext.getString(R.string.app_not_found))
                    return
                }

                1 -> ComponentName(packageName, activityInfo[0].name)
                else -> ComponentName(packageName, activityInfo[activityInfo.size - 1].name)
            }
        } else {
            ComponentName(packageName, activityClassName)
        }

        try {
            launcher.startMainActivity(component, userHandle, null, null)
        } catch (e: SecurityException) {
            try {
                launcher.startMainActivity(component, android.os.Process.myUserHandle(), null, null)
            } catch (e: Exception) {
                appContext.showToast(appContext.getString(R.string.unable_to_open_app))
            }
        } catch (e: Exception) {
            appContext.showToast(appContext.getString(R.string.unable_to_open_app))
        }
    }

    fun getAppList(includeHiddenApps: Boolean = false) {
        viewModelScope.launch {
            appList.value = getAppsList(appContext, prefs, includeRegularApps = true, includeHiddenApps)
        }
    }

    fun getHiddenApps() {
        viewModelScope.launch {
            hiddenApps.value = getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = true)
        }
    }

    fun getAntiDoomApps() {
        viewModelScope.launch {
            antiDoomApps.value = getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = false, includeAntiDoomApps = true)
        }
    }

    fun getQuarantinedApps() {
        viewModelScope.launch {
            quarantinedApps.value = getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = false, includeAntiDoomApps = false, includeQuarantinedApps = true)
        }
    }

    fun getAutoOrderedApps() {
        viewModelScope.launch {
            val allApps = getAppsList(appContext, prefs, includeRegularApps = true, includeHiddenApps = false)
            val filteredApps = allApps.filter { app ->
                !(prefs.isAntiDoomApp(app.appPackage, app.user.toString()) && prefs.isAppTemporarilyHidden(app.appPackage, app.user.toString()))
            }

            val appsInPinnedSlots = getPinnedApps()
            val availableRecentApps = filteredApps
                .filter { !appsInPinnedSlots.contains("${it.appPackage}|${it.user}") }
                .sortedByDescending { prefs.getLastClickedTime(it.appPackage, it.user.toString()) }

            autoOrderAppsToSlots(availableRecentApps)
            refreshHome.postValue(false)
        }
    }

    private fun getPinnedApps(): Set<String> {
        val appsInPinnedSlots = mutableSetOf<String>()
        for (i in 1..16) {
            if (prefs.isLocationPinned(i)) {
                val pkg = prefs.getAppPackage(i)
                val user = prefs.getAppUser(i)
                if (pkg.isNotEmpty()) appsInPinnedSlots.add("$pkg|$user")
            }
        }
        return appsInPinnedSlots
    }

    private fun autoOrderAppsToSlots(availableRecentApps: List<AppModel>) {
        var poolIndex = 0
        val homeAppsNum = prefs.homeAppsNum

        for (i in 1..16) {
            if (i <= homeAppsNum) {
                if (prefs.isLocationPinned(i)) {
                    val pkg = prefs.getAppPackage(i)
                    val user = prefs.getAppUser(i)
                    if (pkg.isEmpty() || !app.olauncher.helper.isPackageInstalled(appContext, pkg, user)) {
                        prefs.unpinLocation(i)
                        fillSlot(i, availableRecentApps, poolIndex++)
                    }
                } else {
                    fillSlot(i, availableRecentApps, poolIndex++)
                }
            } else {
                clearSlot(i)
            }
        }
    }

    private fun clearSlot(location: Int) {
        prefs.setAppName(location, "")
        prefs.setAppPackage(location, "")
        prefs.setAppUser(location, "")
        prefs.setAppActivityClassName(location, "")
    }

    private fun fillSlot(location: Int, pool: List<AppModel>, index: Int) {
        if (index < pool.size) {
            val app = pool[index]
            prefs.setAppName(location, app.appLabel)
            prefs.setAppPackage(location, app.appPackage)
            prefs.setAppUser(location, app.user.toString())
            prefs.setAppActivityClassName(location, app.activityClassName)
        } else {
            prefs.setAppName(location, "")
            prefs.setAppPackage(location, "")
            prefs.setAppUser(location, "")
            prefs.setAppActivityClassName(location, "")
        }
    }

    fun isOlauncherDefault() {
        isOlauncherDefault.value = isOlauncherDefault(appContext)
    }

    fun updateQuarantineCount() {
        viewModelScope.launch(Dispatchers.IO) {
            val antiDoomApps = prefs.antiDoomApps
            var count = 0
            for (app in antiDoomApps) {
                val parts = app.split("|")
                if (parts.size == 2) {
                    if (prefs.isAppTemporarilyHidden(parts[0], parts[1])) {
                        count++
                    }
                }
            }
            quarantineCount.postValue(count)
        }
    }

    fun testCalendarAgenda() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val beginTime = System.currentTimeMillis()
                val endTime = beginTime + DateUtils.DAY_IN_MILLIS * 7 // Look a week ahead for testing
                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, beginTime)
                ContentUris.appendId(builder, endTime)

                val projection = arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.CALENDAR_ID
                )

                // Query all events regardless of selection, but within the next 7 days
                val cursor = appContext.contentResolver.query(
                    builder.build(),
                    projection,
                    "${CalendarContract.Instances.ALL_DAY} = 0",
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )

                cursor?.use {
                    val events = mutableListOf<String>()
                    while (it.moveToNext() && events.size < 5) {
                        val title = it.getString(0)
                        val start = it.getLong(1)

                        val timeStr = DateUtils.formatDateTime(
                            appContext,
                            start,
                            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE
                        )
                        events.add("$timeStr - $title")
                    }
                    testCalendarEvents.postValue(events)
                } ?: testCalendarEvents.postValue(emptyList())
            } catch (e: Exception) {
                e.printStackTrace()
                testCalendarEvents.postValue(emptyList())
            }
        }
    }

    fun getNextCalendarEvent() {
        if (!prefs.showCalendarEvents) {
            calendarEvent.postValue(null)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val beginTime = System.currentTimeMillis()
                val endTime = beginTime + DateUtils.DAY_IN_MILLIS * 3 // Look up to 3 days ahead
                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, beginTime)
                ContentUris.appendId(builder, endTime)

                val projection = arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_ID
                )

                val selection = if (prefs.selectedCalendars.isNotEmpty()) {
                    val calendarIds = prefs.selectedCalendars.joinToString(",")
                    "${CalendarContract.Instances.ALL_DAY} = 0 AND ${CalendarContract.Instances.CALENDAR_ID} IN ($calendarIds)"
                } else {
                    "${CalendarContract.Instances.ALL_DAY} = 0"
                }

                val cursor = appContext.contentResolver.query(
                    builder.build(),
                    projection,
                    selection,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )

                cursor?.use {
                    val events = mutableListOf<String>()
                    while (it.moveToNext() && events.size < 3) {
                        val title = it.getString(0)
                        val start = it.getLong(1)

                        val isToday = android.text.format.DateUtils.isToday(start)
                        val flags = if (isToday) {
                            android.text.format.DateUtils.FORMAT_SHOW_TIME
                        } else {
                            android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_ABBREV_ALL
                        }

                        val timeStr = android.text.format.DateUtils.formatDateTime(
                            appContext,
                            start,
                            flags
                        )
                        val displayStr = if (isToday) "Today, $timeStr $title" else "$timeStr $title"
                        events.add(displayStr)
                    }
                    if (events.isNotEmpty()) {
                        calendarEvent.postValue(events)
                    } else {
                        calendarEvent.postValue(null)
                    }
                } ?: calendarEvent.postValue(null)
            } catch (e: SecurityException) {
                calendarEvent.postValue(null)
            } catch (e: Exception) {
                e.printStackTrace()
                calendarEvent.postValue(null)
            }
        }
    }

//    fun resetDefaultLauncherApp(context: Context) {
//        resetDefaultLauncher(context)
//        launcherResetFailed.value = getDefaultLauncherPackage(appContext).contains(".")
//    }

    fun setWallpaperWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val uploadWorkRequest = PeriodicWorkRequestBuilder<WallpaperWorker>(8, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager
            .getInstance(appContext)
            .enqueueUniquePeriodicWork(
                Constants.WALLPAPER_WORKER_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                uploadWorkRequest
            )
    }

    fun cancelWallpaperWorker() {
        WorkManager.getInstance(appContext).cancelUniqueWork(Constants.WALLPAPER_WORKER_NAME)
        prefs.dailyWallpaperUrl = ""
        prefs.dailyWallpaper = false
    }

    fun updateHomeAlignment(gravity: Int) {
        prefs.homeAlignment = gravity
        homeAppAlignment.value = prefs.homeAlignment
    }

    fun getTodaysScreenTime() {
        if (screenTimeValue.value != null && prefs.screenTimeLastUpdated.hasBeenMinutes(1).not()) return

        val usageStatsManager = appContext.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val beginTime = System.currentTimeMillis().convertEpochToMidnight()
        val endTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(beginTime, endTime)

        val totalScreenOnTime = calculateScreenOnTime(events, beginTime, endTime)
        val viewTimeSpent = appContext.formattedTimeSpent(totalScreenOnTime)
        screenTimeValue.postValue(viewTimeSpent)
        prefs.screenTimeLastUpdated = System.currentTimeMillis()
    }

    private fun calculateScreenOnTime(events: UsageEvents, beginTime: Long, endTime: Long): Long {
        var totalTime = 0L
        var screenOnTimestamp = 0L
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> {
                        screenOnTimestamp = event.timeStamp
                    }
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                        if (screenOnTimestamp != 0L) {
                            totalTime += event.timeStamp - screenOnTimestamp
                            screenOnTimestamp = 0L
                        } else if (totalTime == 0L) {
                            // First event is off, so it must have been on since beginTime
                            totalTime += event.timeStamp - beginTime
                        }
                    }
                }
            } else {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (screenOnTimestamp == 0L) screenOnTimestamp = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (screenOnTimestamp != 0L) {
                            totalTime += event.timeStamp - screenOnTimestamp
                            screenOnTimestamp = 0L
                        }
                    }
                }
            }
        }
        if (screenOnTimestamp != 0L) {
            totalTime += endTime - screenOnTimestamp
        }
        return totalTime
    }
}
