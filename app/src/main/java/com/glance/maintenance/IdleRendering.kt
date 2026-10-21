package com.glance.maintenance

/**
 * How the idle screen's WebView is drawn.
 *
 * On a 3 GB Huawei tablet with Android 8 the app's native heap grows for as long as the idle
 * slideshow plays, outside Chromium's own allocators, and only a process restart gives it back.
 * The drawing path decides which GPU driver work every new photo causes, so it is selectable
 * here to measure, and possibly avoid, that growth on such devices.
 */
enum class IdleRendering(val key: String) {
    /** Into an offscreen hardware layer that is then composited; Glance's original behaviour. */
    GPU_LAYER("gpu_layer"),

    /** Straight onto the window by the GPU, Android's default for a WebView. */
    GPU("gpu"),

    /** By the CPU into a software layer; no GPU texture per photo, more CPU per frame. */
    SOFTWARE("software");

    companion object {
        val DEFAULT = GPU_LAYER

        fun fromKey(key: String?): IdleRendering = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
