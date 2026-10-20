package dev.zayn.vlive.perm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM test (`app/src/test`), no device and no Robolectric: [PermissionGate] reads its
 * state only through the injected `() -> Boolean`, so nothing here touches an Android class.
 */
class PermissionGateTest {
    @Test fun `reflects injected state`() {
        assertFalse(PermissionGate { false }.granted())
        assertTrue(PermissionGate { true }.granted())
    }

    // Negative control: if granted() cached the provider result at construction
    // (`private val value = isManager()`), the second assertion would stay false and this fails.
    @Test fun `granted delegates to the provider on every call`() {
        var state = false
        val gate = PermissionGate { state }
        assertFalse(gate.granted())
        state = true
        assertTrue(gate.granted())
    }
}
