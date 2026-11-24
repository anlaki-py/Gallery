package org.fossify.gallery.dialogs

import android.app.Activity
import android.text.method.LinkMovementMethod
import org.fossify.commons.databinding.DialogMessageBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.gallery.R

/** Fork attribution shown in place of Commons' warning about modified apps. */
object ForkNoticeDialog {
    /** Shows a clickable author link. OK dismisses the notice without opening another app. */
    @JvmStatic
    fun show(activity: Activity) {
        val binding = DialogMessageBinding.inflate(activity.layoutInflater)
        binding.message.text = activity.getText(R.string.fork_notice_message)
        binding.message.movementMethod = LinkMovementMethod.getInstance()
        val builder = activity.getAlertDialogBuilder()
            .setPositiveButton(android.R.string.ok, null)
        activity.setupDialogStuff(binding.root, builder, titleId = R.string.fork_notice_title)
    }
}
