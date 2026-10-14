package com.mathalarm.domain

/**
 * Which OEM battery manager is standing between this app and a 6am alarm, and which settings
 * activity to send the user to.
 *
 * **Pure Kotlin, no Android imports**, so every mapping here is unit-testable on the JVM. The caller
 * supplies `Build.MANUFACTURER` / `Build.BRAND` as plain strings and receives a [Vendor] plus an
 * ordered list of explicit [Target] activities to try.
 *
 * This is BRAINSTORM §6's "#1 source of one-star reviews for every alarm app" surface:
 * Xiaomi/MIUI, Oppo/ColorOS, Vivo/FuntouchOS, Huawei/EMUI and Samsung all kill background
 * processes in undocumented, ROM-version-specific ways, and the only remedy is to walk the user
 * into the right whitelist screen.
 *
 * **These components do not exist on most devices and change between ROM versions.** That is the
 * whole reason this returns a *list* and the whole reason the caller must check resolvability before
 * launching and fall back to written instructions. Nothing here may ever be launched blind.
 */
object OemBattery {

    enum class Vendor {
        XIAOMI,
        OPPO,
        VIVO,
        HUAWEI,
        SAMSUNG,

        /** Stock Android, or an OEM we have no specific screen for. */
        STOCK,
    }

    /** One explicit activity to try, as a package/class pair. */
    data class Target(val packageName: String, val className: String)

    /**
     * Keyword → vendor, matched as a case-insensitive substring of `MANUFACTURER` or `BRAND`, in
     * this order. Sub-brands are mapped to the ROM family they actually ship:
     * Redmi/POCO are MIUI/HyperOS, realme and OnePlus are ColorOS, iQOO is Funtouch/OriginOS, and
     * Honor's MagicOS is EMUI-derived and still ships the same `com.huawei.systemmanager` screens on
     * many models. A wrong guess costs nothing: the target simply fails to resolve and the user gets
     * the stock route plus written instructions.
     */
    private val KEYWORDS: List<Pair<String, Vendor>> = listOf(
        "xiaomi" to Vendor.XIAOMI,
        "redmi" to Vendor.XIAOMI,
        "poco" to Vendor.XIAOMI,
        "oppo" to Vendor.OPPO,
        "realme" to Vendor.OPPO,
        "oneplus" to Vendor.OPPO,
        "vivo" to Vendor.VIVO,
        "iqoo" to Vendor.VIVO,
        "huawei" to Vendor.HUAWEI,
        "honor" to Vendor.HUAWEI,
        "samsung" to Vendor.SAMSUNG,
    )

    /** [manufacturer] and [brand] are `Build.MANUFACTURER` / `Build.BRAND`; either may be null. */
    fun vendorOf(manufacturer: String?, brand: String? = null): Vendor {
        val haystack = listOfNotNull(manufacturer, brand)
            .joinToString(" ")
            .lowercase()
        if (haystack.isBlank()) return Vendor.STOCK
        return KEYWORDS.firstOrNull { (keyword, _) -> keyword in haystack }?.second ?: Vendor.STOCK
    }

    /**
     * Activities to try for [vendor], most useful first. Empty for [Vendor.STOCK], which has no
     * OEM screen — the stock battery-optimization request and the app's own settings page are the
     * caller's fallback and are not vendor-specific.
     */
    fun targetsFor(vendor: Vendor): List<Target> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            // Autostart is the one that actually matters on MIUI; the power-keeper screen is where
            // "no restrictions" lives.
            Target("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            Target("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
            Target("com.miui.securitycenter", "com.miui.securityscan.MainActivity"),
        )
        Vendor.OPPO -> listOf(
            Target("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Target("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            Target("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            Target("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
        )
        Vendor.VIVO -> listOf(
            Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            Target("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Target("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        )
        Vendor.HUAWEI -> listOf(
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            Target("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        Vendor.SAMSUNG -> listOf(
            // Device Care. The package moved between One UI versions, hence both spellings.
            Target("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            Target("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            Target("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        Vendor.STOCK -> emptyList()
    }

    /**
     * The packages [targetsFor] can name, for the manifest `<queries>` block. On API 30+ an explicit
     * component in another package cannot even be *resolved* without declaring visibility of it, so
     * without these the resolvability check would report "absent" on every device and the deep links
     * would never be offered at all.
     */
    fun allPackages(): Set<String> =
        Vendor.entries.flatMapTo(LinkedHashSet()) { v -> targetsFor(v).map { it.packageName } }
}
