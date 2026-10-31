package io.embodit.dronesight.ui.theme

import androidx.compose.ui.graphics.Color

/** The design's colours (design-package `palette`). The only file with colour literals. */
object DsColors {
    // ColorScheme roles the design overrides (palette.colorScheme)
    val Interaction = Color(0xFFFFB53D) // design-style: fixed-figma-color main button / stick knob / next gate
    val Passable = Color(0xFF4FD18B) // design-style: fixed-figma-color in the box / passed
    val Alert = Color(0xFFFF8A3D) // design-style: fixed-figma-color out of the box / almost (fill only)
    val Error = Color(0xFFFF6B6B) // design-style: fixed-figma-color hard landing / again / quit (fill only)

    // dark ink on the interaction / passable / alert / error fills (palette.brandTokens.onInteraction / onPassable)
    val OnFill = Color(0xFF1E2A33) // design-style: fixed-figma-color ink on light fills (12:1 / 9:1)

    // status pills, tags: opaque dark slate with light text (palette.brandTokens.tagBg)
    val TagBg = Color(0xFF1E2A33) // design-style: fixed-figma-color tag background
    val TagInk = Color(0xFFE6F1F7) // design-style: fixed-figma-color tag text
    val WarnBg = Color(0xFF4A2E0A) // design-style: fixed-figma-color chipWarn background
    val WarnInk = Color(0xFFFFD9A8) // design-style: fixed-figma-color chipWarn text
    val ErrBg = Color(0xFF4A1414) // design-style: fixed-figma-color chipError background
    val ErrInk = Color(0xFFFFC5C5) // design-style: fixed-figma-color chipError text

    // 3D (palette.brandTokens)
    val SkyBlue = Color(0xFF2B7FD9) // design-style: fixed-figma-color lesson pictograms
    val PadOrange = Color(0xFFFF7A1A) // design-style: fixed-figma-color pad ring / pole stripes / wind sock
    val PadWhite = Color(0xFFF4F4F0) // design-style: fixed-figma-color pad / pole white
    val ArmRed = Color(0xFFFF3B30) // design-style: fixed-figma-color front arm lights
    val ArmGreen = Color(0xFF34C759) // design-style: fixed-figma-color rear arm lights
    val FrameWhite = Color(0xFFFFFFFF) // design-style: fixed-figma-color box / gates / bounds / height line
    val Shadow = Color(0xFF000000) // design-style: fixed-figma-color ground shadow
    val PadInk = Color(0xFF15181C) // design-style: fixed-figma-color the H on the pad
}
