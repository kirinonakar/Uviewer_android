package com.uviewer_android.data.utils

/** Compares digit runs without converting to integers (even very long page numbers). */
internal object NaturalPathComparator : Comparator<String> {
    override fun compare(left: String, right: String): Int {
        val a = left.replace('\\', '/')
        val b = right.replace('\\', '/')
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                var endA = i
                var endB = j
                while (endA < a.length && a[endA] in '0'..'9') endA++
                while (endB < b.length && b[endB] in '0'..'9') endB++
                while (i < endA && a[i] == '0') i++
                while (j < endB && b[j] == '0') j++
                val lengthOrder = (endA - i).compareTo(endB - j)
                if (lengthOrder != 0) return lengthOrder
                while (i < endA) {
                    val order = a[i++].compareTo(b[j++])
                    if (order != 0) return order
                }
                i = endA
                j = endB
            } else {
                val order = a[i++].lowercaseChar().compareTo(b[j++].lowercaseChar())
                if (order != 0) return order
            }
        }
        val remaining = (a.length - i).compareTo(b.length - j)
        return if (remaining != 0) remaining else a.compareTo(b)
    }
}
