package io.github.marcomaes.mobiletestbench.core

import io.github.marcomaes.mobiletestbench.core.inspector.AttributeTerm
import io.github.marcomaes.mobiletestbench.core.inspector.FilterCriteria
import io.github.marcomaes.mobiletestbench.core.inspector.FilterQuery
import io.github.marcomaes.mobiletestbench.core.inspector.HierarchyFilter
import io.github.marcomaes.mobiletestbench.core.inspector.UiNode
import io.github.marcomaes.mobiletestbench.core.inspector.WdaSourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HierarchyFilterTest {

    private val ios: UiNode = WdaSourceParser.parse(
        javaClass.getResourceAsStream("/fixtures/ios_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    @Test
    fun `empty criteria show everything`() {
        assertNull(HierarchyFilter.visibleNodes(ios, FilterCriteria()))
        assertNull(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "  ")))
    }

    @Test
    fun `text matches id label and type, case insensitive, and keeps the ancestors`() {
        val shown = HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "EMAIL"))!!
        val email = ios.walk().first { it.identifier == "email-input" }
        assertTrue(email in shown)
        assertTrue(ios in shown)
        assertTrue(ios.walk().filter { it.type == "Button" }.none { it in shown })
        assertTrue(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "button"))!!.any { it.type == "Button" })
    }

    @Test
    fun `visible only drops hidden elements`() {
        val hidden = ios.walk().first { it.attr("name") == "Hidden" }
        val shown = HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "hidden"))!!
        assertTrue(hidden in shown)
        assertTrue(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "hidden", visibleOnly = true))!!.none { it === hidden })
    }

    @Test
    fun `interactive only keeps controls`() {
        val shown = HierarchyFilter.visibleNodes(ios, FilterCriteria(interactiveOnly = true))!!
        val leaves = shown.filter { it.children.none { c -> c in shown } }
        assertTrue(leaves.isNotEmpty())
        assertEquals(setOf("Button", "TextField"), leaves.map { it.type }.toSet())
    }

    @Test
    fun `no match gives an empty set`() {
        assertTrue(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "zzz-nothing"))!!.isEmpty())
    }

    private val android: UiNode = io.github.marcomaes.mobiletestbench.core.inspector.UiAutomatorParser.parse(
        javaClass.getResourceAsStream("/fixtures/android_login.xml")!!.readBytes().toString(Charsets.UTF_8),
    )

    private fun shownIos(text: String) = HierarchyFilter.visibleNodes(ios, FilterCriteria(text = text))!!
    private fun shownAndroid(text: String) = HierarchyFilter.visibleNodes(android, FilterCriteria(text = text))!!

    @Test
    fun `a quoted OR stays free text and an empty negated quote is ignored`() {
        assertEquals("OR", FilterQuery.parse("\"OR\"").freeText)
        val q = FilterQuery.parse("-\"\"")
        assertTrue(q.groups.isEmpty())
    }

    @Test
    fun `parse splits free text, terms and invalid terms`() {
        val q = FilterQuery.parse("log in clickable=true class~Button =x")
        assertEquals("log in", q.freeText)
        assertEquals(listOf("clickable=true", "class~Button"), q.terms.map { it.toString() })
        assertEquals(listOf("=x"), q.invalid.map { it.text })
    }

    @Test
    fun `parse handles quotes, not-equals and odd keys`() {
        val q = FilterQuery.parse("""text="Log in" id!=a "two words" http://x.y?a=b""")
        assertEquals("Log in", q.terms[0].value)
        assertEquals(AttributeTerm.Operator.NOT_EQUALS, q.terms[1].operator)
        assertEquals("two words http://x.y?a=b", q.freeText)
        assertEquals("unterminated quote", FilterQuery.parse("text=\"abc").invalid.single().reason)
        assertTrue(FilterQuery.parse("enabled=maybe").invalid.isNotEmpty())
        assertTrue(FilterQuery.parse("enabled~tr").invalid.isNotEmpty())
        assertTrue(FilterQuery.parse("   ").isEmpty)
    }

    @Test
    fun `invalid terms do not filter`() {
        assertNull(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "=x enabled=maybe")))
        assertEquals(2, FilterCriteria(text = "=x enabled=maybe").invalidTerms.size)
    }

    @Test
    fun `type and class aliases work on iOS and Android`() {
        assertTrue(shownIos("type=Button").any { it.type == "Button" })
        assertTrue(shownIos("class=XCUIElementTypeButton").any { it.type == "Button" })
        assertTrue(shownIos("class~textfield").any { it.type == "TextField" })
        assertTrue(shownAndroid("type=Button").any { it.type == "Button" })
        assertTrue(shownAndroid("class=android.widget.Button").any { it.type == "Button" })
        val noButtons = shownIos("type!=Button type!=Application type!=Window")
        assertTrue(noButtons.isNotEmpty())
        assertTrue(noButtons.none { it.type == "Button" })
        assertTrue(noButtons.any { it.type == "TextField" })
    }

    @Test
    fun `extra boolean keys and aliases are validated and applied`() {
        assertTrue(FilterQuery.parse("interactive=true focusable=maybe").invalid.isNotEmpty())
        assertTrue(shownAndroid("interactive=true").isNotEmpty())
        assertEquals(shownAndroid("id=nothing-here"), shownAndroid("resourceid=nothing-here"))
        assertEquals(shownAndroid("text~a"), shownAndroid("desc~a"))
        assertEquals(shownAndroid("text~a"), shownAndroid("contentdesc~a"))
    }

    @Test
    fun `boolean aliases combine and missing counts as false`() {
        val clickable = shownAndroid("clickable=true").filter { it.attr("clickable") == "true" }
        assertTrue(clickable.isNotEmpty())
        assertTrue(shownAndroid("clickable=true").none { it.isRoot.not() && it.attr("clickable") == "false" && it.children.isEmpty() })
        assertTrue(shownIos("enabled=true visible=true").isNotEmpty())
        val hidden = ios.walk().first { it.attr("name") == "Hidden" }
        assertTrue(hidden in shownIos("visible=false"))
        assertTrue(hidden !in shownIos("visible=true"))
        assertTrue(shownIos("clickable=true").any { it.type == "Button" })
        assertTrue(shownAndroid("focused=true").isEmpty())
    }

    @Test
    fun `id and text aliases resolve through the accessors`() {
        val email = ios.walk().first { it.identifier == "email-input" }
        assertTrue(email in shownIos("id=EMAIL-input"))
        assertTrue(email in shownIos("identifier~mail"))
        assertTrue(email !in shownIos("id!=email-input"))
        val welcome = ios.walk().first { it.attr("value") == "Welcome" }
        assertTrue(welcome in shownIos("text=welcome"))
        assertTrue(welcome in shownIos("label~elco value=Welcome"))
        assertTrue(welcome !in shownIos("text=welcome id=email-input"))
        val androidWithId = android.walk().first { it.resourceId?.isNotEmpty() == true }
        val short = androidWithId.resourceId!!.substringAfter(":id/")
        assertTrue(androidWithId in shownAndroid("resource-id=$short"))
        assertTrue(androidWithId in shownAndroid("id=${androidWithId.resourceId}"))
    }

    @Test
    fun `unknown keys use the raw attribute map and free text still combines`() {
        assertTrue(shownAndroid("package=com.example.app").isNotEmpty())
        assertTrue(shownAndroid("package=other").isEmpty())
        assertTrue(shownAndroid("PACKAGE~example").isNotEmpty())
        assertTrue(shownAndroid("nosuchkey=x").isEmpty())
        assertTrue(shownAndroid("nosuchkey!=x").isNotEmpty())
        val email = ios.walk().first { it.identifier == "email-input" }
        assertTrue(email in shownIos("email type=TextField"))
        assertTrue(email !in shownIos("email type=Button"))
    }

    @Test
    fun `existing queries parse into one group`() {
        val q = FilterQuery.parse("log in clickable=true class~Button")
        assertEquals(1, q.groups.size)
        assertEquals("log in", q.groups[0].freeText)
        assertTrue(q.groups[0].notTerms.isEmpty() && q.groups[0].notFree.isEmpty() && q.groups[0].regexes.isEmpty())
        // lone dashes, bang-equals and words that merely contain OR or | stay as before
        assertEquals("a - b ORA x|y", FilterQuery.parse("a - b ORA x|y").freeText)
        assertEquals("or", FilterQuery.parse("or").freeText)
        assertEquals(listOf("!=x"), FilterQuery.parse("!=x").invalid.map { it.text })
        assertEquals("resource-id=a", FilterQuery.parse("resource-id=a").terms.single().toString())
    }

    @Test
    fun `OR shows elements of either group`() {
        val email = ios.walk().first { it.identifier == "email-input" }
        val welcome = ios.walk().first { it.attr("value") == "Welcome" }
        val either = shownIos("email-input OR welcome")
        assertTrue(email in either && welcome in either)
        assertTrue(email !in shownIos("welcome"))
        assertEquals(either, shownIos("email-input | welcome"))
        assertTrue(shownIos("type=Button OR type=TextField").let { s -> s.any { it.type == "Button" } && s.any { it.type == "TextField" } })
        assertEquals(shownIos("welcome"), shownIos("welcome OR"))
        assertEquals(2, FilterQuery.parse("a b OR c=d").groups.size)
        assertTrue(FilterQuery.parse("OR | OR").isEmpty)
    }

    @Test
    fun `leading dash or bang negates one word`() {
        val email = ios.walk().first { it.identifier == "email-input" }
        assertTrue(email !in shownIos("-email"))
        assertTrue(email !in shownIos("!email"))
        assertTrue(email in shownIos("type=TextField -button"))
        assertTrue(email !in shownIos("type=TextField -email"))
        assertTrue(shownIos("type=Button -type=Button").none { it.type == "Button" })
        assertTrue(shownIos("-type=Button").any { it.type == "TextField" })
        assertTrue(shownIos("-type=Button").none { it.type == "Button" })
        assertTrue(email !in shownIos("""-"email input" -email-input"""))
        assertEquals(listOf("two words"), FilterQuery.parse("""-"two words"""").groups.single().notFree)
        assertTrue(email in shownIos("-welcome type=TextField OR -type=TextField"))
    }

    @Test
    fun `regular expressions between slashes`() {
        val email = ios.walk().first { it.identifier == "email-input" }
        assertTrue(email in shownIos("/^email-.*put$/"))
        assertTrue(email in shownIos("/EMAIL/"))
        assertTrue(email !in shownIos("/^mail/"))
        assertTrue(email !in shownIos("-/email/"))
        assertTrue(email in shownIos("/email/ /input/"))
        assertTrue(email in shownIos(""""/email|welcome/""""))
        assertTrue(email in shownIos("/zzz/ OR /e.ail/"))
        assertTrue(FilterQuery.parse("/(/").invalid.single().reason.contains("regular expression"))
        assertNull(HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "/(/")))
        // too short to be a regex, so still free text
        assertEquals("//", FilterQuery.parse("//").freeText)
        assertEquals("/", FilterQuery.parse("/").freeText)
    }

    @Test
    fun `checkboxes keep working next to terms`() {
        val hidden = ios.walk().first { it.attr("name") == "Hidden" }
        assertTrue(hidden !in HierarchyFilter.visibleNodes(ios, FilterCriteria(text = "name~hidden", visibleOnly = true))!!)
        assertTrue(!FilterCriteria(text = "a=b").isEmpty)
        assertTrue(FilterCriteria(text = "=x").isEmpty)
    }
}
