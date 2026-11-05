package com.example.locationservice

import java.util.Locale

object UnitConverter {
    const val KM_TO_MI = 0.621371192237334
    const val MI_TO_KM = 1.609344
    const val L_TO_GAL = 0.264172052358148
    const val GAL_TO_L = 3.785411784
    const val KML_TO_MPG = 2.352145833333333
    const val M_TO_FT = 3.280839895013123

    fun kmToDisplay(km: Double, isImperial: Boolean): Double {
        return if (isImperial) km * KM_TO_MI else km
    }

    fun displayToKm(distanceInput: Double, isImperial: Boolean): Double {
        return if (isImperial) distanceInput * MI_TO_KM else distanceInput
    }

    fun litersToDisplay(liters: Double, isImperial: Boolean): Double {
        return if (isImperial) liters * L_TO_GAL else liters
    }

    fun displayToLiters(volumeInput: Double, isImperial: Boolean): Double {
        return if (isImperial) volumeInput * GAL_TO_L else volumeInput
    }

    fun kmlToDisplay(kml: Double, isImperial: Boolean): Double {
        return if (isImperial) kml * KML_TO_MPG else kml
    }

    fun pricePerKmToDisplay(pricePerKm: Double, isImperial: Boolean): Double {
        return if (isImperial) pricePerKm * MI_TO_KM else pricePerKm
    }

    fun pricePerLiterToDisplay(pricePerL: Double, isImperial: Boolean): Double {
        return if (isImperial) pricePerL * GAL_TO_L else pricePerL
    }

    fun metersToDisplayAltitude(meters: Double, isImperial: Boolean): Double {
        return if (isImperial) meters * M_TO_FT else meters
    }

    fun distanceUnit(isImperial: Boolean, uppercase: Boolean = false): String {
        val u = if (isImperial) "mi" else "km"
        return if (uppercase) u.uppercase(Locale.US) else u
    }

    fun volumeUnit(isImperial: Boolean): String {
        return if (isImperial) "gal" else "L"
    }

    fun efficiencyUnit(isImperial: Boolean): String {
        return if (isImperial) "MPG" else "km/L"
    }

    fun pricePerDistanceUnit(isImperial: Boolean): String {
        return if (isImperial) "$/mi" else "$/km"
    }

    fun pricePerVolumeUnit(isImperial: Boolean): String {
        return if (isImperial) "$/gal" else "$/L"
    }

    fun altitudeUnit(isImperial: Boolean): String {
        return if (isImperial) "ft" else "m"
    }

    fun formatDistance(km: Double, isImperial: Boolean, decimals: Int = 2, uppercaseUnit: Boolean = false): String {
        val valDisplay = kmToDisplay(km, isImperial)
        val unit = distanceUnit(isImperial, uppercaseUnit)
        return String.format(Locale.US, "%.${decimals}f %s", valDisplay, unit)
    }

    fun formatVolume(liters: Double, isImperial: Boolean, decimals: Int = 2): String {
        val valDisplay = litersToDisplay(liters, isImperial)
        val unit = volumeUnit(isImperial)
        return String.format(Locale.US, "%.${decimals}f %s", valDisplay, unit)
    }

    fun formatEfficiency(kml: Double, isImperial: Boolean, decimals: Int = 2): String {
        val valDisplay = kmlToDisplay(kml, isImperial)
        val unit = efficiencyUnit(isImperial)
        return String.format(Locale.US, "%.${decimals}f %s", valDisplay, unit)
    }

    fun formatPricePerDistance(pricePerKm: Double, isImperial: Boolean, decimals: Int = 4): String {
        val valDisplay = pricePerKmToDisplay(pricePerKm, isImperial)
        val unit = pricePerDistanceUnit(isImperial)
        return String.format(Locale.US, "$%.${decimals}f%s", valDisplay, unit.removePrefix("$"))
    }
}

