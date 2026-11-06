package app.rawline.mobile.ui.json

import android.content.Context
import android.util.AttributeSet
import io.github.rosemoe.sora.widget.CodeEditor

/**
 * The typing behaviour of the desktop JsonCodeEdit (JsonCodeEdit.cpp:354-428): `{ [ "` insert their closing pair,
 * typing a closing character that is already next skips over it, Backspace between an empty pair removes both.
 * Nothing is paired inside a string.
 */
class JsonCodeEditor : CodeEditor {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    override fun commitText(text: CharSequence, applyAutoIndent: Boolean, applySymbolCompletion: Boolean) {
        val cur = cursor
        if (text.length == 1 && !cur.isSelected) {
            val typed = text[0]
            val line = cur.leftLine
            val column = cur.leftColumn
            val content = this.text
            val after = if (column < content.getColumnCount(line)) content.charAt(line, column) else '\u0000'
            val before = if (column > 0) content.charAt(line, column - 1) else '\u0000'
            if ((typed == '}' || typed == ']' || typed == '"') && after == typed && before != '\\') {
                setSelection(line, column + 1)
                return
            }
            if (applySymbolCompletion && (typed == '{' || typed == '[' || typed == '"') &&
                !insideString(content.getLineString(line), column)
            ) {
                if (typed != '"' || !after.isLetterOrDigit()) {
                    val close = if (typed == '{') '}' else if (typed == '[') ']' else '"'
                    super.commitText("$typed$close", false, false)
                    setSelection(line, column + 1)
                    return
                }
            }
        }
        super.commitText(text, applyAutoIndent, applySymbolCompletion)
    }

    override fun deleteText() {
        val cur = cursor
        if (!cur.isSelected) {
            val line = cur.leftLine
            val column = cur.leftColumn
            val content = text
            if (column > 0 && column < content.getColumnCount(line)) {
                val before = content.charAt(line, column - 1)
                val after = content.charAt(line, column)
                if ((before == '{' && after == '}') || (before == '[' && after == ']') ||
                    (before == '"' && after == '"')
                ) {
                    content.delete(line, column - 1, line, column + 1)
                    return
                }
            }
        }
        super.deleteText()
    }

    /** Whether [column] of [line] lies inside a string literal (odd count of unescaped quotes before it). */
    private fun insideString(line: String, column: Int): Boolean {
        var inside = false
        var i = 0
        while (i < column && i < line.length) {
            when (line[i]) {
                '\\' -> if (inside) i++
                '"' -> inside = !inside
            }
            i++
        }
        return inside
    }
}
