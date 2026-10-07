package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.exec.CommandResult
import io.github.marcomaes.mobiletestbench.core.exec.CommandRunner
import io.github.marcomaes.mobiletestbench.core.ios.MobilecliClient
import io.github.marcomaes.mobiletestbench.core.location.LocationOverride
import io.github.marcomaes.mobiletestbench.core.location.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class LocationOverrideTest {

    private class FakeRunner(val exit: Int, val out: String, val err: String = "") : CommandRunner(emptyMap()) {
        var last: List<String>? = null
        override fun run(command: List<String>, timeout: Duration): CommandResult {
            last = command
            return CommandResult(exit, out.toByteArray(), err)
        }
    }

    @Test
    fun `pasted pairs are split`() {
        assertEquals("52.3676" to "4.9041", LocationOverride.parsePair("52.3676, 4.9041"))
        assertEquals("-33.8688" to "151.2093", LocationOverride.parsePair(" -33.8688,151.2093 "))
        assertEquals("1" to "2", LocationOverride.parsePair("1; 2"))
        assertEquals("1.5" to "-2.5", LocationOverride.parsePair("1.5 -2.5"))
        assertNull(LocationOverride.parsePair("52.3676"))
        assertNull(LocationOverride.parsePair("1,2,3"))
        assertNull(LocationOverride.parsePair("abc, 4"))
    }

    @Test
    fun `range validation including the boundaries`() {
        assertNull(LocationOverride.validateLatitude("90"))
        assertNull(LocationOverride.validateLatitude("-90"))
        assertNull(LocationOverride.validateLongitude("180"))
        assertNull(LocationOverride.validateLongitude("-180.0"))
        assertEquals("Latitude must be between -90 and 90", LocationOverride.validateLatitude("91"))
        assertEquals("Longitude must be between -180 and 180", LocationOverride.validateLongitude("-181"))
        assertEquals("Latitude is required", LocationOverride.validateLatitude(" "))
        assertNotNull(LocationOverride.validateLatitude("1e3"))
        assertNotNull(LocationOverride.validateLatitude("52,3676"))
        assertTrue(LocationOverride.parse("52", "4") is LocationOverride.Parsed.Ok)
        assertTrue(LocationOverride.parse("52", "400") is LocationOverride.Parsed.Invalid)
    }

    @Test
    fun `arguments use a dot and keep a negative latitude from being a flag`() {
        assertEquals(listOf("device", "location", "set", "52.3676,4.9041"), LocationOverride.setArgs(52.3676, 4.9041))
        assertEquals(" -33.8688,151.2093", LocationOverride.coordinateArg(-33.8688, 151.2093))
        assertEquals("40.7128,-74.006", LocationOverride.coordinateArg(40.7128, -74.0060))
        assertEquals("0,0", LocationOverride.coordinateArg(-0.0, 0.0))
        assertEquals("90,-180", LocationOverride.coordinateArg(90.0, -180.0))
    }

    @Test
    fun `saved places round trip and cannot shadow presets`() {
        val home = Place("Home | office", 52.0, 5.5)
        val saved = LocationOverride.savePlace(emptyList(), home)!!
        assertEquals("Home   office", saved.single().name)
        val again = LocationOverride.savePlace(saved, Place("home   office", 1.0, 2.0))!!
        assertEquals(1, again.size)
        assertEquals(1.0, again.single().latitude, 0.0)
        assertNull(LocationOverride.savePlace(saved, Place("amsterdam", 1.0, 2.0)))
        assertNull(LocationOverride.savePlace(saved, Place("  ", 1.0, 2.0)))
        assertEquals(saved, LocationOverride.decodePlaces(saved.map { LocationOverride.encodePlace(it) }))
        assertTrue(LocationOverride.deletePlace(saved, saved.single()).isEmpty())
        assertTrue(LocationOverride.decodePlaces(listOf("bad", "x|95|1", "y|a|b")).isEmpty())
    }

    @Test
    fun `friendlyError falls back and keeps the first line, format rounds to 6 decimals`() {
        assertEquals("Location command failed", LocationOverride.friendlyError(""))
        assertEquals("Location command failed", LocationOverride.friendlyError("  \n "))
        assertEquals("foo", LocationOverride.friendlyError("\n\nfoo\nbar"))
        assertEquals("52.367612", LocationOverride.format(52.36761234567))
        assertEquals("52.367613", LocationOverride.format(52.3676126))
    }

    @Test
    fun `presets are valid`() {
        assertEquals(5, LocationOverride.PRESETS.size)
        LocationOverride.PRESETS.forEach {
            assertNull(LocationOverride.validateLatitude(it.latitude.toString()))
            assertNull(LocationOverride.validateLongitude(it.longitude.toString()))
        }
    }

    @Test
    fun `set builds the command and reads the success fixture`() {
        val out = javaClass.getResource("/fixtures/location-set-ok.json")!!.readText()
        val runner = FakeRunner(0, out)
        val result = MobilecliClient("/bin/mobilecli", runner).call("Pixel_9", LocationOverride.setArgs(-33.8688, 151.2093))
        assertEquals(listOf("/bin/mobilecli", "device", "location", "set", " -33.8688,151.2093", "--device", "Pixel_9"), runner.last)
        assertEquals(-33.8688, MobilecliJson.data(result)!!.asJsonObject.get("latitude").asDouble, 0.0)
    }

    @Test
    fun `errors come from stderr or the json envelope`() {
        fun msg(r: FakeRunner) = runCatching { MobilecliClient("m", r).call("X", LocationOverride.CLEAR_ARGS) }.exceptionOrNull()!!.message!!
        val stderrOnly = javaClass.getResource("/fixtures/location-set-range-error.txt")!!.readText()
        assertEquals("latitude 91 out of range, must be between -90 and 90", LocationOverride.friendlyError(msg(FakeRunner(1, "", stderrOnly))))
        assertEquals("device not found: nope", msg(FakeRunner(1, """{"status":"error","error":"device not found: nope"}""", "device not found: nope")))
    }
}
