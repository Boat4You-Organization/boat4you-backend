package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage

fun YachtImage.toDto(): YachtImageDto =
    YachtImageDto(
        id = id,
        position = position,
        mainImage = mainImage,
    )
