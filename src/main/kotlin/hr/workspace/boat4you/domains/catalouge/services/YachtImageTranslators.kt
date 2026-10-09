package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.YachtImageDto
import hr.workspace.boat4you.domains.catalouge.jpa.YachtImage
import hr.workspace.boat4you.domains.catalouge.utils.LayoutImageRules

fun YachtImage.toDto(): YachtImageDto =
    YachtImageDto(
        id = id,
        position = position,
        mainImage = mainImage,
        layout = LayoutImageRules.isLayout(externalUrl, url),
    )
