package hr.workspace.boat4you.common.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.common.jpa.EntityStatusEnum
import org.openapitools.model.BasicEntityStatus
import org.openapitools.model.InternalPageModel
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

fun pagingAndSortingModelToJpaPageRequest(
    sortBy: String?,
    sortDirection: String?,
    pageNumber: Int?,
    pageSize: Int?,
): Pageable {
    val finalSortBy = sortBy ?: "id"

    val finalSortDirection =
        sortDirection?.let {
            when (it) {
                "ASC" -> Sort.Direction.ASC
                "DESC" -> Sort.Direction.DESC
                else -> throw ParameterValidationException(mapOf("sortDirection" to "Must either ASC or DESC"))
            }
        } ?: Sort.Direction.DESC

    pageNumber?.let {
        if (it < 0) {
            throw ParameterValidationException(mapOf("pageNumber" to "Must be 0 or greater"))
        }
    }
    val finalPageNumber = pageNumber ?: 0

    pageSize?.let {
        if (it <= 0) {
            throw ParameterValidationException(mapOf("pageSize" to "Must be greater than 0"))
        }
    }
    val finalPageSize = pageSize ?: 20

    return PageRequest.of(finalPageNumber, finalPageSize, Sort.by(finalSortDirection, finalSortBy))
}

fun EntityStatusEnum.toStatusModel(): BasicEntityStatus =
    when (this) {
        EntityStatusEnum.ACTIVE -> BasicEntityStatus.ACTIVE
        EntityStatusEnum.INACTIVE -> BasicEntityStatus.INACTIVE
    }

fun BasicEntityStatus.toJpaEntityStatus(): EntityStatusEnum =
    when (this) {
        BasicEntityStatus.ACTIVE -> EntityStatusEnum.ACTIVE
        BasicEntityStatus.INACTIVE -> EntityStatusEnum.INACTIVE
    }

fun <T> Page<T>.toInternalPageModel(): InternalPageModel =
    InternalPageModel(
        totalElements = this.totalElements,
        number = this.number.toLong(),
        propertySize = this.size.toLong(),
        totalPages = this.totalPages.toLong(),
    )
