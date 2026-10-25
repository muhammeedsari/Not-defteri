package app.notdefteri;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick Settings tile: starts a recording, also from the lock screen (the page then shows only the recorder). */
public class RecordTile extends TileService {
    @Override
    public void onClick() {
        Intent i = new Intent(this, MainActivity.class).setAction(MainActivity.RECORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
