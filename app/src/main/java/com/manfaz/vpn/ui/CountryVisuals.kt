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
    else -> R.drawable.country_generic
}
