package com.posiciontelefono;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings button. Finishing works from anywhere. Starting opens the app, which starts the
 * recording by itself: Android only gives GPS access to a service started while the app is visible.
 */
public final class RecordTileService extends TileService {

    @Override
    public void onStartListening() {
        update(RecorderService.recording);
    }

    @Override
    public void onClick() {
        if (RecorderService.recording) {
            RecorderService.stop(this);
            update(false);
        } else {
            openAppAndStart();
        }
    }

    // The Intent overload is the only one before Android 14 and is never called on 14+.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @SuppressWarnings("deprecation")
    private void openAppAndStart() {
        Intent intent = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_START, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    private void update(boolean recording) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(recording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(recording ? R.string.tile_recording : R.string.tile_idle));
        tile.updateTile();
    }
}
