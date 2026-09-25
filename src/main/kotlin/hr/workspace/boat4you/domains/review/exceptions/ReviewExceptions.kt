package hr.workspace.boat4you.domains.review.exceptions

/** Unknown, malformed or expired review link -> 404 (one answer for all three, so a link cannot be probed). */
class ReviewLinkInvalidException : RuntimeException()

/** The review was submitted more than 24 h ago and can no longer be changed -> 409. */
class ReviewEditWindowClosedException : RuntimeException()

/** Admin: no review with that id -> 404. */
class ReviewNotFoundException : RuntimeException()
