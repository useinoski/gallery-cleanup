package com.useinoski.gallerycleanup.detection

import android.content.Context

/**
 * Notices the app can show later. [ASSET_PATH] is the long form, including the
 * Apache License 2.0 text, merged into the app's assets when the app depends
 * on this module. [components] is the same list for a screen that does not
 * want to parse that file.
 */
object ThirdPartyNotices {
    const val ASSET_PATH = "open_source_notices.txt"

    val components: List<Component> = listOf(
        Component(
            name = "Marqo nsfw-image-detection-384",
            license = "Apache License 2.0",
            url = "https://huggingface.co/Marqo/nsfw-image-detection-384",
            summary = "On-device image classifier. Code and weights are Apache-2.0. " +
                "This app ships an int8 LiteRT conversion. The training images are not included.",
        ),
        Component(
            name = "Google ML Kit face detection (bundled)",
            license = "ML Kit Terms of Service",
            url = "https://developers.google.com/ml-kit/terms",
            summary = "Bundled face detector, artifact com.google.mlkit:face-detection:16.1.7. " +
                "Used for a face count only. No face is identified.",
        ),
        Component(
            name = "Google ML Kit image labeling (bundled)",
            license = "ML Kit Terms of Service",
            url = "https://developers.google.com/ml-kit/terms",
            summary = "Bundled image labeler, artifact com.google.mlkit:image-labeling:17.0.9. " +
                "Experimental alcohol, gambling, and optional meme hints.",
        ),
        Component(
            name = "LiteRT",
            license = "Apache License 2.0",
            url = "https://ai.google.dev/edge/litert",
            summary = "On-device runtime that executes the Marqo model. Bundled in the app.",
        ),
    )

    fun readAsset(context: Context): String =
        context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }

    data class Component(
        val name: String,
        val license: String,
        val url: String,
        val summary: String,
    )
}
