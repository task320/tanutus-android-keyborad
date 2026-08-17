package com.tanutus.ime.core.conversion

/**
 * Romaji -> hiragana lookup table for the interim placeholder converter.
 *
 * This is a representative subset (seion, dakuon/handakuon, and one youon row per
 * consonant), not an exhaustive romaji specification — it exists to demonstrate the
 * mechanism until a real conversion engine (e.g. Mozc) replaces [RomajiHiraganaConverter]
 * behind the [KanaConverter] interface. Sokuon (っ) and trailing "n" (ん) are handled
 * separately in [RomajiHiraganaConverter], not via table entries.
 */
object RomajiKanaTable {
    val TABLE: Map<String, String> =
        buildMap {
            // Seion
            put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")
            put("ka", "か"); put("ki", "き"); put("ku", "く"); put("ke", "け"); put("ko", "こ")
            put("sa", "さ"); put("shi", "し"); put("su", "す"); put("se", "せ"); put("so", "そ")
            put("ta", "た"); put("chi", "ち"); put("tsu", "つ"); put("te", "て"); put("to", "と")
            put("na", "な"); put("ni", "に"); put("nu", "ぬ"); put("ne", "ね"); put("no", "の")
            put("ha", "は"); put("hi", "ひ"); put("fu", "ふ"); put("he", "へ"); put("ho", "ほ")
            put("ma", "ま"); put("mi", "み"); put("mu", "む"); put("me", "め"); put("mo", "も")
            put("ya", "や"); put("yu", "ゆ"); put("yo", "よ")
            put("ra", "ら"); put("ri", "り"); put("ru", "る"); put("re", "れ"); put("ro", "ろ")
            put("wa", "わ"); put("wo", "を")

            // Dakuon
            put("ga", "が"); put("gi", "ぎ"); put("gu", "ぐ"); put("ge", "げ"); put("go", "ご")
            put("za", "ざ"); put("ji", "じ"); put("zu", "ず"); put("ze", "ぜ"); put("zo", "ぞ")
            put("da", "だ"); put("di", "ぢ"); put("du", "づ"); put("de", "で"); put("do", "ど")
            put("ba", "ば"); put("bi", "び"); put("bu", "ぶ"); put("be", "べ"); put("bo", "ぼ")

            // Handakuon
            put("pa", "ぱ"); put("pi", "ぴ"); put("pu", "ぷ"); put("pe", "ぺ"); put("po", "ぽ")

            // Youon (representative — one contracted row per consonant)
            put("kya", "きゃ"); put("kyu", "きゅ"); put("kyo", "きょ")
            put("sha", "しゃ"); put("shu", "しゅ"); put("sho", "しょ")
            put("cha", "ちゃ"); put("chu", "ちゅ"); put("cho", "ちょ")
            put("nya", "にゃ"); put("nyu", "にゅ"); put("nyo", "にょ")
            put("hya", "ひゃ"); put("hyu", "ひゅ"); put("hyo", "ひょ")
            put("mya", "みゃ"); put("myu", "みゅ"); put("myo", "みょ")
            put("rya", "りゃ"); put("ryu", "りゅ"); put("ryo", "りょ")
            put("gya", "ぎゃ"); put("gyu", "ぎゅ"); put("gyo", "ぎょ")
            put("ja", "じゃ"); put("ju", "じゅ"); put("jo", "じょ")
            put("bya", "びゃ"); put("byu", "びゅ"); put("byo", "びょ")
            put("pya", "ぴゃ"); put("pyu", "ぴゅ"); put("pyo", "ぴょ")
        }
}
