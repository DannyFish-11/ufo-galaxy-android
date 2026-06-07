package com.ufo.galaxy.nlp

/**
 * Lightweight local registries for common alias resolution.
 *
 * All mappings are static, rule-based, and deterministic — no external NLP dependencies.
 * Lookups are case-insensitive (keys stored lowercase). Three alias domains are provided:
 *
 * 1. [APP_ALIASES]        — common app name variants → canonical app name.
 * 2. [ACTION_ALIASES]     — action verb synonyms → canonical action verb.
 * 3. [UI_TARGET_ALIASES]  — UI element shorthand → full descriptor.
 *
 * Convenience resolution methods ([resolveApp], [resolveAction], [resolveUiTarget]) accept
 * any case and return the canonical form, or the original string if no match is found.
 */
object AppAliasRegistry {

    /**
     * Maps common app-name variants (lowercase key) to their canonical display names.
     *
     * Examples: "yt" → "YouTube", "fb" → "Facebook", "wa" → "WhatsApp"
     */
    val APP_ALIASES: Map<String, String> = mapOf(
        "yt" to "YouTube",
        "youtube" to "YouTube",
        "fb" to "Facebook",
        "facebook" to "Facebook",
        "ig" to "Instagram",
        "insta" to "Instagram",
        "instagram" to "Instagram",
        "wa" to "WhatsApp",
        "whatsapp" to "WhatsApp",
        "wechat" to "WeChat",
        "wx" to "WeChat",
        "gmaps" to "Google Maps",
        "gmap" to "Google Maps",
        "google maps" to "Google Maps",
        "gmail" to "Gmail",
        "chrome" to "Chrome",
        "settings" to "Settings",
        "setting" to "Settings",
        "camera" to "Camera",
        "photos" to "Google Photos",
        "gallery" to "Gallery",
        "dialer" to "Phone",
        "contacts" to "Contacts",
        "sms" to "Messages",
        "calendar" to "Calendar",
        "alarm" to "Clock",
        "calc" to "Calculator",
        "calculator" to "Calculator",
        "spotify" to "Spotify",
        "netflix" to "Netflix",
        "twitter" to "Twitter",
        "tiktok" to "TikTok",
        "telegram" to "Telegram",
        "tg" to "Telegram",
        "slack" to "Slack",
        "zoom" to "Zoom",
        "playstore" to "Play Store",
        "play store" to "Play Store",
        "google play" to "Play Store",
        "notepad" to "Notes",
        "google drive" to "Google Drive",
        "drive" to "Google Drive",
        "google docs" to "Google Docs",
        "google sheets" to "Google Sheets",
        "google translate" to "Google Translate",
        "translate" to "Google Translate",
        "uber" to "Uber",
        "lyft" to "Lyft",
        "amazon" to "Amazon",
        "reddit" to "Reddit",
        "linkedin" to "LinkedIn"
    )

    /**
     * Maps common action-verb synonyms (lowercase key) to their canonical verb form.
     *
     * Examples: "launch" → "open", "go back" → "back", "click" → "tap"
     */
    val ACTION_ALIASES: Map<String, String> = mapOf(
        "launch" to "open",
        "start" to "open",
        "run" to "open",
        "fire up" to "open",
        "pull up" to "open",
        "bring up" to "open",
        "navigate to" to "go to",
        "navigate back" to "back",
        "go back" to "back",
        "press back" to "back",
        "hit back" to "back",
        "return to home" to "home",
        "go home" to "home",
        "press home" to "home",
        "hit home" to "home",
        "return home" to "home",
        "search for" to "search",
        "look for" to "search",
        "type in" to "type",
        "click" to "tap",
        "press" to "tap",
        "touch" to "tap",
        "hit" to "tap",
        "select" to "tap",
        "choose" to "tap",
        "swipe up" to "scroll up",
        "swipe down" to "scroll down",
        "swipe left" to "scroll left",
        "swipe right" to "scroll right",
        "swipe" to "scroll"
    )

    /**
     * Maps common UI element shorthand terms (lowercase key) to their canonical descriptors.
     *
     * Examples: "btn" → "button", "img" → "image", "fab" → "floating action button"
     */
    val UI_TARGET_ALIASES: Map<String, String> = mapOf(
        "btn" to "button",
        "img" to "image",
        "pic" to "image",
        "navbar" to "navigation bar",
        "nav bar" to "navigation bar",
        "searchbar" to "search bar",
        "search bar" to "search bar",
        "textfield" to "text field",
        "text field" to "text field",
        "input field" to "text field",
        "check box" to "check box",
        "checkbox" to "check box",
        "dropdown" to "drop-down menu",
        "drop down" to "drop-down menu",
        "popup" to "pop-up",
        "pop up" to "pop-up",
        "modal" to "dialog",
        "tab bar" to "tab bar",
        "tabbar" to "tab bar",
        "fab" to "floating action button",
        "hamburger" to "menu button",
        "menu icon" to "menu button",
        "kebab menu" to "overflow menu",
        "three dots" to "overflow menu",
        "overflow" to "overflow menu",
        "back btn" to "back button",
        "close btn" to "close button",
        "send btn" to "send button",
        "submit btn" to "submit button",
        "ok btn" to "OK button",
        "ok button" to "OK button",
        "cancel btn" to "cancel button",
        "confirm btn" to "confirm button",
        "done btn" to "done button"
    )

    /**
     * Resolves [appName] to its canonical display name, or returns [appName] unchanged
     * when no alias is registered. Lookup is case-insensitive.
     */
    fun resolveApp(appName: String): String =
        APP_ALIASES[appName.lowercase().trim()] ?: appName

    /**
     * Resolves [action] to its canonical verb form, or returns [action] unchanged
     * when no alias is registered. Lookup is case-insensitive.
     */
    fun resolveAction(action: String): String =
        ACTION_ALIASES[action.lowercase().trim()] ?: action

    /**
     * Resolves [target] to its canonical UI element descriptor, or returns [target] unchanged
     * when no alias is registered. Lookup is case-insensitive.
     */
    fun resolveUiTarget(target: String): String =
        UI_TARGET_ALIASES[target.lowercase().trim()] ?: target
}
