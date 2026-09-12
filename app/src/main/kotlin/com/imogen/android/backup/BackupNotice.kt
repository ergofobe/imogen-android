package com.imogen.android.backup

/**
 * One destination's share of a pass, named the way the backup screen names it.
 *
 * The counts are per destination rather than summed, because "3 of 40" across two servers
 * is a number that belongs to neither of them.
 */
data class DestinationProgress(
    val label: String,
    val completed: Int,
    val total: Int,
)

/** What the shade is left holding once a pass has ended. */
sealed interface PassNotice {
    data class Finished(val destinations: List<DestinationProgress>) : PassNotice
    /**
     * [sent] is what did get through before the pass gave up. A server signing us out
     * does not unsend the four hundred photographs the other one took, and a notice that
     * mentions only the failure loses them.
     */
    data class Failed(
        val reason: FailureReason,
        val servers: List<String>,
        val sent: List<DestinationProgress> = emptyList(),
    ) : PassNotice
}

/**
 * Nothing at all for a pass that sent nothing.
 *
 * The periodic pass runs every six hours whether or not the camera roll has moved, and a
 * notification saying "nothing to do" four times a day is worse than silence.
 */
fun finishedNotice(destinations: List<DestinationProgress>): PassNotice.Finished? =
    destinations.filter { it.completed > 0 }
        .takeIf { it.isNotEmpty() }
        ?.let(PassNotice::Finished)

fun progressText(destinations: List<DestinationProgress>): String {
    val owed = destinations.filter { it.total > 0 }
    val one = owed.singleOrNull()
    if (one != null) return "${one.label} · ${one.completed} of ${one.total}"
    if (owed.isEmpty()) return ""
    // The collapsed line has room for one thing, and the pass's own total is at least
    // true of the pass. The names are one swipe away, below.
    return "${owed.sumOf { it.completed }} of ${owed.sumOf { it.total }} · ${owed.size} servers"
}

/** The expanded body, or null when the one line above already said everything. */
fun progressDetail(destinations: List<DestinationProgress>): String? =
    destinations.filter { it.total > 0 }
        .takeIf { it.size > 1 }
        ?.joinToString("\n") { "${it.label} · ${it.completed} of ${it.total}" }

fun noticeTitle(notice: PassNotice): String = when (notice) {
    is PassNotice.Finished -> "Backup finished"
    is PassNotice.Failed -> "Backup stopped"
}

fun noticeText(notice: PassNotice): String = when (notice) {
    is PassNotice.Finished -> finishedText(notice.destinations)
    is PassNotice.Failed -> failureText(notice.reason, notice.servers)
}

fun noticeDetail(notice: PassNotice): String? = when (notice) {
    is PassNotice.Finished -> notice.destinations
        .takeIf { it.size > 1 }
        ?.joinToString("\n") { "${it.label} · ${it.completed} backed up" }

    // The failure leads, because `bigText` replaces the collapsed line rather than
    // adding to it: without this, expanding a notice about being signed out shows only
    // the photographs that did get through and no sign that anything needs doing.
    is PassNotice.Failed -> notice.sent
        .filter { it.completed > 0 }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n", prefix = "${noticeText(notice)}\n\n") {
            "${it.label} · ${it.completed} backed up"
        }
}

private fun finishedText(destinations: List<DestinationProgress>): String {
    val one = destinations.singleOrNull()
    if (one != null) return "${one.label} · ${one.completed} backed up"
    return "${destinations.sumOf { it.completed }} backed up · ${destinations.size} servers"
}

private fun failureText(reason: FailureReason, servers: List<String>): String = when (reason) {
    FailureReason.MediaAccess -> "imogen cannot read your photographs."
    FailureReason.SignedOut -> if (servers.isEmpty()) {
        "An account is signed out. Sign in again to carry on backing up."
    } else {
        "Signed out of ${servers.joinToString(", ")}. Sign in again to carry on backing up."
    }

    FailureReason.Unknown -> "The backup could not finish. It will try again."
}
