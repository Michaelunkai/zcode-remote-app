package com.michaelunkai.zcoderemote;

import android.content.Context;
import android.util.AttributeSet;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * SwipeRefreshLayout that refuses to fire while the page — or any scrollable
 * container inside it (chat message lists, log panes) — is scrolled away from
 * its top. Without the inner-container gate, scrolling up inside a session
 * triggered a full page refresh and lost the user's place.
 */
public class SwipeRefreshGate extends SwipeRefreshLayout {

    /** Returns true when the content must not be pulled-to-refresh. */
    public interface Gate {
        boolean isContentScrolledUp();
    }

    private Gate gate;

    public SwipeRefreshGate(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setGate(Gate gate) {
        this.gate = gate;
    }

    @Override
    public boolean canChildScrollUp() {
        if (gate != null && gate.isContentScrolledUp()) {
            return true;
        }
        return super.canChildScrollUp();
    }
}
