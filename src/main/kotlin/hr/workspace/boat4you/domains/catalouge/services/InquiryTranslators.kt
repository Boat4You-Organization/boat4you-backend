package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.InquiryBasicDto
import hr.workspace.boat4you.domains.catalouge.dto.InquiryDetailsDto
import hr.workspace.boat4you.domains.catalouge.jpa.Inquiry

fun Inquiry.toDto(): InquiryBasicDto =
    InquiryBasicDto(
        id = id!!,
        yachtId = yacht?.id,
        yachtName = yacht?.name,
        location = yacht?.location?.name,
        dateFrom = dateFrom,
        dateTo = dateTo,
        name = name,
        surname = surname,
        email = email!!,
        phone = phone,
        status = status!!,
        createdAt = createdAt!!,
    )

fun Inquiry.toDetailsDto(): InquiryDetailsDto =
    InquiryDetailsDto(
        id = id!!,
        yachtId = yacht?.id,
        yachtName = yacht?.name,
        dateFrom = dateFrom,
        dateTo = dateTo,
        name = name,
        surname = surname,
        email = email!!,
        phone = phone,
        status = status!!,
        message = message!!,
        createdAt = createdAt!!,
        locationId = "l-" + yacht?.location?.id,
        location = yacht?.location?.name,
        countryCode = yacht?.location?.countryCode,
        mainImage = yacht?.mainImageId,
        modelName = yacht?.model?.name,
    )
