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
 * The checkable assertions a verdict makes about the world, as opaque tokens.
 *
 * A pass that retries never reaches a verdict of its own — it has not ended — so the only
 * honest thing it can do to the shade is take down what it has since proved wrong. That
 * comparison needs the standing verdict's claims and nothing else about it, which is why
 * these are a set of tokens rather than prose: the notification carries them, and a later
 * pass matches its own evidence against them without having to rebuild the notice.
 *
 * A verdict with nothing checkable in it claims a token nothing ever produces, so it
 * stands until a pass ends and replaces it. "Backup finished" claims nothing at all and
 * is left alone for the same reason from the other direction: a pass starting again does
 * not make last night's count untrue.
 */
fun verdictClaims(notice: PassNotice): Set<String> = when (notice) {
    is PassNotice.Finished -> emptySet()
    is PassNotice.Failed -> when (notice.reason) {
        FailureReason.MediaAccess -> setOf(MEDIA_UNREADABLE)
        // A sign-out that names no server is a claim about nothing in particular, and
        // nothing in particular can disprove it.
        FailureReason.SignedOut -> notice.servers.map(::signedOutOf).toSet()
        FailureReason.Unknown -> setOf(NOT_CHECKABLE)
    }
}

/**
 * What a pass has disproved by the time it gives up and asks for a retry.
 *
 * Called from inside the upload loop, which is what makes the first token true: reaching
 * it at all means MediaStore answered. An upload the server accepted means that account
 * is not signed out, whatever went wrong afterwards — and a server that could not be
 * reached says nothing either way, so a destination that sent nothing disproves nothing.
 *
 * [signedOutNow] is what this pass found for itself, and it overrides the uploads. A
 * token that expires part way through leaves both behind — five files in, then a 401 —
 * and the newer of the two is the one that is still true.
 */
fun disprovedByRetry(
    sent: List<DestinationProgress>,
    signedOutNow: Set<String>,
): Set<String> = setOf(MEDIA_UNREADABLE) +
    sent.filter { it.completed > 0 && it.label !in signedOutNow }
        .map { signedOutOf(it.label) }

/**
 * Whether every claim a standing verdict makes has been disproved.
 *
 * All of them or none: a verdict is an instruction, and one that is still true of a
 * second server is still the thing to act on. Partly disproved, it stands as posted —
 * reposting a shortened version would be a verdict no pass has actually reached, and
 * under [BackupNotifications.result]'s rule it would have to arrive silently.
 */
fun retiredBy(claims: Set<String>, disproved: Set<String>): Boolean =
    claims.isNotEmpty() && disproved.containsAll(claims)

private const val MEDIA_UNREADABLE = "media"
private const val NOT_CHECKABLE = "unknown"

private fun signedOutOf(label: String) = "signed-out:$label"

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
