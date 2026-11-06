package app.rawline.mobile.ui.json

import android.content.Context
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import app.rawline.mobile.R

/** Editor colours: the app theme for the chrome, the desktop JsonCodeEdit palette for tokens (JsonCodeEdit.cpp:25-31). */
object JsonColors {

    const val KEY = EditorColorScheme.ATTRIBUTE_NAME
    const val STRING = EditorColorScheme.ATTRIBUTE_VALUE
    const val NUMBER = EditorColorScheme.LITERAL
    const val LITERAL = EditorColorScheme.KEYWORD
    const val COMMENT = EditorColorScheme.COMMENT

    const val ERROR = 0xffc62828.toInt()
    const val WARNING = 0xfff08c00.toInt()

    fun accent(context: Context) = MaterialColors.getColor(context, R.attr.colorAccent, 0xff536dfe.toInt())

    fun scheme(context: Context, dark: Boolean): EditorColorScheme {
        val background = MaterialColors.getColor(
            context, android.R.attr.colorBackground, if (dark) 0xff121212.toInt() else 0xffffffff.toInt()
        )
        return Scheme(dark).apply {
            paint(
                background = background,
                surface = MaterialColors.getColor(context, R.attr.colorSurface, background),
                text = MaterialColors.getColor(
                    context, android.R.attr.textColorPrimary, if (dark) 0xffeeeeee.toInt() else 0xff212121.toInt()
                ),
                secondary = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0xff808080.toInt()),
                accent = accent(context),
            )
        }
    }

    private class Scheme(dark: Boolean) : EditorColorScheme(dark) {

        fun paint(background: Int, surface: Int, text: Int, secondary: Int, accent: Int) {
            fun alpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha)
            setColor(WHOLE_BACKGROUND, background)
            setColor(LINE_NUMBER_BACKGROUND, background)
            setColor(TEXT_NORMAL, text)
            setColor(OPERATOR, text)
            setColor(LINE_NUMBER, alpha(secondary, 0x99))
            setColor(LINE_NUMBER_CURRENT, text)
            setColor(LINE_DIVIDER, alpha(secondary, 0x33))
            setColor(CURRENT_LINE, alpha(text, 0x0f))
            setColor(SELECTION_INSERT, accent)
            setColor(SELECTION_HANDLE, accent)
            setColor(SELECTED_TEXT_BACKGROUND, alpha(accent, 0x55))
            setColor(MATCHED_TEXT_BACKGROUND, alpha(accent, 0x44))
            setColor(BLOCK_LINE, alpha(secondary, 0x33))
            setColor(BLOCK_LINE_CURRENT, alpha(secondary, 0x88))
            setColor(SIDE_BLOCK_LINE, alpha(secondary, 0x88))
            setColor(NON_PRINTABLE_CHAR, alpha(secondary, 0x66))
            setColor(SCROLL_BAR_THUMB, alpha(secondary, 0x55))
            setColor(SCROLL_BAR_THUMB_PRESSED, alpha(secondary, 0xaa))
            setColor(HIGHLIGHTED_DELIMITERS_BACKGROUND, alpha(accent, 0x33))
            setColor(HIGHLIGHTED_DELIMITERS_UNDERLINE, accent)
            setColor(COMPLETION_WND_BACKGROUND, surface)
            setColor(COMPLETION_WND_CORNER, alpha(secondary, 0x55))
            setColor(COMPLETION_WND_TEXT_PRIMARY, text)
            setColor(COMPLETION_WND_TEXT_SECONDARY, secondary)
            setColor(COMPLETION_WND_ITEM_CURRENT, alpha(accent, 0x33))
            setColor(TEXT_ACTION_WINDOW_BACKGROUND, surface)
            setColor(TEXT_ACTION_WINDOW_ICON_COLOR, text)
            setColor(PROBLEM_ERROR, ERROR)
            setColor(PROBLEM_WARNING, WARNING)

            setColor(JsonColors.KEY, if (isDark) 0xff66d9e8.toInt() else 0xff0b7285.toInt())
            setColor(JsonColors.STRING, if (isDark) 0xff8ce99a.toInt() else 0xff2b8a3e.toInt())
            setColor(JsonColors.NUMBER, if (isDark) 0xffffc078.toInt() else 0xffe8590c.toInt())
            setColor(JsonColors.LITERAL, if (isDark) 0xffb197fc.toInt() else 0xff7048e8.toInt())
            setColor(JsonColors.COMMENT, 0xff868e96.toInt())
        }
    }
}
