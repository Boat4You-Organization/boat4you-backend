package hr.workspace.boat4you.domains.pr.service

/**
 * The footer the backend (never the writer) puts under every PR email: who we are, where our privacy notice is, and how
 * to stop getting these. The one source of the text: the admin shows [text] (PrPitchDto.footer) and sending appends it
 * with [appendTo].
 */
object PrFooter {
    fun text(privacyUrl: String): String =
        "Boat4you Group, Vrboran 37, 21000 Split, Croatia · How we handle your data: $privacyUrl\n" +
            "Not the right person, or don't want these? Just reply and I won't email you again."

    /** The body as sent: trailing blank lines and spaces dropped, one empty line, then the footer. */
    fun appendTo(
        body: String,
        privacyUrl: String,
    ): String = body.trimEnd() + "\n\n" + text(privacyUrl)
}
