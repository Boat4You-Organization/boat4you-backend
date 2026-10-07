package hr.workspace.boat4you.domains.catalouge.exceptions

import hr.workspace.boat4you.domains.catalouge.successor.YachtSuccessor

/** 1502. [successor] = the live listing of the same boat (yacht_successor, V9_73), when the boat page names one. */
class YachtNotActiveException(
    val successor: YachtSuccessor? = null,
) : RuntimeException()
