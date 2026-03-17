package app.olauncher.ui

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.core.widget.doAfterTextChanged
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.AntiDoomBlockedInfo
import app.olauncher.data.Constants
import app.olauncher.data.DailyReminder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getColorFromAttr
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openCameraApp
import app.olauncher.helper.openDialerApp
import app.olauncher.helper.openSearch
import app.olauncher.helper.setPlainWallpaperByTheme
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import app.olauncher.helper.hideKeyboard
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val homeAppViews
        get() = listOf(
            binding.homeApp1, binding.homeApp2, binding.homeApp3, binding.homeApp4,
            binding.homeApp5, binding.homeApp6, binding.homeApp7, binding.homeApp8,
            binding.homeApp9, binding.homeApp10, binding.homeApp11, binding.homeApp12,
            binding.homeApp13, binding.homeApp14, binding.homeApp15, binding.homeApp16
        )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initPermanentNote()
        initDailyReminder()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.autoOrderApps) viewModel.getAutoOrderedApps()
        else populateHomeScreen(false)
        viewModel.isOlauncherDefault()
        viewModel.updateQuarantineCount()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            R.id.quarantineLayout -> showQuarantinedApps()

            in homeAppViews.map { it.id } -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else {
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
        }
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else {
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
        }
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            in homeAppViews.map { it.id } -> {
                val location = view.tag.toString().toInt()
                val packageName = prefs.getAppPackage(location)
                val userString = prefs.getAppUser(location)
                val isPinned = prefs.isLocationPinned(location)
                val flag = Constants.FLAG_SET_HOME_APP_1 + location - 1

                val options = mutableListOf<String>()
                options.add(getString(R.string.select_app))
                if (packageName.isNotEmpty()) {
                    options.add(if (isPinned) getString(R.string.unpin_app) else getString(R.string.pin_app))
                    options.add(getString(R.string.rename))
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setItems(options.toTypedArray()) { _, which ->
                        when (options[which]) {
                            getString(R.string.select_app) -> {
                                showAppList(flag, false, true)
                            }
                            getString(R.string.pin_app) -> {
                                prefs.pinLocation(location)
                                viewModel.getAutoOrderedApps()
                            }
                            getString(R.string.unpin_app) -> {
                                prefs.unpinLocation(location)
                                viewModel.getAutoOrderedApps()
                            }
                            getString(R.string.rename) -> {
                                showAppList(flag, true, true)
                            }
                        }
                    }
                    .show()
            }
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                if (prefs.dailyWallpaper) {
                    prefs.dailyWallpaper = false
                    viewModel.cancelWallpaperWorker()
                }
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.visibility == View.VISIBLE) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
//            if (it) binding.setDefaultLauncher.visibility = View.GONE
//            else binding.setDefaultLauncher.visibility = View.VISIBLE
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let {
                binding.tvScreenTime.text = it
                binding.tvScreenTime.visibility = View.VISIBLE
            }
        }
        viewModel.quarantineCount.observe(viewLifecycleOwner) { count ->
            if (count > 0) {
                binding.quarantineLayout?.visibility = View.VISIBLE
                binding.tvQuarantineCount?.text = count.toString()
            } else {
                binding.quarantineLayout?.visibility = View.GONE
            }
        }
        viewModel.showAntiDoomDialog.observe(viewLifecycleOwner) { info ->
            info?.let { showAntiDoomBlockedDialog(it) }
        }
        viewModel.clearPermanentNoteFocus.observe(viewLifecycleOwner) {
            // no-op
        }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))

        val defaultPaddingH = 5.dpToPx()
        val defaultPaddingV = 5.dpToPx()
        homeAppViews.forEach {
            it.setPadding(defaultPaddingH, defaultPaddingV, defaultPaddingH, defaultPaddingV)
            it.setOnTouchListener(getViewSwipeTouchListener(context, it))
        }
    }


    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.quarantineLayout.setOnClickListener(this)
    }

    private fun showQuarantinedApps() {
        viewModel.getQuarantinedApps()
        findNavController().navigate(
            R.id.action_mainFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_QUARANTINED_APPS)
        )
    }

    private fun initPermanentNote() {
        binding.btnAddQuickReminder.setOnClickListener {
            showAddQuickReminderDialog()
        }
    }

    private fun showAddQuickReminderDialog() {
        val editText = androidx.appcompat.widget.AppCompatEditText(requireContext())
        editText.hint = getString(R.string.reminder_text_placeholder)
        editText.isSingleLine = true
        val padding = 16.dpToPx()
        val container = FrameLayout(requireContext())
        container.setPadding(padding, padding / 2, padding, 0)
        container.addView(editText)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_reminder)
            .setView(container)
            .setPositiveButton(R.string.okay) { _, _ ->
                val text = editText.text.toString()
                if (text.isNotBlank()) {
                    val list = prefs.quickReminders.toMutableList()
                    list.add(text)
                    prefs.quickReminders = list
                    populatePermanentNote()
                }
            }
            .setNegativeButton(R.string.not_now, null)
            .create()
        
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        editText.requestFocus()
    }

    private fun populatePermanentNote() {
        binding.permanentNoteContainer.isVisible = prefs.showPermanentNote
        if (prefs.showPermanentNote) {
            binding.quickRemindersContainer.removeAllViews()
            prefs.quickReminders.forEach { reminderText ->
                val chip = createQuickReminderChip(reminderText)
                binding.quickRemindersContainer.addView(chip)
            }
            binding.quickRemindersContainer.addView(binding.btnAddQuickReminder)
        }
    }

    private fun createQuickReminderChip(text: String): TextView {
        val textView = TextView(requireContext())
        textView.text = text
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_small))
        textView.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
        textView.setBackgroundResource(R.drawable.rounded_rect_shade_color)

        val paddingH = 16.dpToPx()
        val paddingV = 8.dpToPx()
        textView.setPadding(paddingH, paddingV, paddingH, paddingV)

        textView.setOnClickListener {
            markQuickReminderAsCompleted(textView, text)
        }

        return textView
    }

    private fun markQuickReminderAsCompleted(view: TextView, text: String) {
        val list = prefs.quickReminders.toMutableList()
        list.remove(text)
        prefs.quickReminders = list
        binding.quickRemindersContainer.removeView(view)
        showLightningEffect()
    }

    private fun initDailyReminder() {
        // No-op for now, we'll populate dynamically
    }

    private fun populateDailyReminder() {
        binding.dailyRemindersContainer.removeAllViews()
        if (!prefs.showDailyReminder) return

        val currentDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

        prefs.dailyReminders.forEach { reminder ->
            if (reminder.text.isNotBlank() && reminder.lastCompletedDay != currentDay && currentTime >= reminder.time) {
                val ticker = createReminderTicker(reminder)
                binding.dailyRemindersContainer.addView(ticker)
            }
        }
    }

    private fun createReminderTicker(reminder: DailyReminder): TextView {
        val textView = TextView(requireContext())
        textView.text = reminder.text
        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_small))
        textView.setTextColor(requireContext().getColorFromAttr(R.attr.primaryColor))
        textView.setBackgroundResource(R.drawable.rounded_rect_shade_color)
        
        val paddingH = 16.dpToPx()
        val paddingV = 8.dpToPx()
        textView.setPadding(paddingH, paddingV, paddingH, paddingV)
        
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 0, 0, 8.dpToPx())
        textView.layoutParams = params
        
        textView.setOnClickListener {
            markReminderAsCompleted(textView, reminder)
        }
        
        return textView
    }

    private fun markReminderAsCompleted(view: TextView, reminder: DailyReminder) {
        val list = prefs.dailyReminders.toMutableList()
        val index = list.indexOfFirst { it.id == reminder.id }
        if (index != -1) {
            list[index].lastCompletedDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            prefs.dailyReminders = list
        }
        binding.dailyRemindersContainer.removeView(view)
        showLightningEffect()
    }

    private fun showLightningEffect() {
        val root = binding.confettiContainer
        val screenWidth = root.width
        val screenHeight = root.height
        if (screenWidth == 0 || screenHeight == 0) return

        val lightningColor = Color.WHITE

        // Trigger 5 bolts with slight delays
        for (j in 0 until 5) {
            val startDelay = j * 100L
            
            // Flash animation for each bolt
            val flashView = View(requireContext())
            flashView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            flashView.setBackgroundColor(lightningColor)
            flashView.alpha = 0f
            root.addView(flashView)

            flashView.animate()
                .alpha(0.2f)
                .setDuration(40)
                .setStartDelay(startDelay)
                .withEndAction {
                    flashView.animate()
                        .alpha(0f)
                        .setDuration(40)
                        .withEndAction { root.removeView(flashView) }
                        .start()
                }
                .start()

            // Lightning bolt segments
            val startX = (0..screenWidth).random().toFloat()
            var currentX = startX
            var currentY = 0f
            val segments = 8
            val segmentHeight = screenHeight / segments

            for (i in 0 until segments) {
                val nextX = currentX + ((-50..50).random() * resources.displayMetrics.density)
                val nextY = currentY + segmentHeight

                val segment = View(requireContext())
                val angle = Math.atan2((nextY - currentY).toDouble(), (nextX - currentX).toDouble())
                val distance = Math.sqrt(Math.pow((nextX - currentX).toDouble(), 2.0) + Math.pow((nextY - currentY).toDouble(), 2.0))
                
                segment.layoutParams = FrameLayout.LayoutParams(distance.toInt(), (3 * resources.displayMetrics.density).toInt())
                segment.setBackgroundColor(lightningColor)
                segment.pivotX = 0f
                segment.pivotY = (1.5f * resources.displayMetrics.density)
                segment.x = currentX
                segment.y = currentY
                segment.rotation = Math.toDegrees(angle).toFloat()
                segment.alpha = 0f
                root.addView(segment)

                segment.animate()
                    .alpha(1f)
                    .setDuration(60)
                    .setStartDelay(startDelay)
                    .withEndAction {
                        segment.animate()
                            .alpha(0f)
                            .setDuration(100)
                            .withEndAction { root.removeView(segment) }
                            .start()
                    }
                    .start()

                currentX = nextX
                currentY = nextY
            }
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
//        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        homeAppViews.forEach { it.gravity = horizontalGravity }
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()
        populatePermanentNote()
        populateDailyReminder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        binding.homeAppsLayout.columnCount = if (prefs.showAppIconsHome) 4 else 2

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        for (i in 1..homeAppsNum) {
            val view = homeAppViews[i - 1]
            view.visibility = View.VISIBLE
            if (!setHomeAppText(view, prefs.getAppName(i), prefs.getAppPackage(i), prefs.getAppUser(i))) {
                prefs.setAppName(i, "")
                prefs.setAppPackage(i, "")
            }
        }
    }

    private fun setHomeAppText(textView: TextView, appName: String, packageName: String, userString: String): Boolean {
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            val isTemporarilyHidden = prefs.isAppTemporarilyHidden(packageName, userString)

            if (prefs.showAppIconsHome) {
                val userHandle = getUserHandleFromString(requireContext(), userString)
                val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val activityList = launcherApps.getActivityList(packageName, userHandle)
                if (activityList.isNotEmpty()) {
                    val icon = activityList[0].getIcon(0)
                    val iconSize = 48.dpToPx()
                    icon.setBounds(0, 0, iconSize, iconSize)
                    textView.setCompoundDrawables(null, icon, null, null)
                }
                textView.text = if (appName.length > 8) appName.substring(0, 8) else appName
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_small))
                textView.gravity = Gravity.CENTER
            } else {
                textView.setCompoundDrawables(null, null, null, null)
                textView.text = if (appName.length > 15) appName.substring(0, 15) else appName
                textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_large))
                textView.gravity = prefs.homeAlignment
            }

            textView.visibility = View.VISIBLE

            if (prefs.paintAntidoomedAppsRed && isTemporarilyHidden && prefs.isAntiDoomApp(packageName, userString)) {
                val remainingMinutes = prefs.getAntiDoomRemainingMinutes(packageName, userString)
                val color = if (remainingMinutes < 10) R.color.light_red else R.color.red
                textView.setTextColor(requireContext().getColor(color))
            } else {
                val typedValue = TypedValue()
                requireContext().theme.resolveAttribute(R.attr.primaryColor, typedValue, true)
                textView.setTextColor(typedValue.data)
            }
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        homeAppViews.forEach { it.visibility = View.GONE }
    }

    private fun homeAppClicked(location: Int) {
        if (prefs.getAppName(location).isEmpty()) showLongPressToast()
        else {
            launchApp(
                prefs.getAppName(location),
                prefs.getAppPackage(location),
                prefs.getAppActivityClassName(location),
                prefs.getAppUser(location)
            )
        }
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel(
                appName,
                null,
                packageName,
                activityClassName,
                false,
                getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.SEARCH -> openSearch(requireContext())
            else -> expandNotificationDrawer(requireContext())
        }
    }

    private fun openSwipeRightApp() {
        if (!prefs.swipeRightEnabled) return
        if (prefs.appPackageSwipeRight.isNotEmpty()) {
            launchApp(
                prefs.appNameSwipeRight,
                prefs.appPackageSwipeRight,
                prefs.appActivityClassNameRight,
                prefs.appUserSwipeRight
            )
        } else openDialerApp(requireContext())
    }

    private fun openSwipeLeftApp() {
        if (!prefs.swipeLeftEnabled) return
        if (prefs.appPackageSwipeLeft.isNotEmpty()) {
            launchApp(
                prefs.appNameSwipeLeft,
                prefs.appPackageSwipeLeft,
                prefs.appActivityClassNameSwipeLeft,
                prefs.appUserSwipeLeft
            )
        } else openCameraApp(requireContext())
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) {
            setPlainWallpaperByTheme(requireContext(), changedAppTheme)
            viewModel.setWallpaperWorker()
        }
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun showAntiDoomBlockedDialog(info: AntiDoomBlockedInfo) {
        val view = layoutInflater.inflate(R.layout.dialog_antidoom, null)
        view.findViewById<TextView>(R.id.dialogMessage).text = getString(R.string.antidoom_blocked_message, info.remainingMinutes)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnOpenAnyway).setOnClickListener {
            viewModel.forceLaunchApp(info.appModel)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else if (prefs.lockModeOn)
                    lockPhone()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
                // binding.etPermanentNote.clearFocus()
                // binding.etPermanentNote.hideKeyboard()
            }
        }
    }

    private fun getViewSwipeTouchListener(context: Context, view: View): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openSwipeLeftApp()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openSwipeRightApp()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                swipeDownAction()
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                textOnLongClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                textOnClick(view)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
