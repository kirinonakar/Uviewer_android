package com.uviewer_android.ui.viewer

internal object DocumentCsvRenderer {
    fun render(content: String): String {
        val rows = content.lines().filter { it.isNotBlank() }
        val table = StringBuilder("<div class='table-container' style='overflow-x: auto; margin: 1em 0; width: 100%;'>")
        table.append("<table style='width: 100%; table-layout: fixed; border-collapse: collapse; margin: 1em 0;'>")
        rows.forEach { row ->
            table.append("<tr>")
            row.split(",").forEach { cell ->
                table.append("<td style='border: 1px solid #888; padding: 8px; white-space: normal; word-wrap: break-word; overflow-wrap: break-word; vertical-align: top;'>")
                table.append(cell.trim())
                table.append("</td>")
            }
            table.append("</tr>")
        }
        table.append("</table></div>")
        return table.toString()
    }
}
