package com.manfaz.vpn.ui

import androidx.annotation.DrawableRes
import com.manfaz.vpn.R
import com.manfaz.vpn.data.model.Country

@DrawableRes
fun Country.landmarkRes(): Int = when (iso) {
    "DE" -> R.drawable.country_de
    "FR" -> R.drawable.country_fr
    "NL" -> R.drawable.country_nl
    "FI" -> R.drawable.country_fi
    "US" -> R.drawable.country_us
    "AE" -> R.drawable.country_ae
    "PL" -> R.drawable.country_pl
    "RU" -> R.drawable.country_ru
    "AZ" -> R.drawable.country_az
    "TR" -> R.drawable.country_tr
    "RO" -> R.drawable.country_ro
    "BG" -> R.drawable.country_bg
    "AU" -> R.drawable.country_au
    "JP" -> R.drawable.country_jp
    "SA" -> R.drawable.country_sa
    else -> R.drawable.country_generic
}
