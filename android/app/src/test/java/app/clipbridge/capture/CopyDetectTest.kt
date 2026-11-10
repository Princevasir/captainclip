package app.clipbridge.capture

import android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
import android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyDetectTest {
    @Test fun matchesCopyLabels() {
        assertTrue(CopyDetect.matches("Copy"))
        assertTrue(CopyDetect.matches("  copy link "))
        assertTrue(CopyDetect.matches("Copy text"))
        assertTrue(CopyDetect.matches("कॉपी करें"))
        assertTrue(CopyDetect.matches("Copy image"))
    }

    @Test fun matchesCopiedToasts() {
        assertTrue(CopyDetect.matches("Copied to clipboard"))
        assertTrue(CopyDetect.matches("Link copied"))
        assertTrue(CopyDetect.matches("कॉपी किया गया"))
    }

    @Test fun ignoresLookalikes() {
        assertFalse(CopyDetect.matches("© 2026 Copyright Inc"))
        assertFalse(CopyDetect.matches("Copyright"))
        assertFalse(CopyDetect.matches("Paste"))
        assertFalse(CopyDetect.matches(""))
        assertFalse(CopyDetect.matches("How to copy files between your laptop and your phone quickly"))
    }

    @Test fun clickOnCopyButtonTriggers() {
        assertTrue(CopyDetect.isCopyTrigger(TYPE_VIEW_CLICKED, "android.widget.Button", "com.whatsapp", listOf("Copy"), null))
        assertTrue(CopyDetect.isCopyTrigger(TYPE_VIEW_CLICKED, "android.widget.ImageButton", "com.android.chrome", emptyList(), "Copy link"))
    }

    @Test fun copiedToastTriggers() {
        assertTrue(CopyDetect.isCopyTrigger(TYPE_NOTIFICATION_STATE_CHANGED, "android.widget.Toast\$TN", "com.google.android.gm", listOf("Copied to clipboard"), null))
    }

    @Test fun systemClipboardOverlayTriggers() {
        // Android 13+ shows a system "Copied" overlay after any copy.
        assertTrue(CopyDetect.isCopyTrigger(TYPE_WINDOW_STATE_CHANGED, "android.widget.FrameLayout", "com.android.systemui", listOf("Copied"), null))
    }

    @Test fun unrelatedEventsDoNotTrigger() {
        assertFalse(CopyDetect.isCopyTrigger(TYPE_VIEW_CLICKED, "android.widget.Button", "com.whatsapp", listOf("Send"), null))
        assertFalse(CopyDetect.isCopyTrigger(TYPE_VIEW_FOCUSED, "android.widget.Button", "com.whatsapp", listOf("Copy"), null))
        assertFalse(CopyDetect.isCopyTrigger(TYPE_WINDOW_STATE_CHANGED, "android.widget.FrameLayout", "com.example.notes", listOf("Copied"), null))
    }

    @Test fun ownAppNeverTriggers() {
        assertFalse(CopyDetect.isCopyTrigger(TYPE_VIEW_CLICKED, "android.widget.Button", "app.clipbridge", listOf("Copy"), null))
    }
}
