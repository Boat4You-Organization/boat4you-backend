package hr.workspace.boat4you.domains.calls.enums

/** Why a call row exists. HUMAN_CALLBACK is a task for Mario and is never dialled by Nika. */
enum class CallKind { CONFIRMATION, FOLLOW_UP, MANUAL, HUMAN_CALLBACK }
