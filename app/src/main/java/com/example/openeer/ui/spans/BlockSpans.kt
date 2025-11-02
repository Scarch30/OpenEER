package com.example.openeer.ui.spans

import android.content.Context
import android.graphics.Color
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView

object BlockSpans {
    val TOKEN_REGEX: Regex = Regex("""\\[\\[media:block:(\\d+)]]""")

    fun spanifyMediaTokens(
        context: Context,
        textView: TextView,
        onMediaLinkClicked: (Long) -> Unit,
    ): Int {
        val original = textView.text ?: return 0
        val spannable = SpannableStringBuilder.valueOf(original)
        val matches = TOKEN_REGEX.findAll(spannable).toList()
        if (matches.isEmpty()) {
            textView.movementMethod = LinkMovementMethod.getInstance()
            textView.highlightColor = Color.TRANSPARENT
            textView.text = spannable
            return 0
        }

        var count = 0
        for (match in matches.asReversed()) {
            val (idGroup) = match.destructured
            val blockId = idGroup.toLongOrNull() ?: continue
            val span = MediaThumbnailSpan(context, textView, blockId)
            val clickable = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onMediaLinkClicked(blockId)
                }

                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false
                    ds.color = textView.textColors.defaultColor
                }
            }

            val start = match.range.first
            val end = match.range.last + 1
            spannable.setSpan(
                span,
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            spannable.setSpan(
                clickable,
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            count++
        }

        textView.movementMethod = LinkMovementMethod.getInstance()
        textView.highlightColor = Color.TRANSPARENT
        textView.text = spannable
        return count
    }
}

fun TextView.applyMediaSpans(onMediaLinkClicked: (Long) -> Unit) {
    BlockSpans.spanifyMediaTokens(context, this, onMediaLinkClicked)
}
