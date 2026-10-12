package com.michaelunkai.zcoderemote;

import android.service.quicksettings.TileService;

/**
 * Quick Settings tile "ZCode": one swipe-and-tap from anywhere opens the live
 * session. Zero battery when unused — the fastest manual launch path.
 */
public class SessionTileService extends TileService {

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(() -> SessionLauncher.openSession(this));
        } else {
            SessionLauncher.openSession(this);
        }
    }
}
