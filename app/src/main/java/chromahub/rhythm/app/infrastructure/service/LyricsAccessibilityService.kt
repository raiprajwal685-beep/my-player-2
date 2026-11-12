/*
 * Personal addition: an accessibility service whose only job is to let the floating lyrics window
 * use the accessibility overlay layer, which draws above system screens (Settings, the
 * notification panel) and can extend into the display cutout (notch).
 * It listens to no events and does not read the screen. Licensed GPL-3.0-or-later.
 */

package chromahub.rhythm.app.infrastructure.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class LyricsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty: this service never inspects the screen.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: LyricsAccessibilityService? = null
    }
}
