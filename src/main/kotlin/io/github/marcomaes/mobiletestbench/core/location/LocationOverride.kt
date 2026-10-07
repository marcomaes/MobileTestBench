package io.github.marcomaes.mobiletestbench.core.location

import java.util.Locale

/** A named place. */
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Pure helpers for the GPS location override: parsing, validation, presets, saved places and the mobilecli arguments. */
object LocationOverride {

    val PRESETS = listOf(
        Place("Amsterdam", 52.3676, 4.9041),
        Place("London", 51.5074, -0.1278),
        Place("New York", 40.7128, -74.0060),
        Place("Tokyo", 35.6762, 139.6503),
        Place("Sydney", -33.8688, 151.2093),
    )

    private val number = """[+-]?\d+(?:\.\d+)?"""
    private val pair = Regex("""^\s*($number)\s*(?:[,;]\s*|\s+)($number)\s*$""")

    /** "lat, lon" (comma, semicolon or whitespace separated, dot decimals) as a pair of strings, or null when [text] is not such a pair. */
    fun parsePair(text: String): Pair<String, String>? =
        pair.matchEntire(text)?.let { it.groupValues[1] to it.groupValues[2] }

    sealed class Parsed {
        data class Ok(val latitude: Double, val longitude: Double) : Parsed()
        data class Invalid(val reason: String) : Parsed()
    }

    fun validateLatitude(text: String): String? = validate(text, "Latitude", 90.0)
    fun validateLongitude(text: String): String? = validate(text, "Longitude", 180.0)

    /** An error text, or null when [text] is a valid number within -[limit]..[limit]. */
    private fun validate(text: String, label: String, limit: Double): String? {
        val value = text.trim().toDoubleOrNull()?.takeIf { text.trim().matches(Regex(number)) }
            ?: return if (text.isBlank()) "$label is required" else "$label must be a number (use a dot as the decimal separator)"
        return if (value < -limit || value > limit) "$label must be between -${format(limit)} and ${format(limit)}" else null
    }

    fun parse(latitude: String, longitude: String): Parsed {
        validateLatitude(latitude)?.let { return Parsed.Invalid(it) }
        validateLongitude(longitude)?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(latitude.trim().toDouble(), longitude.trim().toDouble())
    }

    /** Plain decimal with a dot, no exponent, trailing zeros removed (`52.3676`, `-74.006`, `90`). */
    fun format(value: Double): String {
        val s = java.math.BigDecimal(String.format(Locale.ROOT, "%.6f", value)).stripTrailingZeros().toPlainString()
        return if (s == "-0") "0" else s
    }

    /** The `lat,lon` argument. A negative latitude gets a leading space (mobilecli trims it) so it is not taken for a flag; the device is appended by the caller. */
    fun coordinateArg(latitude: Double, longitude: Double): String {
        val s = "${format(latitude)},${format(longitude)}"
        return if (s.startsWith("-")) " $s" else s
    }

    fun setArgs(latitude: Double, longitude: Double): List<String> = listOf("device", "location", "set", coordinateArg(latitude, longitude))

    val CLEAR_ARGS: List<String> = listOf("device", "location", "clear")

    /** The place list: presets, then saved places (a saved place with a preset's name is dropped), each saved entry encoded as `name|lat|lon`. */
    fun encodePlace(place: Place): String = "${place.name.replace("|", " ")}|${format(place.latitude)}|${format(place.longitude)}"

    fun decodePlace(entry: String): Place? {
        val parts = entry.split("|")
        if (parts.size != 3) return null
        val name = parts[0].trim().ifEmpty { return null }
        val lat = parts[1].toDoubleOrNull() ?: return null
        val lon = parts[2].toDoubleOrNull() ?: return null
        if (validateLatitude(parts[1]) != null || validateLongitude(parts[2]) != null) return null
        return Place(name, lat, lon)
    }

    fun decodePlaces(entries: List<String>): List<Place> = entries.mapNotNull { decodePlace(it) }

    fun isPreset(place: Place): Boolean = PRESETS.any { it.name.equals(place.name, ignoreCase = true) }

    /** Adds or replaces (by name, ignoring case) a saved place. Null when the name is blank or is a preset's name. */
    fun savePlace(saved: List<Place>, place: Place): List<Place>? {
        val name = place.name.trim().replace("|", " ")
        if (name.isEmpty() || isPreset(place.copy(name = name))) return null
        return saved.filterNot { it.name.equals(name, ignoreCase = true) } + place.copy(name = name)
    }

    fun deletePlace(saved: List<Place>, place: Place): List<Place> = saved.filterNot { it.name.equals(place.name, ignoreCase = true) }

    /** A short readable error from mobilecli's text. Its own validation messages are already clear and are passed through. */
    fun friendlyError(message: String): String = message.trim().lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Location command failed"
}
