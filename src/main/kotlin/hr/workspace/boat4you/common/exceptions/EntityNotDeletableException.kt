package hr.workspace.boat4you.common.exceptions

class EntityNotDeletableException(
    val referencingEntities: Map<String, List<String>>,
) : RuntimeException()
