package com.vidgrab.app.util

object Languages {

    private val map = linkedMapOf(
        "af" to "Afrikaans",
        "am" to "Amharic",
        "ar" to "Arabic",
        "az" to "Azerbaijani",
        "be" to "Belarusian",
        "bg" to "Bulgarian",
        "bn" to "Bengali",
        "bs" to "Bosnian",
        "ca" to "Catalan",
        "cs" to "Czech",
        "da" to "Danish",
        "de" to "German",
        "el" to "Greek",
        "en" to "English",
        "eo" to "Esperanto",
        "es" to "Spanish",
        "et" to "Estonian",
        "eu" to "Basque",
        "fa" to "Persian",
        "fi" to "Finnish",
        "fr" to "French",
        "ga" to "Irish",
        "gl" to "Galician",
        "gu" to "Gujarati",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hr" to "Croatian",
        "ht" to "Haitian Creole",
        "hu" to "Hungarian",
        "hy" to "Armenian",
        "id" to "Indonesian",
        "is" to "Icelandic",
        "it" to "Italian",
        "ja" to "Japanese",
        "ka" to "Georgian",
        "kk" to "Kazakh",
        "km" to "Khmer",
        "kn" to "Kannada",
        "ko" to "Korean",
        "ky" to "Kyrgyz",
        "lt" to "Lithuanian",
        "lv" to "Latvian",
        "mk" to "Macedonian",
        "ml" to "Malayalam",
        "mn" to "Mongolian",
        "mr" to "Marathi",
        "ms" to "Malay",
        "my" to "Burmese",
        "ne" to "Nepali",
        "nl" to "Dutch",
        "no" to "Norwegian",
        "pa" to "Punjabi",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "si" to "Sinhala",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "so" to "Somali",
        "sq" to "Albanian",
        "sr" to "Serbian",
        "sv" to "Swedish",
        "sw" to "Swahili",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tl" to "Filipino",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "uz" to "Uzbek",
        "vi" to "Vietnamese",
        "zh" to "Chinese",
        "zh-Hans" to "Chinese (Simplified)",
        "zh-Hant" to "Chinese (Traditional)"
    )

    fun displayName(code: String): String {
        val base = code.substringBefore("-orig").substringBefore("@")
        return map[base]
            ?: try {
                java.util.Locale.forLanguageTag(base).displayName
            } catch (_: Exception) {
                base
            }
    }

    fun isKnown(code: String): Boolean {
        val base = code.substringBefore("-orig").substringBefore("@")
        return map.containsKey(base) || runCatching {
            java.util.Locale.forLanguageTag(base).displayName != base
        }.getOrDefault(false)
    }

    val all: Map<String, String> get() = map
}
