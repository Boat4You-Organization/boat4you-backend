package hr.workspace.boat4you.security.exceptions

/**
 * Raised when a social provider (currently Facebook) completes the OAuth dance
 * but does NOT share the user's email. Our account model is email-keyed, so we
 * cannot find-or-create an account without one. This is deliberately DISTINCT
 * from [InternalLoginException] (BAD_CREDENTIALS): it is not an auth failure to
 * hide, it is an actionable user-facing condition — the frontend tells the user
 * to sign up with email instead. Mapped to a clear 4xx in ApiErrorHandler.
 *
 * Facebook only returns the `email` field when it is the account's confirmed
 * email; it can be absent when the user registered with a phone number only or
 * declined the email permission.
 */
class OAuthEmailMissingException(
    val provider: String,
) : Exception("Social login provider did not share an email: provider=$provider")
