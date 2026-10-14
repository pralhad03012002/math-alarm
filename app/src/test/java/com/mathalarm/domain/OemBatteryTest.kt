package com.mathalarm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OEM intent selection (BUILD_SPECS §4).
 *
 * This is the one half of the battery flow that can be tested off-device: whether a given
 * `Build.MANUFACTURER`/`Build.BRAND` maps to the right vendor and the right candidate activities.
 * Whether those activities *exist* is a device fact, which is exactly why `SettingsIntents` checks
 * resolvability before launching and wraps the launch anyway.
 */
class OemBatteryTest {

    @Test
    fun `each named manufacturer maps to its own vendor`() {
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("Xiaomi"))
        assertEquals(OemBattery.Vendor.OPPO, OemBattery.vendorOf("OPPO"))
        assertEquals(OemBattery.Vendor.VIVO, OemBattery.vendorOf("vivo"))
        assertEquals(OemBattery.Vendor.HUAWEI, OemBattery.vendorOf("HUAWEI"))
        assertEquals(OemBattery.Vendor.SAMSUNG, OemBattery.vendorOf("samsung"))
    }

    @Test
    fun `detection is case insensitive`() {
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("XIAOMI"))
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("xiaomi"))
        assertEquals(OemBattery.Vendor.SAMSUNG, OemBattery.vendorOf("SaMsUnG"))
    }

    @Test
    fun `sub-brands map to the ROM family they actually ship`() {
        // MIUI / HyperOS.
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("Redmi"))
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("POCO"))
        // ColorOS.
        assertEquals(OemBattery.Vendor.OPPO, OemBattery.vendorOf("realme"))
        assertEquals(OemBattery.Vendor.OPPO, OemBattery.vendorOf("OnePlus"))
        // Funtouch / OriginOS.
        assertEquals(OemBattery.Vendor.VIVO, OemBattery.vendorOf("iQOO"))
        // EMUI-derived.
        assertEquals(OemBattery.Vendor.HUAWEI, OemBattery.vendorOf("HONOR"))
    }

    @Test
    fun `the brand is consulted when the manufacturer is generic`() {
        assertEquals(OemBattery.Vendor.XIAOMI, OemBattery.vendorOf("unknown", "Redmi"))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf("unknown", "unknown"))
    }

    @Test
    fun `an unknown or absent manufacturer is stock, not a crash`() {
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf("Google"))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf("Nothing", "Nothing Phone"))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf(null))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf(null, null))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf(""))
        assertEquals(OemBattery.Vendor.STOCK, OemBattery.vendorOf("   ", "  "))
    }

    @Test
    fun `every named vendor offers at least one target and stock offers none`() {
        OemBattery.Vendor.entries
            .filter { it != OemBattery.Vendor.STOCK }
            .forEach { vendor ->
                assertTrue(
                    "$vendor should have at least one candidate activity",
                    OemBattery.targetsFor(vendor).isNotEmpty(),
                )
            }
        // STOCK deliberately has none: the stock battery request and the app settings page are the
        // fallback and are not vendor-specific.
        assertTrue(OemBattery.targetsFor(OemBattery.Vendor.STOCK).isEmpty())
    }

    @Test
    fun `every target names a real looking package and class`() {
        OemBattery.Vendor.entries.flatMap { OemBattery.targetsFor(it) }.forEach { target ->
            assertTrue("blank package", target.packageName.isNotBlank())
            assertTrue("blank class", target.className.isNotBlank())
            assertTrue("package is not a package: ${target.packageName}", "." in target.packageName)
            assertTrue("class is not a class: ${target.className}", "." in target.className)
            // A ComponentName built from a relative class name would not resolve, so these must be
            // fully qualified rather than the ".Activity" shorthand the manifest allows.
            assertFalse(
                "class name must be fully qualified: ${target.className}",
                target.className.startsWith("."),
            )
        }
    }

    @Test
    fun `no two vendors share a target`() {
        val all = OemBattery.Vendor.entries.flatMap { OemBattery.targetsFor(it) }
        assertEquals(
            "a duplicated target means one vendor's deep link is being offered to another",
            all.size,
            all.toSet().size,
        )
    }

    @Test
    fun `allPackages covers every target's package`() {
        val fromTargets = OemBattery.Vendor.entries
            .flatMap { OemBattery.targetsFor(it) }
            .map { it.packageName }
            .toSet()
        // This set is what the manifest `<queries>` block has to contain; a target whose package is
        // missing there could never be resolved on API 30+.
        assertEquals(fromTargets, OemBattery.allPackages())
    }

    @Test
    fun `the first xiaomi target is autostart, which is the one that matters on MIUI`() {
        val first = OemBattery.targetsFor(OemBattery.Vendor.XIAOMI).first()
        assertEquals("com.miui.securitycenter", first.packageName)
        assertTrue(first.className.contains("AutoStart"))
    }
}
