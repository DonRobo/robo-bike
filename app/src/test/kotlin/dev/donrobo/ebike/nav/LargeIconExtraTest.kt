package dev.donrobo.ebike.nav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The large-icon fallback must also work below API 33 (minSdk 31), where `getParcelable(key, Class)` is missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 35])
@Suppress("DEPRECATION") // EXTRA_LARGE_ICON, as in largeIconExtra
class LargeIconExtraTest {
    private val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    @Test fun bitmapExtra() {
        val ex = Bundle().apply { putParcelable(Notification.EXTRA_LARGE_ICON, bitmap) }
        assertSame(bitmap, largeIconExtra(ex))
    }

    @Test fun iconExtra() {
        val icon = Icon.createWithBitmap(bitmap)
        val ex = Bundle().apply { putParcelable(Notification.EXTRA_LARGE_ICON, icon) }
        assertSame(icon, largeIconExtra(ex))
    }

    @Test fun noExtra() = assertNull(largeIconExtra(Bundle()))
}
