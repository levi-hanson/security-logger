package com.daxbot.readywidgets

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "Captain's log" quick-settings tile. Pull down from the top of the screen, even on the
 * lock screen, and tap it to start recording an entry ([RecordLogActivity] shows over the lock
 * screen, so there's no need to unlock first).
 */
class LogTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Captain's log"
            val waiting = CaptainsLog.pendingCount(this)
            tile.subtitle = if (waiting > 0) "$waiting waiting to send" else "Tap to record"
            tile.updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, RecordLogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
