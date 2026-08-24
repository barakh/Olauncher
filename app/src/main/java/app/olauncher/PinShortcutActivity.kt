package app.olauncher

import android.app.Activity
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.PinItemRequest
import android.os.Bundle
import app.olauncher.data.Prefs

class PinShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val pinItemRequest = launcherApps.getPinItemRequest(getIntent())

        if (pinItemRequest != null &&
            pinItemRequest.isValid() &&
            pinItemRequest.getRequestType() == PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            val shortcut = pinItemRequest.getShortcutInfo()
            if (shortcut != null) {
                val prefs = Prefs(this)

                val freeLocation = findFreeLocation(prefs)
                if (freeLocation != 0) {
                    prefs.setAppName(freeLocation, shortcut.getShortLabel().toString())
                    prefs.setAppPackage(freeLocation, shortcut.getPackage())
                    prefs.setAppUser(freeLocation, shortcut.getUserHandle().toString())
                    prefs.setAppActivityClassName(freeLocation, shortcut.getActivity()?.className)
                    prefs.pinLocation(freeLocation)
                    pinItemRequest.accept()
                } else {
                    pinItemRequest.accept(Bundle())
                }
            }
        }

        finish()
    }

    private fun findFreeLocation(prefs: Prefs): Int {
        for (i in 1..16) {
            if (prefs.getAppPackage(i).isEmpty()) return i
        }
        return 0
    }
}