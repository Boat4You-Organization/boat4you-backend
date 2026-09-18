package hr.workspace.boat4you.domains.catalouge.exceptions

/**
 * The bounded OpenCV resize gate in `YachtImageService` refused this request: either its queue was
 * already deeper than `YachtImageService.MAX_WAITERS_PER_PERMIT` per permit, or no slot came free
 * within `application.images.resize-wait-ms`. Shedding is deliberate: an unbounded burst of
 * `/public/image/{id}?width=…` calls is what drove the 16.9.2026 cusma2 load incident (native
 * memory outside the JVM's accounting, 9 OOM-kills in 3 days), and parking every burst request
 * instead would starve Tomcat's pool. Maps to 503 + `Retry-After: 2`.
 */
class ImageResizeBusyException : RuntimeException()
