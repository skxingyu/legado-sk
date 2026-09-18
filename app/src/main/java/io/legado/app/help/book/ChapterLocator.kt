package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.utils.StringUtils
import org.apache.commons.text.similarity.JaccardSimilarity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 换源后的阅读进度定位：把「旧目录里的章节位置」映射到「新目录里的章节位置」。
 *
 * 从 [BookHelp] 中抽出并保持**纯 JVM 实现**（无 `appCtx` / 无 Android 依赖），
 * 这样合并逻辑可以在单元测试里直接覆盖。
 * [BookHelp.getDurChapter] 保留为同签名转发，既有调用方不受影响。
 *
 * 匹配策略（与既有行为完全一致）：
 * 1. 按章节名相似度（Jaccard）在旧位置附近的窗口内找最像的一章；
 * 2. 相似度不足时，退化为按「章节号」找最接近的一章；
 * 3. 两者都不可靠时，按新旧目录长度做等比换算并对结果做夹取。
 */
object ChapterLocator {

    private val jaccardSimilarity by lazy { JaccardSimilarity() }

    private val regexA by lazy { "\\s".toRegex() }

    /**
     * 解析章节名中的章节号（中文/阿拉伯数字，支持"第N章/回/集……""N、标题"等形态）；
     * 解析失败返回 -1。统一委托 [ChapterTitle]，与音频文本融合等场景共用同一解析口径。
     */
    fun chapterNum(chapterName: String?): Int = ChapterTitle.num(chapterName)

    /**
     * 根据目录名获取当前章节。
     *
     * @param oldDurChapterIndex 旧目录里的章节序号
     * @param oldDurChapterName 旧目录里的章节标题
     * @param newChapterList 新目录
     * @param oldChapterListSize 旧目录长度（用于等比换算；0 表示未知）
     * @return 新目录里的章节序号
     */
    fun findChapterIndex(
        oldDurChapterIndex: Int,
        oldDurChapterName: String?,
        newChapterList: List<BookChapter>,
        oldChapterListSize: Int = 0
    ): Int {
        if (oldDurChapterIndex <= 0) return 0
        if (newChapterList.isEmpty()) return oldDurChapterIndex
        val oldChapterNum = chapterNum(oldDurChapterName)
        val oldName = pureChapterName(oldDurChapterName)
        val newChapterSize = newChapterList.size
        val durIndex =
            if (oldChapterListSize == 0) oldDurChapterIndex
            else oldDurChapterIndex * oldChapterListSize / newChapterSize
        val min = max(0, min(oldDurChapterIndex, durIndex) - 10)
        val max = min(newChapterSize - 1, max(oldDurChapterIndex, durIndex) + 10)
        var nameSim = 0.0
        var newIndex = 0
        var newNum = 0
        if (oldName.isNotEmpty()) {
            for (i in min..max) {
                val newName = pureChapterName(newChapterList[i].title)
                val temp = jaccardSimilarity.apply(oldName, newName)
                if (temp > nameSim) {
                    nameSim = temp
                    newIndex = i
                }
            }
        }
        if (nameSim < 0.96 && oldChapterNum > 0) {
            for (i in min..max) {
                val temp = chapterNum(newChapterList[i].title)
                if (temp == oldChapterNum) {
                    newNum = temp
                    newIndex = i
                    break
                } else if (abs(temp - oldChapterNum) < abs(newNum - oldChapterNum)) {
                    newNum = temp
                    newIndex = i
                }
            }
        }
        return if (nameSim > 0.96 || abs(newNum - oldChapterNum) < 1) {
            newIndex
        } else {
            min(max(0, newChapterList.size - 1), oldDurChapterIndex)
        }
    }

    fun findChapterIndex(oldBook: Book, newChapterList: List<BookChapter>): Int {
        return oldBook.run {
            findChapterIndex(durChapterIndex, durChapterTitle, newChapterList, totalChapterNum)
        }
    }

    private val regexOther by lazy {
        // 所有非字母数字中日韩文字 CJK区+扩展A-F区
        @Suppress("RegExpDuplicateCharacterInClass")
        return@lazy "[^\\w\\u4E00-\\u9FEF〇\\u3400-\\u4DBF\\u20000-\\u2A6DF\\u2A700-\\u2EBEF]".toRegex()
    }

    @Suppress("RegExpUnnecessaryNonCapturingGroup", "RegExpSimplifiable")
    private val regexB by lazy {
        //章节序号，排除处于结尾的状况，避免将章节名替换为空字串
        return@lazy "^.*?第(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)[章节篇回集话](?!$)|^(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+[,:、])*(?:[\\d零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+)(?:[,:、](?!$)|\\.(?=[^\\d]))".toRegex()
    }

    private val regexC by lazy {
        //前后附加内容，整个章节名都在括号中时只剔除首尾括号，避免将章节名替换为空字串
        return@lazy "(?!^)(?:[〖【《〔\\[{(][^〖【《〔\\[{()〕》】〗\\]}]+)?[)〕》】〗\\]}]$|^[〖【《〔\\[{(](?:[^〖【《〔\\[{()〕》】〗\\]}]+[〕》】〗\\]})])?(?!$)".toRegex()
    }

    /** 去掉章节名里的序号、空白与括号附加内容，只留下可比较的主体文字。 */
    fun pureChapterName(chapterName: String?): String {
        return if (chapterName == null) "" else StringUtils.fullToHalf(chapterName)
            .replace(regexA, "")
            .replace(regexB, "")
            .replace(regexC, "")
            .replace(regexOther, "")
    }
}
