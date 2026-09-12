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

/**
 * The expanded body. Never absent, and it always leads with the sentence.
 *
 * `bigText` replaces the collapsed line rather than following it, so anything left out of
 * here is unreachable — and the collapsed line is around thirty-five characters, which is
 * not enough for "Signed out of family.example.org. Sign in again to carry on backing
 * up." A verdict that cannot be read in full is a verdict that was not delivered.
 */
fun noticeDetail(notice: PassNotice): String {
    val rows = when (notice) {
        is PassNotice.Finished -> notice.destinations.takeIf { it.size > 1 }.orEmpty()
        is PassNotice.Failed -> notice.sent.filter { it.completed > 0 }
    }
    if (rows.isEmpty()) return noticeText(notice)
    val lines = rows.joinToString("\n") { "${it.label} · ${it.completed} backed up" }
    // The Finished text is a summary of these same rows, so repeating it would be noise;
    // a failure's is the half that needs acting on and leads.
    return if (notice is PassNotice.Failed) "${noticeText(notice)}\n\n$lines" else lines
}

/**
 * What makes two verdicts the same verdict.
 *
 * Counts are left out deliberately: 484 backed up yesterday and 490 today is the same
 * thing happening again, while a pass that finished and then a pass that could not is
 * news. See [BackupNotifications.result] for what is done with it.
 */
fun verdictKey(notice: PassNotice): String = when (notice) {
    is PassNotice.Finished -> "finished"
    is PassNotice.Failed -> "failed:${notice.reason}:${notice.servers.sorted().joinToString(",")}"
}

/**
 * Names that tell the destinations apart.
 *
 * Two accounts on one server is a supported arrangement, and the address alone then names
 * neither of them — the backup screen has the same problem and solves it the same way,
 * with who you are there underneath.
 */
fun distinctLabels(servers: List<String>, people: List<String>): List<String> =
    servers.mapIndexed { index, server ->
        if (servers.count { it == server } > 1) "$server · ${people[index]}" else server
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
