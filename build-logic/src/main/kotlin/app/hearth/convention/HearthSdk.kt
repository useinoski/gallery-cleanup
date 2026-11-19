package app.hearth.convention

/**
 * SDK levels for every Android module.
 * Play requires target API 36 for new apps from 31 Aug 2026, and this project
 * compiles against that same level so a dependency cannot silently require 37.
 */
object HearthSdk {
    const val COMPILE_SDK = 36
    const val MIN_SDK = 30
    const val TARGET_SDK = 36
}
