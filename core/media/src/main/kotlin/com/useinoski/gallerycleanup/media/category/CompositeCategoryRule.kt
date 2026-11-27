package com.useinoski.gallerycleanup.media.category

import com.useinoski.gallerycleanup.model.CategoryRule
import com.useinoski.gallerycleanup.model.MediaCategory
import com.useinoski.gallerycleanup.model.MediaItemMetadata
import java.time.Clock

/**
 * Every [rules] match, as one set.
 *
 * A rule that returns an empty set does not remove another rule's match.
 * The same category from two rules appears once. The list is copied, so a
 * caller who keeps the list and edits it does not change this rule.
 *
 * This does not write MediaStore and it does not remove the file. The
 * scanner does not call [standardCategoryRules] yet. That waits on the
 * `item_category` table.
 */
class CompositeCategoryRule(
    rules: List<CategoryRule>,
) : CategoryRule {
    private val rules: List<CategoryRule> = rules.toList()

    override fun categorize(item: MediaItemMetadata): Set<MediaCategory> {
        val matches = LinkedHashSet<MediaCategory>()
        for (rule in rules) {
            matches.addAll(rule.categorize(item))
        }
        return matches
    }
}

/**
 * Screenshot, chat-image, and old-item rules, in that order.
 *
 * [oldItemMonths] defaults to six. [clock] decides "today" for the old-item
 * rule. Pass [Clock.fixed] from a test.
 */
fun standardCategoryRules(
    clock: Clock,
    oldItemMonths: OldItemMonths = OldItemMonths.Six,
): CategoryRule = CompositeCategoryRule(
    listOf(
        ScreenshotRule,
        ChatImageRule,
        OldItemRule(clock = clock, months = oldItemMonths),
    ),
)
