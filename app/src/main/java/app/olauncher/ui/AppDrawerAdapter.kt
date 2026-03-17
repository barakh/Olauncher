package app.olauncher.ui

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.AdapterAppDrawerBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.getAppName
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.isSystemApp
import app.olauncher.helper.showKeyboard
import java.util.Locale

class AppDrawerAdapter(
    private val flag: Int,
    private val prefs: Prefs,
    private val appClickListener: (AppModel) -> Unit,
    private val appInfoListener: (AppModel) -> Unit,
    private val appDeleteListener: (AppModel) -> Unit,
    private val appHideListener: (AppModel, Int) -> Unit,
    private val appRenameListener: (AppModel, String) -> Unit,
    private val appAntiDoomListener: (AppModel) -> Unit,
    private val isAppHidden: ((AppModel) -> Boolean)? = null,
) : RecyclerView.Adapter<AppDrawerAdapter.ViewHolder>(), Filterable {

    var appsList: MutableList<AppModel> = mutableListOf()
    var appFilteredList: MutableList<AppModel> = mutableListOf()
    private var myUserHandle = android.os.Process.myUserHandle()

    fun setAppList(list: MutableList<AppModel>) {
        appsList = list
        appFilteredList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = AdapterAppDrawerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(
            flag,
            prefs,
            myUserHandle,
            appFilteredList[position],
            appClickListener,
            appDeleteListener,
            appInfoListener,
            appHideListener,
            appRenameListener,
            appAntiDoomListener,
            isAppHidden
        )
    }

    override fun getItemCount(): Int {
        return appFilteredList.size
    }

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val charSearch = constraint.toString()
                appFilteredList = if (charSearch.isEmpty()) {
                    appsList
                } else {
                    val resultList = mutableListOf<AppModel>()
                    for (row in appsList) {
                        if (row.appLabel.lowercase(Locale.ROOT).contains(charSearch.lowercase(Locale.ROOT))) {
                            resultList.add(row)
                        }
                    }
                    resultList
                }
                val filterResults = FilterResults()
                filterResults.values = appFilteredList
                return filterResults
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                appFilteredList = results?.values as MutableList<AppModel>
                notifyDataSetChanged()
            }
        }
    }

    fun launchFirstInList() {
        if (appFilteredList.size > 0)
            appClickListener(appFilteredList[0])
    }

    class ViewHolder(private val binding: AdapterAppDrawerBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(
            flag: Int,
            prefs: Prefs,
            myUserHandle: UserHandle,
            appModel: AppModel,
            clickListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appInfoListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appRenameListener: (AppModel, String) -> Unit,
            appAntiDoomListener: (AppModel) -> Unit,
            isAppHidden: ((AppModel) -> Boolean)? = null,
        ) = with(binding) {
            setupInitialVisibility()
            setupAppTitle(appModel, prefs)
            setupAppTitleColor(appModel, prefs)

            if (appModel.appPackage.isEmpty()) {
                appTitle.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
                return@with
            }

            setupAppIcons(flag, appModel, prefs, isAppHidden)
            otherProfileIndicator.isVisible = appModel.user != myUserHandle

            setupClickListeners(appModel, flag, clickListener)
            setupRenameLogic(appModel, appRenameListener)
            setupAppActions(appModel, appInfoListener, appDeleteListener, appHideListener, appAntiDoomListener, bindingAdapterPosition)
        }

        private fun AdapterAppDrawerBinding.setupInitialVisibility() {
            appHideLayout.visibility = View.GONE
            renameLayout.visibility = View.GONE
            appTitle.visibility = View.VISIBLE
        }

        private fun AdapterAppDrawerBinding.setupAppTitle(appModel: AppModel, prefs: Prefs) {
            val title = appModel.appLabel + if (appModel.isNew == true) " ✦" else ""
            appTitle.text = title
            appTitle.gravity = prefs.appLabelAlignment
        }

        private fun AdapterAppDrawerBinding.setupAppTitleColor(appModel: AppModel, prefs: Prefs) {
            if (appModel.appPackage.isNotEmpty()) {
                val user = appModel.user.toString()
                val isTemporarilyHidden = prefs.isAppTemporarilyHidden(appModel.appPackage, user)
                if (prefs.paintAntidoomedAppsRed && isTemporarilyHidden && prefs.isAntiDoomApp(appModel.appPackage, user)) {
                    val remainingMinutes = prefs.getAntiDoomRemainingMinutes(appModel.appPackage, user)
                    val color = if (remainingMinutes < 10) R.color.light_red else R.color.red
                    appTitle.setTextColor(root.context.getColor(color))
                } else {
                    val typedValue = TypedValue()
                    root.context.theme.resolveAttribute(R.attr.primaryColor, typedValue, true)
                    appTitle.setTextColor(typedValue.data)
                }
            }
        }

        private fun AdapterAppDrawerBinding.setupAppIcons(
            flag: Int,
            appModel: AppModel,
            prefs: Prefs,
            isAppHidden: ((AppModel) -> Boolean)?
        ) {
            if ((flag == Constants.FLAG_ANTIDOOM_APPS || flag == Constants.FLAG_QUARANTINED_APPS) && isAppHidden != null) {
                val isHidden = isAppHidden(appModel)
                val iconRes = if (isHidden) R.drawable.ic_hide else R.drawable.ic_show
                appTitle.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
                appTitle.compoundDrawablePadding = 24
            } else if (prefs.showAppIconsAppDrawer) {
                val launcherApps = root.context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
                val activityList = launcherApps.getActivityList(appModel.appPackage, appModel.user)
                if (activityList.isNotEmpty()) {
                    val icon = activityList[0].getIcon(0)
                    val iconSize = 24.dpToPx()
                    icon.setBounds(0, 0, iconSize, iconSize)
                    appTitle.setCompoundDrawables(icon, null, null, null)
                    appTitle.compoundDrawablePadding = 24
                } else {
                    appTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                }
            } else {
                appTitle.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            }
        }

        private fun AdapterAppDrawerBinding.setupClickListeners(
            appModel: AppModel,
            flag: Int,
            clickListener: (AppModel) -> Unit
        ) {
            appTitle.setOnClickListener { clickListener(appModel) }
            appTitle.setOnLongClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    appDelete.alpha = if (root.context.isSystemApp(appModel.appPackage)) 0.5f else 1.0f
                    appHide.text = if (flag == Constants.FLAG_HIDDEN_APPS)
                        root.context.getString(R.string.adapter_show)
                    else
                        root.context.getString(R.string.adapter_hide)
                    appTitle.visibility = View.INVISIBLE
                    appHideLayout.visibility = View.VISIBLE
                    appRename.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                    appAntiDoom.isVisible = flag != Constants.FLAG_HIDDEN_APPS
                }
                true
            }
        }

        private fun AdapterAppDrawerBinding.setupRenameLogic(
            appModel: AppModel,
            appRenameListener: (AppModel, String) -> Unit
        ) {
            appRename.setOnClickListener {
                if (appModel.appPackage.isNotEmpty()) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage)
                    etAppRename.setText(appModel.appLabel)
                    etAppRename.setSelectAllOnFocus(true)
                    renameLayout.visibility = View.VISIBLE
                    appHideLayout.visibility = View.GONE
                    etAppRename.showKeyboard()
                    etAppRename.imeOptions = EditorInfo.IME_ACTION_DONE
                }
            }
            etAppRename.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                appTitle.visibility = if (hasFocus) View.INVISIBLE else View.VISIBLE
            }
            setupRenameTextWatcher(appModel)
            setupRenameActions(appModel, appRenameListener)
        }

        private fun AdapterAppDrawerBinding.setupRenameTextWatcher(appModel: AppModel) {
            etAppRename.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    etAppRename.hint = getAppName(etAppRename.context, appModel.appPackage)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    etAppRename.hint = ""
                }
            })
        }

        private fun AdapterAppDrawerBinding.setupRenameActions(
            appModel: AppModel,
            appRenameListener: (AppModel, String) -> Unit
        ) {
            etAppRename.setOnEditorActionListener { _, actionCode, _ ->
                if (actionCode == EditorInfo.IME_ACTION_DONE) {
                    val renameLabel = etAppRename.text.toString().trim()
                    if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                        appRenameListener(appModel, renameLabel)
                        renameLayout.visibility = View.GONE
                    }
                    true
                } else false
            }
            tvSaveRename.setOnClickListener {
                etAppRename.hideKeyboard()
                val renameLabel = etAppRename.text.toString().trim()
                if (renameLabel.isNotBlank() && appModel.appPackage.isNotBlank()) {
                    appRenameListener(appModel, renameLabel)
                    renameLayout.visibility = View.GONE
                } else {
                    val packageManager = etAppRename.context.packageManager
                    appRenameListener(
                        appModel,
                        packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(appModel.appPackage, 0)
                        ).toString()
                    )
                    renameLayout.visibility = View.GONE
                }
            }
        }

        private fun AdapterAppDrawerBinding.setupAppActions(
            appModel: AppModel,
            appInfoListener: (AppModel) -> Unit,
            appDeleteListener: (AppModel) -> Unit,
            appHideListener: (AppModel, Int) -> Unit,
            appAntiDoomListener: (AppModel) -> Unit,
            position: Int
        ) {
            appInfo.setOnClickListener { appInfoListener(appModel) }
            appDelete.setOnClickListener { appDeleteListener(appModel) }
            appHide.setOnClickListener { appHideListener(appModel, position) }
            appAntiDoom.setOnClickListener {
                appAntiDoomListener(appModel)
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appMenuClose.setOnClickListener {
                appHideLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
            }
            appRenameClose.setOnClickListener {
                renameLayout.visibility = View.GONE
                appTitle.visibility = View.VISIBLE
                etAppRename.hideKeyboard()
            }
        }
    }
}
