package org.fossify.gallery.dialogs

import android.app.Application
import android.content.Intent
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fossify.gallery.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForkNoticeDialogTest {
    private lateinit var controller: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity

    @Before fun setUp() {
        controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        activity = controller.get()
        activity.setTheme(R.style.AppTheme)
        controller.setup()
    }

    @After fun tearDown() {
        ShadowDialog.getLatestDialog()?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun okDismissesWithoutOpeningAStoreOrBrowser() {
        ForkNoticeDialog.show(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(org.fossify.commons.R.id.message)!!
        assertEquals("You are using a fork of Fossify Gallery made by anlaki.", message.text.toString())
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(android.R.string.ok), dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertFalse(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.isFinishing)
    }

    @Test fun authorLinkOpensTheGitHubProfileOnlyWhenClicked() {
        ForkNoticeDialog.show(activity)
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val message = dialog.findViewById<TextView>(org.fossify.commons.R.id.message)!!
        assertTrue(message.movementMethod is LinkMovementMethod)
        val text = message.text as Spanned
        val links = text.getSpans(0, text.length, URLSpan::class.java)
        assertEquals(1, links.size)
        assertEquals("https://github.com/anlaki-py", links.single().url)
        assertNull(shadowOf(activity).nextStartedActivity)
        links.single().onClick(message)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/anlaki-py", intent.dataString)
    }
}
