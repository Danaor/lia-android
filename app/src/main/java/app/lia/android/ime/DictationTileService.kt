package app.lia.android.ime

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.lia.android.R

/**
 * A Quick Settings tile that starts a dictation.
 *
 * This is the "one more button" that does not touch anything you already have:
 * your keyboard's own microphone key keeps working exactly as it does today,
 * and this is a separate way in. Pull the shade down, tap Lia, speak; the
 * session ends itself when you stop talking and the text goes into the field
 * you were in.
 *
 * Add it once from the pencil at the bottom of the Quick Settings panel.
 */
class DictationTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        render()
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        // Going through an activity is not decoration: a service started
        // straight from here is a background start, and Android 14+ then
        // refuses it the microphone. See TileLaunchActivity.
        val intent = Intent(this, TileLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val listening = DictationService.isActive.value
        tile.state = if (listening) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Lia"
        tile.icon = Icon.createWithResource(packageName, R.drawable.ic_stat_lia)
        // Without an explicit subtitle the panel falls back to the raw state
        // name, and an untouched tile reads "Unavailable", which looks broken.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (listening) "Listening" else "Dictate"
        }
        tile.contentDescription =
            if (listening) "Lia is listening" else "Dictate with Lia"
        tile.updateTile()
    }

    companion object {
        /** Ask the system to re-render the tile after the session state moves. */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, DictationTileService::class.java),
                )
            }
        }
    }
}
