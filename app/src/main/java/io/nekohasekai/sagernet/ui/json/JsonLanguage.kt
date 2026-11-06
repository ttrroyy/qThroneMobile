package app.rawline.mobile.ui.json

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.OnlineBracketsMatcher
import io.github.rosemoe.sora.lang.completion.CompletionItem
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import app.rawline.mobile.ui.json.engine.SchemaCompletion
import app.rawline.mobile.ui.json.engine.Suggestion
import kotlin.math.min

/** The sora-editor language of the JSON editor: highlighting, block lines, smart Enter and schema completion. */
class JsonLanguage(
    private val completion: () -> SchemaCompletion?,
    private val keyIcon: Drawable,
    private val valueIcon: Drawable,
) : Language {

    private val analyzer = JsonAnalyzer()

    // JsonCodeEditor pairs brackets and quotes itself
    private val pairs = SymbolPairMatch()
    private val newlineHandlers = arrayOf<NewlineHandler>(PairNewlineHandler())

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    override fun getInterruptionLevel() = Language.INTERRUPTION_LEVEL_STRONG

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle,
    ) {
        val engine = completion() ?: return
        val text = StringBuilder(content.length)
        for (line in 0 until content.lineCount) {
            if (line > 0) text.append(content.getLineSeparator(line - 1))
            content.appendLineTo(text, line)
        }
        val items = engine.suggest(text.toString(), position.index)
        if (items.isEmpty()) return
        publisher.addItems(items.map { JsonCompletionItem(it, if (it.isKey) keyIcon else valueIcon) })
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        val text = content.getLine(line)
        var i = min(column, text.length) - 1
        while (i >= 0 && (text[i] == ' ' || text[i] == '\t')) i--
        return if (i >= 0 && (text[i] == '{' || text[i] == '[')) INDENT else 0
    }

    override fun useTab() = false

    override fun getFormatter(): Formatter = EmptyLanguage.EmptyFormatter.INSTANCE

    override fun getSymbolPairs(): SymbolPairMatch = pairs

    override fun getNewlineHandlers(): Array<NewlineHandler> = newlineHandlers

    override fun destroy() {}

    /** Enter between `{}` or `[]` opens an indented empty line between them (JsonCodeEdit.cpp:359-385). */
    private class PairNewlineHandler : NewlineHandler {
        override fun matchesRequirement(text: Content, position: CharPosition, style: Styles?): Boolean {
            val line = text.getLineString(position.line)
            val column = position.column
            if (column <= 0 || column >= line.length) return false
            val before = line[column - 1]
            val after = line[column]
            return (before == '{' && after == '}') || (before == '[' && after == ']')
        }

        override fun handleNewline(text: Content, position: CharPosition, style: Styles?, tabSize: Int): NewlineHandleResult {
            val indent = text.getLineString(position.line).takeWhile { it == ' ' || it == '\t' }
            val tail = "\n$indent"
            return NewlineHandleResult("\n$indent${" ".repeat(INDENT)}$tail", tail.length)
        }
    }

    private class JsonCompletionItem(
        private val suggestion: Suggestion,
        icon: Drawable,
    ) : CompletionItem(suggestion.label, suggestion.detail, icon) {

        override fun performCompletion(editor: CodeEditor, text: Content, line: Int, column: Int) {
            val caret = text.getCharIndex(line, column)
            val start = (caret - suggestion.before).coerceIn(0, text.length)
            val end = (caret + suggestion.after).coerceIn(start, text.length)
            text.replace(start, end, suggestion.insert)
            val indexer = text.indexer
            val selStart = indexer.getCharPosition(min(start + suggestion.selectStart, text.length))
            if (suggestion.selectEnd > suggestion.selectStart) {
                val selEnd = indexer.getCharPosition(min(start + suggestion.selectEnd, text.length))
                editor.setSelectionRegion(selStart.line, selStart.column, selEnd.line, selEnd.column)
            } else {
                editor.setSelection(selStart.line, selStart.column)
            }
            if (suggestion.chain) {
                editor.postDelayedInLifecycle({
                    editor.getComponent(EditorAutoCompletion::class.java).requireCompletion()
                }, CHAIN_DELAY_MS)
            }
        }
    }

    /** A letter in a filled circle, the completion list's key/value marker. */
    class LetterIcon(private val letter: String, private val fill: Int, private val ink: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val radius = min(bounds.width(), bounds.height()) / 2f
            paint.color = fill
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius * 0.85f, paint)
            paint.color = ink
            paint.textSize = radius
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(letter, bounds.exactCenterX(), bounds.exactCenterY() - (paint.descent() + paint.ascent()) / 2, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** Whole-text highlighting in the analyzer thread (the desktop Highlighter, JsonCodeEdit.cpp:21-118). */
    private class JsonAnalyzer : SimpleAnalyzeManager<Unit>() {

        private val brackets = OnlineBracketsMatcher(charArrayOf('{', '}', '[', ']'), BRACKET_SCAN_LIMIT)

        override fun setReceiver(receiver: StyleReceiver?) {
            super.setReceiver(receiver)
            receiver?.updateBracketProvider(this, brackets)
        }

        override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
            val spans = MappedSpans.Builder()
            val styles = Styles(null, true)
            val openers = ArrayList<IntArray>()
            var line = 0
            var column = 0
            var i = 0
            val n = text.length

            // a token's style starts at its first character; plain text resumes after it unless a token follows at once
            var plainFrom: IntArray? = null
            fun begin(style: Long) {
                plainFrom?.let { if (it[0] != line || it[1] != column) spans.addIfNeeded(it[0], it[1], NORMAL) }
                plainFrom = null
                spans.addIfNeeded(line, column, style)
            }

            fun finish() {
                plainFrom = intArrayOf(line, column)
            }

            // advances over text[from, to) keeping line/column in step
            fun advance(to: Int) {
                while (i < to) {
                    val c = text[i]
                    if (c == '\n') {
                        line++
                        column = 0
                    } else if (c == '\r') {
                        if (i + 1 < n && text[i + 1] == '\n') i++
                        line++
                        column = 0
                    } else {
                        column++
                    }
                    i++
                }
            }

            while (i < n) {
                if (delegate.isCancelled) break
                val c = text[i]
                when {
                    c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                        begin(COMMENT)
                        var end = i + 2
                        while (end < n && text[end] != '\n' && text[end] != '\r') end++
                        advance(end)
                        finish()
                    }

                    c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                        begin(COMMENT)
                        val close = text.indexOf("*/", i + 2)
                        advance(if (close < 0) n else close + 2)
                        finish()
                    }

                    c == '"' -> {
                        var end = i + 1
                        while (end < n) {
                            val ch = text[end]
                            if (ch == '\\' && end + 1 < n && text[end + 1] != '\n') {
                                end += 2
                                continue
                            }
                            if (ch == '\n' || ch == '\r') break
                            end++
                            if (ch == '"') break
                        }
                        var look = end
                        while (look < n && text[look].isWhitespace()) look++
                        begin(if (look < n && text[look] == ':') KEY else STRING)
                        advance(end)
                        finish()
                    }

                    c == '-' || c in '0'..'9' -> {
                        var end = i + 1
                        while (end < n && (text[end].isLetterOrDigit() || text[end] == '.' || text[end] == '+' || text[end] == '-')) end++
                        begin(NUMBER)
                        advance(end)
                        finish()
                    }

                    c.isLetter() -> {
                        var end = i + 1
                        while (end < n && text[end].isLetter()) end++
                        val word = text.substring(i, end)
                        if (word == "true" || word == "false" || word == "null") {
                            begin(LITERAL)
                            advance(end)
                            finish()
                        } else {
                            advance(end)
                        }
                    }

                    c == '{' || c == '[' -> {
                        openers.add(intArrayOf(line, column, c.code))
                        begin(PUNCTUATION)
                        advance(i + 1)
                        finish()
                    }

                    c == '}' || c == ']' -> {
                        val open = if (c == '}') '{' else '['
                        val index = openers.indexOfLast { it[2] == open.code }
                        if (index >= 0) {
                            val start = openers[index]
                            while (openers.size > index) openers.removeAt(openers.size - 1)
                            if (start[0] < line) {
                                styles.addCodeBlock(CodeBlock().also {
                                    it.startLine = start[0]
                                    it.startColumn = start[1]
                                    it.endLine = line
                                    it.endColumn = column
                                })
                            }
                        }
                        begin(PUNCTUATION)
                        advance(i + 1)
                        finish()
                    }

                    c == ':' || c == ',' -> {
                        begin(PUNCTUATION)
                        advance(i + 1)
                        finish()
                    }

                    else -> advance(i + 1)
                }
            }
            plainFrom?.let { spans.addIfNeeded(it[0], it[1], NORMAL) }
            spans.determine(line)
            styles.spans = spans.build()
            styles.finishBuilding()
            return styles
        }
    }

    companion object {
        const val INDENT = 2
        private const val CHAIN_DELAY_MS = 120L
        private const val BRACKET_SCAN_LIMIT = 100_000

        private val NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
        private val KEY = TextStyle.makeStyle(JsonColors.KEY)
        private val STRING = TextStyle.makeStyle(JsonColors.STRING)
        private val NUMBER = TextStyle.makeStyle(JsonColors.NUMBER)
        private val LITERAL = TextStyle.makeStyle(JsonColors.LITERAL)
        private val PUNCTUATION = TextStyle.makeStyle(EditorColorScheme.OPERATOR)
        private val COMMENT = TextStyle.makeStyle(JsonColors.COMMENT, 0, false, true, false, true)
    }
}
