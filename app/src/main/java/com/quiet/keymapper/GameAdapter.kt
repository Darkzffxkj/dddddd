package com.quiet.keymapper

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Compact game grid similar to the supplied reference, with a live Add tile. */
class GameAdapter(
    private val context: Context,
    private val onTap: (InstalledApp) -> Unit,
    private val onAdd: () -> Unit,
    private val onRemove: (InstalledApp) -> Unit
) : RecyclerView.Adapter<GameAdapter.Holder>() {
    private val apps = mutableListOf<InstalledApp>()
    private val icons = android.util.LruCache<String, Drawable.ConstantState>(48)
    fun submit(next: List<InstalledApp>) {
        apps.clear()
        apps.addAll(next)
        notifyDataSetChanged()
    }
    override fun getItemCount(): Int = apps.size + 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val tile = context.column().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(5), context.dp(8), context.dp(5), context.dp(6))
            background=GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF362647.toInt(),0xFF21162F.toInt(),0xFF14101D.toInt())
            ).apply {
                cornerRadius=context.dp(11).toFloat()
                setStroke(context.dp(1),0xFF6A5286.toInt())
            }
            isClickable = true
            isFocusable = true
        }
        val iconFrame = android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(50), context.dp(50))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius=context.dp(12).toFloat()
                setColor(Palette.panelAlt)
                setStroke(context.dp(1), Palette.line)
            }
            clipToOutline = true
        }
        val icon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        iconFrame.addView(icon)
        val plus = context.txt("+", 30f, Palette.violetSoft).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        iconFrame.addView(plus, android.widget.FrameLayout.LayoutParams(-1, -1))
        val title = context.txt("", 11f, Palette.white).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tile.addView(iconFrame)
        tile.addView(title, LinearLayout.LayoutParams(-1, context.dp(29)).apply { topMargin = context.dp(5) })
        return Holder(tile, icon, plus, title, iconFrame)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (position == apps.size) {
            holder.title.text = "Adicionar"
            holder.icon.visibility = View.GONE
            holder.plus.visibility = View.VISIBLE
            holder.itemView.setOnClickListener { onAdd() }
            holder.itemView.setOnLongClickListener { false }
            return
        }
        val app = apps[position]
        holder.title.text = app.label
        holder.icon.visibility = View.VISIBLE
        holder.plus.visibility = View.GONE
        val drawable = try {
            val saved = icons.get(app.packageName)
            if (saved != null) saved.newDrawable(context.resources) else {
                context.packageManager.getApplicationIcon(app.packageName).also { value ->
                    value.constantState?.let { icons.put(app.packageName, it) }
                }
            }
        } catch (_: Exception) { context.packageManager.defaultActivityIcon }
        holder.icon.setImageDrawable(drawable)
        holder.itemView.setOnClickListener { onTap(app) }
        holder.itemView.setOnLongClickListener {
            onRemove(app)
            true
        }
    }

    class Holder(
        item: View, val icon: ImageView, val plus: TextView, val title: TextView,
        val iconFrame: android.widget.FrameLayout
    ) : RecyclerView.ViewHolder(item)
}
