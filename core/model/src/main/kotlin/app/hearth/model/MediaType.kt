package app.hearth.model

/**
 * The three libraries Hearth knows how to count.
 * Audio is one type here; music versus other audio is a count, not a type,
 * because MediaStore stores both in the same collection and flags music with IS_MUSIC.
 */
enum class MediaType {
    Image,
    Video,
    Audio,
}
