package com.useinoski.gallerycleanup.detection.eval

/**
 * One person's label for one photo.
 *
 * [EXPLICIT] is the only positive class in the gate.
 * [NOT_EXPLICIT] is the only negative class. The false-positive rate is
 * false positives divided by the number of not-explicit items.
 * [UNSURE] and [IMMODEST] are neither. They do not move precision, recall,
 * or the false-positive rate. [IMMODEST] is reported on its own: how many of
 * those photos score at or above each threshold, so a softer list can be
 * chosen later.
 *
 * The enum names are the tokens in the label file. Renaming one orphans
 * labels already stored on a phone.
 */
enum class NudityLabel {
    EXPLICIT,
    NOT_EXPLICIT,
    UNSURE,
    IMMODEST,
}
