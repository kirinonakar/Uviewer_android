package com.uviewer_android.ui.viewer

private class ObsidianHighlight : org.commonmark.node.CustomNode(), org.commonmark.node.Delimited {
    override fun getOpeningDelimiter(): String = "=="
    override fun getClosingDelimiter(): String = "=="
}

private object ObsidianHighlightDelimiterProcessor : org.commonmark.parser.delimiter.DelimiterProcessor {
    override fun getOpeningCharacter(): Char = '='
    override fun getClosingCharacter(): Char = '='
    override fun getMinLength(): Int = 2

    override fun process(
        openingRun: org.commonmark.parser.delimiter.DelimiterRun,
        closingRun: org.commonmark.parser.delimiter.DelimiterRun
    ): Int {
        if (openingRun.length() < 2 || closingRun.length() < 2) return 0

        val highlight = ObsidianHighlight()
        var node = openingRun.opener.next
        while (node != null && node != closingRun.closer) {
            val next = node.next
            highlight.appendChild(node)
            node = next
        }
        openingRun.opener.insertAfter(highlight)
        return 2
    }
}

private class ObsidianHighlightHtmlNodeRenderer(
    private val context: org.commonmark.renderer.html.HtmlNodeRendererContext
) : org.commonmark.renderer.NodeRenderer {
    private val html = context.writer

    override fun getNodeTypes(): Set<Class<out org.commonmark.node.Node>> {
        return setOf(ObsidianHighlight::class.java)
    }

    override fun render(node: org.commonmark.node.Node) {
        html.tag("mark", context.extendAttributes(node, "mark", emptyMap()))
        var child = node.firstChild
        while (child != null) {
            val next = child.next
            context.render(child)
            child = next
        }
        html.tag("/mark")
    }
}

private object ObsidianHighlightExtension :
    org.commonmark.parser.Parser.ParserExtension,
    org.commonmark.renderer.html.HtmlRenderer.HtmlRendererExtension {

    override fun extend(parserBuilder: org.commonmark.parser.Parser.Builder) {
        parserBuilder.customDelimiterProcessor(ObsidianHighlightDelimiterProcessor)
    }

    override fun extend(rendererBuilder: org.commonmark.renderer.html.HtmlRenderer.Builder) {
        rendererBuilder.nodeRendererFactory { context -> ObsidianHighlightHtmlNodeRenderer(context) }
    }
}

internal fun convertDocumentMarkdownToHtml(md: String): String {
    val mathBlocks = mutableListOf<String>()

    var processedMd = md.replace(Regex("\\$\\$(.*?)\\$\\$", RegexOption.DOT_MATCHES_ALL)) {
        val index = mathBlocks.size
        mathBlocks.add(it.value)
        "K_MATH_BLOCK_${index}_K"
    }

    processedMd = processedMd.replace(Regex("(?<!\\\\)\\$(?!\\s)([^\\s$](?:[^$]*?[^\\s$])?)(?<!\\\\)\\$")) {
        val index = mathBlocks.size
        mathBlocks.add(it.value)
        "K_MATH_INLINE_${index}_K"
    }

    val extensions = listOf(
        org.commonmark.ext.gfm.tables.TablesExtension.create(),
        ObsidianHighlightExtension
    )

    val preprocessedMd = processedMd.replace(
        Regex("(?<![\\s*_\u201C\u2018\u300C\u300E\u3008\u300A\u3010\u3014\u3016\u3018\u301A(\\[{])(\\*\\*|\\*|__|\\_)(?=[가-힣])"),
        "$1 "
    )

    val parser = org.commonmark.parser.Parser.builder()
        .extensions(extensions)
        .build()

    val document = parser.parse(preprocessedMd)

    val renderer = org.commonmark.renderer.html.HtmlRenderer.builder()
        .extensions(extensions)
        .softbreak("<br/>")
        .build()

    var html = renderer.render(document)

    mathBlocks.forEachIndexed { index, original ->
        val escaped = original.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        html = html.replace("K_MATH_BLOCK_${index}_K", escaped)
            .replace("K_MATH_INLINE_${index}_K", escaped)
    }

    return html.replace(Regex("(</(?:strong|em)>) (?=[가-힣])"), "$1")
}
