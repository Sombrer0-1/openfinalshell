package io.github.openfinalshell.android.core.session

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SessionFileStateTest {
    @Test
    fun `a late listing does not replace a newer navigation`() {
        val started = beginNavigation(SessionFileState(confirmedPath = "/"), "/etc")
        val newer = beginNavigation(started.first, "/var")
        val stale = NavigationRequest("s", "/etc", started.second)
        assertNull(applyListing(newer.first, stale, "/etc"))
        val applied = applyListing(newer.first, NavigationRequest("s", "/var", newer.second), "/var")
        assertEquals("/var", applied?.confirmedPath)
        assertEquals(listOf("/"), applied?.backStack)
    }

    @Test
    fun `back and forward keep the confirmed path out of the write target until the listing returns`() {
        val applied = applyListing(
            SessionFileState(confirmedPath = "/", generation = 1),
            NavigationRequest("s", "/etc", 1),
            "/etc"
        )!!
        val (backing, path) = goBack(applied)!!
        assertEquals("/", path)
        assertEquals("/etc", backing.forwardStack.last())
        assertEquals("/etc", backing.confirmedPath)
    }
}
