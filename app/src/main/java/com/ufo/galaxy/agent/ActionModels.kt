package com.ufo.galaxy.agent

/**
 * Intent for a single planned action step produced by the local planner.
 * Contains no screen coordinates; those are resolved by the grounding engine.
 *
 * @param action_type Symbolic action: "tap" | "scroll" | "type" | "open_app" | "back" | "home".
 * @param intent      Natural-language description forwarded to the grounding engine.
 * @param parameters  Action-specific parameters (e.g., "text" for type, "direction" for scroll).
 */
data class ActionIntent(
    val action_type: String,
    val intent: String,
    val parameters: Map<String, String> = emptyMap()
)

/**
 * Action with screen coordinates resolved locally by the grounding engine.
 * Coordinates are produced exclusively on-device; they never originate from the cloud.
 *
 * @param action_type         Symbolic action name matching [ActionIntent.action_type].
 * @param x                   Horizontal pixel coordinate resolved by grounding.
 * @param y                   Vertical pixel coordinate resolved by grounding.
 * @param confidence          Grounding model confidence score in [0.0, 1.0].
 * @param element_description Human-readable label of the matched UI element.
 * @param parameters          Action-specific parameters carried forward from [ActionIntent].
 */
data class GroundedAction(
    val action_type: String,
    val x: Int,
    val y: Int,
    val confidence: Float,
    val element_description: String,
    val parameters: Map<String, String> = emptyMap()
)
