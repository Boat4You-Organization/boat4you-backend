package hr.workspace.boat4you.common.exceptions

class UnmodifiableFieldsException(
    val fieldNames: List<String>,
) : RuntimeException()
