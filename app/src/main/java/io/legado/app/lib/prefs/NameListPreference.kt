package io.legado.app.lib.prefs

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import io.legado.app.R
import io.legado.app.lib.theme.getPrimaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.utils.ColorUtils


class NameListPreference(context: Context, attrs: AttributeSet) : ListPreference(context, attrs) {

    private val isBottomBackground: Boolean

    init {
        layoutResource = R.layout.view_preference
        widgetLayoutResource = R.layout.item_fillet_text
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.Preference)
        isBottomBackground = typedArray.getBoolean(R.styleable.Preference_isBottomBackground, false)
        typedArray.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        val v = Preference.bindView<TextView>(
            context, holder, icon, title, summary, widgetLayoutResource,
            R.id.text_view, isBottomBackground = isBottomBackground
        )
        if (v is TextView) {
            v.typeface = context.uiTypeface()
            v.text = entry
            if (isBottomBackground) {
                // chip 背景是卡片色上叠半透明 tint，文字判定必须与卡片表面色同源，
                // 不按原始 bottomBackground 存值（亮度<0.5 的中灰会误判出白字）。
                val isLight =
                    ColorUtils.isColorLight(PreferenceItemStyle.itemSurfaceColor(context))
                val pTextColor = context.getPrimaryTextColor(isLight)
                v.setTextColor(pTextColor)
            }
        }
        super.onBindViewHolder(holder)
        PreferenceItemStyle.apply(this, holder)
    }

}
