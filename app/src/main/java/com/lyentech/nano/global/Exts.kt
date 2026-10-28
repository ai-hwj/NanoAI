package com.lyentech.nano.global

import com.google.adk.kt.events.Event

/**
 * Concatenates this event's visible response text: the text of every non-thought part of its
 * content, in order, joined with no separator (empty when there is no such text).
 *
 * This mirrors how core ADK reconstructs a response's text from its parts (see
 * `LlmAgent.maybeSaveOutputToState`): thought parts are excluded, and parts are joined directly
 * (not space-separated), because they are fragments of one continuous message — a separator would
 * inject spurious spaces, including mid-word across streaming chunks. Core keeps this logic
 * private, so the examples re-expose it here as a small shared helper.
 */
fun Event.foldTextParts(): String =
    content?.parts.orEmpty().filter { it.thought != true }.mapNotNull { it.text }.joinToString("")

/**
 * Concatenates this event's thought text: the text of every part marked as a thought, in order,
 * joined directly (empty when the event carries none).
 *
 * The counterpart to [foldTextParts], and joined the same way and for the same reason: the parts
 * are fragments of one continuous thought, so a separator injects breaks the model did not write.
 */
internal fun Event.foldThoughtParts(): String =
    content?.parts.orEmpty().filter { it.thought == true }.mapNotNull { it.text }.joinToString("")