package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.ModelDto
import hr.workspace.boat4you.domains.catalouge.jpa.Model

fun ModelDto.toJpaModelEntity(): Model {
    val model = this
    return Model().apply {
        model.id?.let {
            id = model.id
        }
        name = model.name
    }
}

fun Model.toDto(): ModelDto =
    ModelDto(
        id = id,
        name = name,
    )
