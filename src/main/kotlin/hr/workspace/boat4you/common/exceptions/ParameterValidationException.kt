package hr.workspace.boat4you.common.exceptions

class ParameterValidationException(
    val badOrMissingParameters: Map<String, String>,
) : RuntimeException()
