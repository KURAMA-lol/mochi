package com.sahil.chibi

import android.app.PendingIntent
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pull down the shade, tap "Call Nana". */
class SummonTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let {
            it.state = if (CompanionService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.label = "Call Nana"
            it.updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, SummonActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}

/** Invisible trampoline: launched from the home-screen icon or the tile, summons her, closes itself. */
class SummonActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!android.provider.Settings.canDrawOverlays(this)) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            CompanionService.send(this, CompanionService.ACT_SUMMON)
        }
        finish()
    }
}
