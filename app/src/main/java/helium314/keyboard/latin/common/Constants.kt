// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.common

object Links {
    const val DICTIONARY_URL = "https://codeberg.org/Helium314/aosp-dictionaries"
    const val DICTIONARY_DOWNLOAD_SUFFIX = "/raw/branch/main/"
    const val DICTIONARY_NORMAL_SUFFIX = "dictionaries/"
    const val DICTIONARY_EXPERIMENTAL_SUFFIX = "dictionaries_experimental/"
    const val DICTIONARY_EMOJI_CLDR_SUFFIX = "emoji_cldr_signal_dictionaries/"
    /** This fork. Everything the About screen offers as "this app" points here. */
    const val GITHUB = "https://github.com/leinss/TobiBoard"
    const val LICENSE = "$GITHUB/blob/main/LICENSE"
    const val DOCS_URL = "$GITHUB#readme"
    /**
     * HeliBoard, the upstream project. TobiBoard has neither a wiki nor discussions enabled, so
     * the layout, custom-colour and gesture-data links keep pointing at the pages that hold that
     * content. Do not repoint them at [GITHUB] until the equivalent pages exist here.
     */
    const val UPSTREAM_GITHUB = "https://github.com/HeliBorg/HeliBoard"
    const val WIKI_URL = "$UPSTREAM_GITHUB/wiki"
    const val LAYOUT_WIKI_URL = "$WIKI_URL/2.-Layouts"
    const val CUSTOM_LAYOUTS = "$UPSTREAM_GITHUB/discussions/categories/custom-layout"
    const val CUSTOM_COLORS = "$UPSTREAM_GITHUB/discussions/categories/custom-colors"
    const val GESTURE_DATA_VIDEO_PEERTUBE = "https://makertube.net/w/cQECfDkuLGR9eUQquUEo4K"
    const val GESTURE_DATA_VIDEO_YOUTUBE = "https://youtu.be/CyjumVTWtJA"
    const val SWIPE_O_SCOPE = "https://codeberg.org/eclexic/swipe-o-scope"
    const val GESTURE_DATA_WIKI = "$WIKI_URL/Tutorial:-How-to-Contribute-Gesture-Data"
}

val combiningRange = 0x300..0x35b
