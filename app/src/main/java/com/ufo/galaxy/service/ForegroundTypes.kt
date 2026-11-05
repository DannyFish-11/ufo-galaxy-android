package com.ufo.galaxy.service

/**
 * 常驻连接服务([GalaxyConnectionService])在 Android 14+ 以什么前台服务类型起。
 *
 * 智能体问「我手机在哪」时,手机多半在兜里、Galaxy 在后台。Android 10 起后台读位置只有两条路:用户给了
 * 「始终允许」,或者由一个**类型含 location、并且是在应用可见时起的**前台服务来读。Android 14 起,在后台以
 * location 类型起前台服务会直接抛 `SecurityException` —— 粘性重启、开机自启都算后台。所以:
 *  * 只在应用自己的界面拉起服务(或回到界面补一次)、并且已给位置权限时,才带上 location;
 *  * 其余情况只用 specialUse:照常常驻,只是后台读不到位置(位置动作回原因,不崩)。
 *
 * 纯 Kotlin,单测直接跑。
 */
object ForegroundTypes {

    enum class Kind { SPECIAL_USE, LOCATION }

    /** 这次起服务要的类型。 */
    fun forConnection(locationGranted: Boolean, fromVisibleUi: Boolean): Set<Kind> =
        if (locationGranted && fromVisibleUi) setOf(Kind.SPECIAL_USE, Kind.LOCATION) else PLAIN

    /** 带 location 起不来时退回的类型。 */
    val PLAIN: Set<Kind> = setOf(Kind.SPECIAL_USE)

    /**
     * 回到界面时要不要从界面再起一次服务,把 location 补上:位置权限已给、服务在跑([running] 不为 null)、
     * 却还没带 location(权限是服务起来之后才给的,或服务是在后台重启的)。
     */
    fun needsUpgrade(locationGranted: Boolean, running: Set<Kind>?): Boolean =
        locationGranted && running != null && Kind.LOCATION !in running
}
