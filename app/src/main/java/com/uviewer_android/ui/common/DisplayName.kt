package com.uviewer_android.ui.common

/**
 * 즐겨찾기/최근 파일/고정탭 목록에서 파일명 뒤에 붙는 위치·파일 표시를 제거한 제목을 반환합니다.
 *
 * 예:
 *  - "novel.txt - line 12332" -> "novel.txt"
 *  - "novel.epub - ch3 45%"   -> "novel.epub"
 *  - "comic.zip - 58489.jpg"  -> "comic.zip"
 */
private val positionSuffixRegex = Regex("^(line|page|pg)\\s+\\d+$", RegexOption.IGNORE_CASE)
private val chapterSuffixRegex = Regex("^ch\\s*\\d+(\\s+\\d+%)?$", RegexOption.IGNORE_CASE)

fun displayFileName(title: String, positionTitle: String? = null): String {
    // 이미지 뷰어가 저장한 "<파일명> - <이미지명>" 형식은 positionTitle과 정확히 일치할 때만 제거합니다.
    if (!positionTitle.isNullOrEmpty() && title.endsWith(" - $positionTitle")) {
        return title.dropLast(positionTitle.length + 3)
    }

    val separatorIndex = title.lastIndexOf(" - ")
    if (separatorIndex <= 0) return title

    val suffix = title.substring(separatorIndex + 3)
    return if (positionSuffixRegex.matches(suffix) || chapterSuffixRegex.matches(suffix)) {
        title.substring(0, separatorIndex)
    } else {
        title
    }
}
