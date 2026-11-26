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
 * An implementation is pure. The same [MediaItemMetadata] returns the same
 * set. It does not read a clock, the network, or MediaStore, and it does not
 * delete or modify the file. The fields on [MediaItemMetadata] are the only
 * input.
 *
 * No implementation ships with this type.
 */
interface CategoryRule {
    fun categorize(item: MediaItemMetadata): Set<MediaCategory>
}
