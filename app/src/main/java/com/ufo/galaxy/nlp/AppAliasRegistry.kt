package com.ufo.galaxy.nlp

/**
 * Local alias registries for app names, action verb synonyms, and UI target synonyms.
 *
 * All lookups are case-insensitive. The canonical form returned is always lowercase.
 * Resolution is deterministic and requires no external dependencies.
 *
 * ### App name aliases
 * Maps common alternative names (including Chinese app names) to a stable canonical
 * English identifier used by the planner.
 *
 * ### Action verb synonyms
 * Maps synonym verbs to a canonical action verb so that the planner prompt is
 * consistent regardless of how the user phrased the command.
 *
 * ### UI target synonyms
 * Maps common UI element aliases to a stable descriptor.
 */
object AppAliasRegistry {

    // ── App name aliases ──────────────────────────────────────────────────────

    /**
     * Maps alternative app names (variants, transliterations, Chinese names) to a
     * canonical lowercase English app name.
     */
    private val appAliases: Map<String, String> = mapOf(
        // WeChat
        "微信" to "wechat",
        "weixin" to "wechat",
        "wechat" to "wechat",
        // Alipay
        "支付宝" to "alipay",
        "alipay" to "alipay",
        // TikTok / Douyin
        "抖音" to "tiktok",
        "douyin" to "tiktok",
        "tiktok" to "tiktok",
        // Taobao
        "淘宝" to "taobao",
        "taobao" to "taobao",
        // Weibo
        "微博" to "weibo",
        "weibo" to "weibo",
        // QQ
        "qq" to "qq",
        // Baidu
        "百度" to "baidu",
        "baidu" to "baidu",
        // Camera
        "相机" to "camera",
        "camera" to "camera",
        // Gallery / Photos
        "相册" to "gallery",
        "图库" to "gallery",
        "photos" to "gallery",
        "gallery" to "gallery",
        // Settings
        "设置" to "settings",
        "settings" to "settings",
        "setting" to "settings",
        // Clock / Alarm
        "时钟" to "clock",
        "闹钟" to "clock",
        "alarm" to "clock",
        "clock" to "clock",
        // Calendar
        "日历" to "calendar",
        "calendar" to "calendar",
        // Maps
        "地图" to "maps",
        "maps" to "maps",
        // Browser
        "浏览器" to "browser",
        "browser" to "browser",
        // Contacts
        "联系人" to "contacts",
        "contacts" to "contacts",
        // Phone / Dialer
        "电话" to "phone",
        "拨号" to "phone",
        "phone" to "phone",
        "dialer" to "phone",
        // Messages / SMS
        "短信" to "messages",
        "messages" to "messages",
        "sms" to "messages",
        // Email
        "邮件" to "email",
        "email" to "email",
        "mail" to "email",
        // Music
        "音乐" to "music",
        "music" to "music",
        // Calculator
        "计算器" to "calculator",
        "calculator" to "calculator",
        // File Manager
        "文件管理" to "files",
        "files" to "files",
        "file manager" to "files",
        // Notes
        "备忘录" to "notes",
        "notes" to "notes",
        // Netflix
        "netflix" to "netflix",
        // YouTube
        "youtube" to "youtube",
        // Instagram
        "instagram" to "instagram",
        // Twitter / X
        "twitter" to "twitter",
        "x" to "twitter",
        // Facebook
        "facebook" to "facebook",
        // WhatsApp
        "whatsapp" to "whatsapp"
    )

    // ── Action verb synonyms ──────────────────────────────────────────────────

    /**
     * Maps synonym action verbs to a canonical verb used in prompts.
     * Keys are complete phrases or single words, all lowercase.
     */
    private val actionSynonyms: Map<String, String> = mapOf(
        // "open" synonyms
        "launch" to "open",
        "start" to "open",
        "run" to "open",
        "go to" to "open",
        "navigate to" to "open",
        "bring up" to "open",
        // "back" synonyms
        "go back" to "back",
        "press back" to "back",
        "return" to "back",
        // "home" synonyms
        "go home" to "home",
        "return home" to "home",
        "back to home" to "home",
        "back to homescreen" to "home",
        "go to home screen" to "home",
        "回首页" to "home",
        "返回桌面" to "home",
        // "tap" synonyms
        "click" to "tap",
        "press" to "tap",
        "select" to "tap",
        "choose" to "tap",
        "hit" to "tap",
        // "type" synonyms
        "input" to "type",
        "write" to "type",
        "enter" to "type",
        // "scroll" synonyms
        "swipe" to "scroll",
        "slide" to "scroll",
        // "search" synonyms
        "look up" to "search",
        "find" to "search",
        "look for" to "search"
    )

    // ── UI target synonyms ────────────────────────────────────────────────────

    /**
     * Maps common UI element aliases to a stable canonical descriptor.
     */
    private val uiTargetSynonyms: Map<String, String> = mapOf(
        "search bar" to "search field",
        "search box" to "search field",
        "searchbar" to "search field",
        "search input" to "search field",
        "text box" to "text field",
        "textbox" to "text field",
        "input box" to "text field",
        "input field" to "text field",
        "ok button" to "confirm button",
        "okay button" to "confirm button",
        "submit button" to "confirm button",
        "yes button" to "confirm button",
        "back button" to "back",
        "home button" to "home",
        "hamburger menu" to "menu",
        "nav menu" to "menu",
        "navigation menu" to "menu",
        "top bar" to "toolbar",
        "action bar" to "toolbar",
        "nav bar" to "navigation bar"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolves [name] to its canonical app name.
     * Returns the lowercased canonical name if found, or the original [name] unchanged.
     */
    fun resolveAppAlias(name: String): String =
        appAliases[name.lowercase()] ?: name

    /**
     * Resolves [verb] to its canonical action verb.
     * Performs a case-insensitive lookup; returns [verb] unchanged when no match found.
     */
    fun resolveActionSynonym(verb: String): String =
        actionSynonyms[verb.lowercase()] ?: verb

    /**
     * Resolves [target] to its canonical UI target descriptor.
     * Returns [target] unchanged when no match found.
     */
    fun resolveUiTargetSynonym(target: String): String =
        uiTargetSynonyms[target.lowercase()] ?: target

    /**
     * Returns all registered app alias keys (for testing and diagnostics).
     */
    internal fun appAliasKeys(): Set<String> = appAliases.keys

    /**
     * Returns all registered action synonym keys (for testing and diagnostics).
     */
    internal fun actionSynonymKeys(): Set<String> = actionSynonyms.keys
}
