package com.example.chinlern.texts

import com.example.chinlern.dictionary.Pinyin
import com.example.chinlern.dictionary.OfflineDictionary
import java.util.Locale

internal object ReaderPronunciation {
    // Ordinary modern usage where the competing readings are chiefly literary or specialized.
    private val common = mapOf(
        "的" to "de5", "说" to "shuo1", "說" to "shuo1", "读" to "du2", "讀" to "du2",
        "都" to "dou1", "把" to "ba3", "打" to "da3", "跑" to "pao3", "雨" to "yu3"
    )
    private val restrictedPrefixes = listOf(
        "(archaic)", "(literary)", "(dialect)", "(bound form)", "(rare)", "(surname",
        "surname ", "variant of ", "old variant of ", "archaic variant of ", "unofficial variant of ",
        "used in ", "see ", "also written ", "taiwan pr."
    )
    private val restrictedSuffixes = listOf("(archaic)", "(literary)", "(dialect)", "(rare)")
    private val titles = listOf("先生", "女士", "老师", "老師", "教授", "同学", "同學", "大夫")

    fun mayBeName(text: String, span: ReaderWords.Span) =
        text.getOrNull(span.start - 1) == '姓' || titles.any { text.startsWith(it, span.end) }

    fun preferred(form: String, entries: List<OfflineDictionary.Entry>, personal: Set<String>): String? {
        common[form]?.let { standard ->
            if (personal.all { Pinyin.readingKey(it) == standard }) return standard
        }
        val independent = entries.filter { entry ->
            entry.meanings.any { meaning ->
                val gloss = meaning.trim().lowercase(Locale.ROOT)
                gloss.isNotEmpty() && restrictedPrefixes.none(gloss::startsWith) &&
                    restrictedSuffixes.none(gloss::endsWith)
            }
        }.map { it.pinyin }
        return (independent + personal).distinctBy(Pinyin::readingKey).singleOrNull()
    }

    fun inContext(text: String, span: ReaderWords.Span, form: String): String? {
        fun before(vararg forms: String) = forms.any { text.startsWith(it, span.start - it.length) }
        fun after(vararg forms: String) = forms.any { text.startsWith(it, span.end) }
        val preceding = text.getOrNull(span.start - 1)
        val following = text.getOrNull(span.end)
        val clauseEnd = following == null || following in "。！？!?，,；;\r\n"
        return when (form) {
            "和" -> if (after("我", "他", "她", "你", "朋友", "同学", "同學", "老师", "老師")) "he2" else null
            "了" -> if (preceding != null && preceding !in "不未得了" &&
                Character.UnicodeScript.of(preceding.code) == Character.UnicodeScript.HAN &&
                (clauseEnd || preceding in "买買吃喝看做走到回去来來给給说說写寫用听聽读讀见見放学學拿打穿")) "le5" else null
            "得了" -> if (after("第", "奖", "獎", "病", "感冒", "冠军", "冠軍")) "de2 le5" else null
            "地" -> if (before("慢慢", "认真", "認真", "安静", "安靜", "轻轻", "輕輕",
                    "仔细", "仔細", "静静", "靜靜", "高兴", "高興", "小心", "默默")) "de5" else null
            "得" -> when {
                before("我", "你", "他", "她", "们", "們", "就") &&
                    after("走了", "去了", "回去", "走", "去", "赶快", "趕快", "先", "早点", "早點", "马上", "馬上") -> "dei3"
                preceding != null && preceding in "看跑做说說写寫唱跳听聽走飞飛玩学學吃喝睡" && after("很", "太", "真", "比较", "比較") -> "de5"
                else -> null
            }
            "过", "過" -> if (preceding != null && preceding in "吃喝看听聽读讀做去来來见見学學用住试試问問想写寫玩")
                "guo5" else null
            "着", "著" -> if (!after("了") && (before("看", "拿", "抱", "带", "帶", "站", "坐", "等", "放", "写", "寫", "举", "舉") ||
                (before("下") && after("雨", "雪")) || (before("打") && after("伞", "傘")))) "zhe5" else null
            "还", "還" -> when {
                after("没", "沒", "要", "会", "會", "在", "能", "是", "想", "有", "不", "可以", "得") -> "hai2"
                after("书", "書", "钱", "錢", "给", "給") -> "huan2"
                else -> null
            }
            "看" -> when {
                after("电视", "電視", "课文", "課文", "报纸", "報紙", "电影", "電影", "得很") -> "kan4"
                after("家", "门", "門", "守") -> "kan1"
                else -> null
            }
            "吗", "嗎" -> if (clauseEnd && preceding != null) "ma5" else null
            "告诉", "告訴" -> if (after("我", "你", "他", "她", "大家", "朋友", "老师", "老師")) "gao4 su5" else null
            "东西", "東西" -> if (before("买", "買", "卖", "賣", "吃", "拿", "找", "有", "这些", "這些", "什么", "什麼"))
                "dong1 xi5" else null
            "行" -> if (before("办法", "辦法", "这样", "這樣")) "xing2" else null
            else -> null
        }
    }
}
