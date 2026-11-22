package com.mja.reyamf.common.model

data class Config(
    var reduceDPI: Int = 50,
    /*
     * VIRTUAL_DISPLAY_FLAG_SECURE                          1 << 2
     * VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY                1 << 3
     * VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT            1 << 7
     * VIRTUAL_DISPLAY_FLAG_TRUSTED                         1 << 10
     *
     * OWN_CONTENT_ONLY is required: without it the virtual display mirrors the
     * default display (home screen + taskbar + navigation bar) for the first
     * few seconds until the target task is moved onto it.
     * SHOULD_SHOW_SYSTEM_DECORATIONS (1 << 9) is intentionally OFF: it renders
     * the system status/navigation bars inside the window, which are not
     * interactive and confuse users.
     */
    var flags: Int = 1164,
    var coloredController: Boolean = false,
    /*
     * 0: move task only
     * 1: start activity only
     * 2: move task, failback to start activity
    */
    var windowfy: Int = 0,
    /*
     * 0: TextureView
     * 1: SurfaceView
    */
    var surfaceView: Int = 1,
    var recentBackHome: Boolean = false,
    var showImeInWindow: Boolean = false,
    var defaultWindowWidth: Int = 280,
    var defaultWindowHeight: Int = 380,
    var hookLauncher: HookLauncher = HookLauncher(),
    var showForceShowIME: Boolean = false,
    var portraitY: Int = 0,
    var landscapeY: Int = 0,
    var favApps: MutableList<FavApps> = mutableListOf(),
    var launchSideBarAtBoot: Boolean = false,
    var enableSidebar: Boolean = true,
    var sidebarTransparency: Int = 80,
    var windowRoundedCorner: Int = 20,
    var sidebarPosition: Boolean = false,
    var animationSpeed: Float = 5100f
) {
    data class HookLauncher(
        var hookRecents: Boolean = true,
        var hookTaskbar: Boolean = true,
        var hookPopup: Boolean = true,
        var hookTransientTaskbar: Boolean = false,
    )
}