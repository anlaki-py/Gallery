package org.fossify.gallery.ai

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.fossify.gallery.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsRowLayoutTest {
    @Test fun aiSettingsTextUsesTheSameStartInsetAndRowGeometryAsItsNeighbors() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val root = LayoutInflater.from(context).inflate(R.layout.activity_settings, null)
        val ai = root.findViewById<TextView>(R.id.settings_ai_search)
        val aiRow = root.findViewById<ViewGroup>(R.id.settings_ai_search_holder)
        val reference = root.findViewById<TextView>(R.id.settings_change_date_time_format)
        val referenceRow = root.findViewById<ViewGroup>(R.id.settings_change_date_time_format_holder)
        val width = View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        for (row in listOf(aiRow, referenceRow)) {
            row.measure(width, height)
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
        }
        assertSame(aiRow, ai.parent)
        assertTrue(aiRow.paddingStart > 0)
        assertEquals(referenceRow.paddingStart, aiRow.paddingStart)
        assertEquals(reference.left, ai.left)
        assertEquals(reference.top, ai.top)
        assertEquals(referenceRow.measuredHeight, aiRow.measuredHeight)
    }
}
