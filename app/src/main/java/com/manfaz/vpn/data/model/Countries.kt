package com.manfaz.vpn.data.model

/**
 * Country detection from a server name. Handles:
 *  - Regional-indicator emoji flags already present in the name (🇩🇪 → DE)
 *  - English / Persian keywords and ISO codes
 * Returns a [Country] with flag emoji and Persian display name.
 */
data class Country(val iso: String, val flag: String, val faName: String)

object Countries {

    val UNKNOWN = Country("", "🌐", "عمومی")

    // ISO2 -> Persian name
    private val faNames = mapOf(
        "DE" to "آلمان", "NL" to "هلند", "US" to "آمریکا", "GB" to "انگلستان",
        "FR" to "فرانسه", "TR" to "ترکیه", "AE" to "امارات", "FI" to "فنلاند",
        "SG" to "سنگاپور", "JP" to "ژاپن", "CA" to "کانادا", "SE" to "سوئد",
        "CH" to "سوئیس", "RU" to "روسیه", "IN" to "هند", "IR" to "ایران",
        "PL" to "لهستان", "AT" to "اتریش", "IT" to "ایتالیا", "ES" to "اسپانیا",
        "RO" to "رومانی", "UA" to "اوکراین", "HK" to "هنگ‌کنگ", "KR" to "کره جنوبی",
        "AU" to "استرالیا", "BR" to "برزیل", "AM" to "ارمنستان", "AZ" to "آذربایجان",
        "GE" to "گرجستان", "KZ" to "قزاقستان", "QA" to "قطر", "OM" to "عمان",
        "CN" to "چین", "DK" to "دانمارک", "NO" to "نروژ", "IE" to "ایرلند",
        "CZ" to "چک", "BE" to "بلژیک", "PT" to "پرتغال", "LV" to "لتونی",
        "LT" to "لیتوانی", "EE" to "استونی", "BG" to "بلغارستان", "HU" to "مجارستان",
        "MD" to "مولداوی", "RS" to "صربستان", "LU" to "لوکزامبورگ", "CY" to "قبرس",
        "MY" to "مالزی", "TH" to "تایلند", "VN" to "ویتنام", "ID" to "اندونزی",
        "PH" to "فیلیپین", "TW" to "تایوان", "SA" to "عربستان", "IL" to "اسرائیل",
        "MX" to "مکزیک", "AR" to "آرژانتین", "ZA" to "آفریقای جنوبی",
    )

    // Keyword -> ISO2 (English + Persian). Order matters: longer/specific first.
    private val keywords = listOf(
        listOf("germany", "german", "frankfurt", "آلمان", "فرانکفورت") to "DE",
        listOf("netherlands", "amsterdam", "holland", "dutch", "هلند", "آمستردام") to "NL",
        listOf("united states", "usa", "america", "آمریکا", "امریکا") to "US",
        listOf("united kingdom", "britain", "london", "england", "انگلیس", "بریتانیا", "لندن") to "GB",
        listOf("france", "paris", "فرانسه", "پاریس") to "FR",
        listOf("turkey", "istanbul", "turkiye", "ترکیه", "استانبول") to "TR",
        listOf("emirates", "dubai", "u.a.e", "امارات", "دبی") to "AE",
        listOf("finland", "helsinki", "فنلاند", "هلسینکی") to "FI",
        listOf("singapore", "سنگاپور") to "SG",
        listOf("japan", "tokyo", "ژاپن", "توکیو") to "JP",
        listOf("canada", "toronto", "کانادا") to "CA",
        listOf("sweden", "stockholm", "سوئد") to "SE",
        listOf("switzerland", "zurich", "سوئیس") to "CH",
        listOf("russia", "moscow", "روسیه", "مسکو") to "RU",
        listOf("india", "mumbai", "هند") to "IN",
        listOf("poland", "warsaw", "لهستان") to "PL",
        listOf("austria", "vienna", "اتریش") to "AT",
        listOf("italy", "milan", "ایتالیا") to "IT",
        listOf("spain", "madrid", "اسپانیا") to "ES",
        listOf("romania", "bucharest", "رومانی") to "RO",
        listOf("ukraine", "اوکراین") to "UA",
        listOf("hong kong", "هنگ", "hongkong") to "HK",
        listOf("korea", "seoul", "کره") to "KR",
        listOf("australia", "sydney", "استرالیا") to "AU",
        listOf("armenia", "ارمنستان") to "AM",
        listOf("azerbaijan", "baku", "آذربایجان") to "AZ",
        listOf("georgia", "tbilisi", "گرجستان") to "GE",
        listOf("qatar", "قطر") to "QA",
        listOf("china", "چین") to "CN",
        listOf("denmark", "دانمارک") to "DK",
        listOf("norway", "نروژ") to "NO",
        listOf("ireland", "ایرلند") to "IE",
        listOf("czech", "چک") to "CZ",
        listOf("iran", "ایران") to "IR",
    )

    fun detect(name: String): Country {
        emojiFlag(name)?.let { iso -> return build(iso) }
        val n = name.lowercase()
        for ((keys, iso) in keywords) if (keys.any { n.contains(it) }) return build(iso)
        // Bare ISO2 token, e.g. "DE-01"
        Regex("\\b([A-Za-z]{2})\\b").findAll(name).forEach { m ->
            val iso = m.groupValues[1].uppercase()
            if (faNames.containsKey(iso)) return build(iso)
        }
        return UNKNOWN
    }

    fun fromIso(iso: String?): Country {
        val normalized = iso?.trim()?.uppercase().orEmpty()
        return if (faNames.containsKey(normalized)) build(normalized) else UNKNOWN
    }

    private fun build(iso: String) = Country(iso, isoToFlag(iso), faNames[iso] ?: iso)

    /** Extract ISO from the first regional-indicator emoji pair in the string. */
    private fun emojiFlag(s: String): String? {
        val cps = s.codePoints().toArray()
        for (i in 0 until cps.size - 1) {
            val a = cps[i]; val b = cps[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val c1 = 'A' + (a - 0x1F1E6)
                val c2 = 'A' + (b - 0x1F1E6)
                return "$c1$c2"
            }
        }
        return null
    }

    private fun isoToFlag(iso: String): String {
        if (iso.length != 2) return "🏳️"
        val sb = StringBuilder()
        for (c in iso.uppercase()) sb.appendCodePoint(0x1F1E6 + (c - 'A'))
        return sb.toString()
    }
}
