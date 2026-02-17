package app.olauncher

import android.app.Application
import android.app.Service.USAGE_STATS_SERVICE
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
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
import app.olauncher.helper.AppUsageStats
import app.olauncher.helper.AppUsageStatsBucket
import app.olauncher.helper.SingleLiveEvent
import app.olauncher.helper.WallpaperWorker
import app.olauncher.helper.convertEpochToMidnight
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getAppsList
import app.olauncher.helper.hasBeenMinutes
import app.olauncher.helper.isOlauncherDefault
import app.olauncher.helper.showToast
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlin.math.max


class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)

    val firstOpen = MutableLiveData<Boolean>()
    val refreshHome = MutableLiveData<Boolean>()
    val toggleDateTime = MutableLiveData<Unit>()
    val updateSwipeApps = MutableLiveData<Any>()
    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val isOlauncherDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()
    val homeAppAlignment = MutableLiveData<Int>()
    val screenTimeValue = MutableLiveData<String>()

    val showDialog = SingleLiveEvent<String>()
    val checkForMessages = SingleLiveEvent<Unit?>()
    val resetLauncherLiveData = SingleLiveEvent<Unit?>()
    val showAntiDoomDialog = SingleLiveEvent<AntiDoomBlockedInfo>()

    fun selectedApp(appModel: AppModel, flag: Int) {
        when (flag) {
            Constants.FLAG_LAUNCH_APP -> {
                if (prefs.isAntiDoomApp(appModel.appPackage, appModel.user.toString())) {
                    val hiddenUntil = prefs.getAntiDoomHiddenUntil(appModel.appPackage, appModel.user.toString())
                    if (hiddenUntil > System.currentTimeMillis()) {
                        val remainingMinutes = ((hiddenUntil - System.currentTimeMillis()) / 60000).toInt()
                        showAntiDoomDialog.postValue(AntiDoomBlockedInfo(appModel, remainingMinutes.coerceAtLeast(1)))
                        return
                    }
                    prefs.setAntiDoomHiddenUntil(appModel.appPackage, appModel.user.toString(), System.currentTimeMillis() + Constants.ONE_HOUR_IN_MILLIS)
                    getAppList()
                    getHiddenApps()
                    refreshHome(false)
                }
                launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
            }

            Constants.FLAG_HIDDEN_APPS -> {
                launchApp(appModel.appPackage, appModel.activityClassName, appModel.user)
            }

            in Constants.HOME_APP_FLAGS -> {
                val location = flag - Constants.FLAG_SET_HOME_APP_1 + 1
                prefs.setAppName(location, appModel.appLabel)
                prefs.setAppPackage(location, appModel.appPackage)
                prefs.setAppUser(location, appModel.user.toString())
                prefs.setAppActivityClassName(location, appModel.activityClassName)
                refreshHome(false)
            }

            Constants.FLAG_SET_SWIPE_LEFT_APP -> {
                prefs.appNameSwipeLeft = appModel.appLabel
                prefs.appPackageSwipeLeft = appModel.appPackage
                prefs.appUserSwipeLeft = appModel.user.toString()
                prefs.appActivityClassNameSwipeLeft = appModel.activityClassName
                updateSwipeApps()
            }

            Constants.FLAG_SET_SWIPE_RIGHT_APP -> {
                prefs.appNameSwipeRight = appModel.appLabel
                prefs.appPackageSwipeRight = appModel.appPackage
                prefs.appUserSwipeRight = appModel.user.toString()
                prefs.appActivityClassNameRight = appModel.activityClassName
                updateSwipeApps()
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
    }

    fun forceLaunchApp(appModel: AppModel) {
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

    fun isOlauncherDefault() {
        isOlauncherDefault.value = isOlauncherDefault(appContext)
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
        if (prefs.screenTimeLastUpdated.hasBeenMinutes(1).not()) return

        val usageStatsManager = appContext.getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val appUsageStatsHashMap: MutableMap<String, AppUsageStats> = HashMap()
        val beginTime = System.currentTimeMillis().convertEpochToMidnight()
        val endTime = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(beginTime, endTime)
        val eventsMap: MutableMap<String, MutableList<UsageEvents.Event>> = HashMap()
        var currentEvent: UsageEvents.Event

        while (events.hasNextEvent()) {
            currentEvent = UsageEvents.Event()
            if (events.getNextEvent(currentEvent)) {
                when (currentEvent.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, UsageEvents.Event.FOREGROUND_SERVICE_START, UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                        var packageEvents = eventsMap[currentEvent.packageName]
                        if (packageEvents == null)
                            packageEvents = ArrayList(listOf(currentEvent))
                        else
                            packageEvents.add(currentEvent)
                        eventsMap[currentEvent.packageName] = packageEvents
                    }
                }
            }
        }

        for ((key, value) in eventsMap) {
            val foregroundBucket = AppUsageStatsBucket()
            val backgroundBucketMap: MutableMap<String, AppUsageStatsBucket?> = HashMap()
            var pos = 0
            while (pos < value.size) {
                val event = value[pos]
                if (event.className != null) {
                    var backgroundBucket: AppUsageStatsBucket? = backgroundBucketMap[event.className]
                    if (backgroundBucket == null) {
                        backgroundBucket = AppUsageStatsBucket()
                        backgroundBucketMap[event.className] = backgroundBucket
                    }
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> foregroundBucket.startMillis = event.timeStamp

                        UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> if (foregroundBucket.startMillis >= foregroundBucket.endMillis) {
                            if (foregroundBucket.startMillis == 0L) {
                                foregroundBucket.startMillis = beginTime
                            }
                            foregroundBucket.endMillis = event.timeStamp
                            foregroundBucket.addTotalTime()
                        }

                        UsageEvents.Event.FOREGROUND_SERVICE_START -> backgroundBucket.startMillis = event.timeStamp
                        UsageEvents.Event.FOREGROUND_SERVICE_STOP -> if (backgroundBucket.startMillis >= backgroundBucket.endMillis) {
                            if (backgroundBucket.startMillis == 0L) {
                                backgroundBucket.startMillis = beginTime
                            }
                            backgroundBucket.endMillis = event.timeStamp
                            backgroundBucket.addTotalTime()
                        }
                    }
                    if (pos == value.size - 1) {
                        if (foregroundBucket.startMillis > foregroundBucket.endMillis) {
                            foregroundBucket.endMillis = endTime
                            foregroundBucket.addTotalTime()
                        }
                        if (backgroundBucket.startMillis > backgroundBucket.endMillis) {
                            backgroundBucket.endMillis = endTime
                            backgroundBucket.addTotalTime()
                        }
                    }
                }
                pos++
            }

            val foregroundEnd: Long = foregroundBucket.endMillis
            val totalTimeForeground: Long = foregroundBucket.totalTime
            val backgroundEnd: Long = backgroundBucketMap.values
                .mapNotNull { it?.endMillis }
                .maxOrNull() ?: 0L

            val totalTimeBackground: Long = backgroundBucketMap.values
                .mapNotNull { it?.totalTime }
                .sum()

            appUsageStatsHashMap[key] = AppUsageStats(
                max(foregroundEnd, backgroundEnd),
                totalTimeForeground,
                backgroundEnd,
                totalTimeBackground
            )
        }

        val totalTimeInMillis = appUsageStatsHashMap.values.sumOf { it.totalTimeInForegroundMillis }
        val viewTimeSpent = appContext.formattedTimeSpent((totalTimeInMillis * 1.1).toLong())
        screenTimeValue.postValue(viewTimeSpent)
        prefs.screenTimeLastUpdated = endTime
    }
}