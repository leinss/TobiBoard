// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.clipboard_entry_key, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(getItem(position))
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount() = clipboardHistoryManager?.getHistorySize() ?: 0

    inner class ViewHolder(
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val contentView: TextView
        private val annotationView: TextView
        private val metaRow: LinearLayout
        private val timestampView: TextView
        private val useCountView: TextView

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            Settings.getValues().mColors.setBackground(view, ColorType.KEY_BACKGROUND)
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            contentView = view.findViewById<TextView>(R.id.clipboard_entry_content).apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            annotationView = view.findViewById(R.id.clipboard_entry_annotation)
            metaRow = view.findViewById(R.id.clipboard_entry_meta_row)
            timestampView = view.findViewById(R.id.clipboard_entry_timestamp)
            useCountView = view.findViewById(R.id.clipboard_entry_use_count)
            clipboardLayoutParams.setItemProperties(view)
            val colors = Settings.getValues().mColors
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
            val metaColor = colors.get(ColorType.KEY_TEXT)
            annotationView.setTextColor(metaColor)
            timestampView.setTextColor(metaColor)
            useCountView.setTextColor(metaColor)
        }

        fun setContent(entry: ClipboardHistoryEntry?) {
            itemView.tag = entry?.id
            contentView.text = entry?.text?.take(1000)
            pinnedIconView.visibility = if (entry?.isPinned == true) View.VISIBLE else View.GONE

            val annotation = entry?.annotation
            if (!annotation.isNullOrBlank()) {
                annotationView.text = annotation
                annotationView.visibility = View.VISIBLE
            } else {
                annotationView.visibility = View.GONE
            }

            if (entry != null) {
                metaRow.visibility = View.VISIBLE
                timestampView.text = DateUtils.getRelativeTimeSpanString(
                    entry.timeStamp,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                if (entry.useCount > 0) {
                    useCountView.text = itemView.context.getString(R.string.clipboard_used_count, entry.useCount)
                    useCountView.visibility = View.VISIBLE
                } else {
                    useCountView.visibility = View.GONE
                }
            } else {
                metaRow.visibility = View.GONE
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            val id = view.tag as? Long ?: return false
            val manager = clipboardHistoryManager ?: return false
            val entry = manager.getHistoryEntryContent(id) ?: return false
            showEntryContextMenu(view, id, entry.isPinned, manager)
            return true
        }

        private fun showEntryContextMenu(anchor: View, id: Long, isPinned: Boolean, manager: ClipboardHistoryManager) {
            val popup = PopupMenu(anchor.context, anchor)
            val pinLabel = if (isPinned)
                anchor.context.getString(R.string.clipboard_context_unpin)
            else
                anchor.context.getString(R.string.clipboard_context_pin)
            popup.menu.add(0, 1, 0, pinLabel)
            popup.menu.add(0, 2, 1, anchor.context.getString(R.string.clipboard_context_delete))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> manager.toggleClipPinned(id)
                    2 -> manager.deleteEntryById(id)
                }
                true
            }
            popup.show()
        }
    }
}
