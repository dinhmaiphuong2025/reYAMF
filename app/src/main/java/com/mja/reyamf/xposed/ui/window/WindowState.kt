package com.mja.reyamf.xposed.ui.window

/**
 * A single floating window's lifecycle state.
 *
 * [NORMAL]     standard floating window (3 control zones).
 * [MINI]       shrunken content-only window snapped to a corner.
 * [EDGE_HIDE]  hidden as a thin semi-circular tab stuck to the screen edge.
 * [FULLSCREEN] task moved back to the default display; VirtualDisplay released.
 */
enum class WindowState {
    NORMAL,
    MINI,
    EDGE_HIDE,
    FULLSCREEN
}
