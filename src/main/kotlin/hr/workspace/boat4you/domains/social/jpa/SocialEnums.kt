package hr.workspace.boat4you.domains.social.jpa

/** Every network the admin knows; S1 connectors: FACEBOOK, INSTAGRAM, TIKTOK, YOUTUBE, THREADS, LINKEDIN, BLUESKY. */
enum class SocialPlatform {
    FACEBOOK,
    INSTAGRAM,
    THREADS,
    LINKEDIN,
    X,
    TIKTOK,
    YOUTUBE,
    PINTEREST,
    BLUESKY,
    GOOGLE_BUSINESS,
}

/** CONNECTED = can publish; EXPIRED = the token ran out, reconnect; ERROR = the last call was refused. */
enum class SocialAccountStatus { CONNECTED, EXPIRED, ERROR }

enum class SocialMediaKind { IMAGE, VIDEO }

/** Where a file came from: an admin upload, one of our catalogue photos, or a generator (Higgsfield). */
enum class SocialMediaOrigin { UPLOAD, CATALOGUE, URL, GENERATED }

enum class SocialPostStatus { DRAFT, SCHEDULED, PUBLISHING, PUBLISHED, PARTIAL, FAILED, CANCELLED }

enum class SocialTargetStatus { PENDING, SCHEDULED, PUBLISHING, PUBLISHED, FAILED, CANCELLED }

/** ADMIN = a person in the admin; API = the automation key; AI = the daily AI post. */
enum class SocialPostSource { ADMIN, API, AI }

enum class SocialPostAction { DRAFT, SCHEDULE, PUBLISH_NOW }

enum class SocialAiRunStatus { RUNNING, WAITING_VIDEO, DONE, SKIPPED, FAILED }

enum class SocialGenerationStatus { QUEUED, RUNNING, DONE, FAILED }
