package com.useinoski.gallerycleanup.model

/**
 * One pure check from library metadata to review categories.
 *
 * Implement this for a folder, file name, date, duration, or IS_MUSIC rule.
 * The NSFW detector, the face detector, and the alcohol and gambling labels
 * already return scores. A later change maps those scores onto
 * [MediaCategory.Sensitive], [MediaCategory.People], and
 * [MediaCategory.Experimental]. This interface has no bitmap and cannot run
 * a model.
 *
 * [categorize] returns every category this rule matches. An empty set means
 * this rule does not apply. It is not an error, and it is not the unflagged
 * bucket stored in `:core:data`. Several rules may match one file. The
 * caller unions the sets. This interface does not rank them.
 *
 * An implementation is pure. The same [MediaItemMetadata] and the same
 * caller-supplied clock return the same set. It does not read the system
 * clock, the network, or MediaStore, and it does not delete or modify the
 * file. A date rule may take a `java.time.Clock` from the caller so a test
 * can pin the day. Every other input is a field on [MediaItemMetadata].
 *
 * The folder rules and the date rule live in `:core:media`. This type stays
 * the contract. The scanner does not call them yet.
 */
interface CategoryRule {
    fun categorize(item: MediaItemMetadata): Set<MediaCategory>
}
