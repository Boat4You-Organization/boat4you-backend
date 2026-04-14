package hr.workspace.boat4you.domains.users.services

import hr.workspace.boat4you.common.jpa.EntityStatusEnum
import hr.workspace.boat4you.common.services.ifNotNull
import hr.workspace.boat4you.common.services.initSpecification
import hr.workspace.boat4you.common.services.nonBlankOrNull
import hr.workspace.boat4you.common.services.pagingAndSortingModelToJpaPageRequest
import hr.workspace.boat4you.common.services.toInternalPageModel
import hr.workspace.boat4you.common.services.toJpaEntityStatus
import hr.workspace.boat4you.domains.roles.jpa.RoleAssignmentEntity
import hr.workspace.boat4you.domains.roles.jpa.RoleEntity
import hr.workspace.boat4you.domains.users.jpa.UserEntity
import hr.workspace.boat4you.domains.users.jpa.UserRepository
import org.openapitools.model.BasicEntityStatus
import org.openapitools.model.GetAllUsers200Response
import org.openapitools.model.RoleEnum
import org.openapitools.model.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class UserQueryingService(
    private val userRepository: UserRepository,
) {
    @Suppress("LongParameterList")
    fun getAllUsers(query: GetAllUsersQuery): GetAllUsers200Response {
        val pagingAndSortingCriteria = pagingAndSortingModelToJpaPageRequest(query.sortBy, query.sortDirection, query.pageNumber, query.pageSize)
        val pagedUsers = findAllWithCriteria(query, pagingAndSortingCriteria)

        return GetAllUsers200Response(
            content = pagedUsers.content.map { it.toUserModel() },
            page = pagedUsers.toInternalPageModel(),
        )
    }

    private fun findAllWithCriteria(
        query: GetAllUsersQuery,
        pageable: Pageable,
    ): Page<UserEntity> =
        userRepository.findAll(
            initSpecification(searchCriteria(query.search))
                .and(activeOnlyCriteria(query.activeOnly))
                .and(roleCriteria(query.role?.name))
                .and(userStatusCriteria(query.userStatus?.toJpaEntityStatus())),
            pageable,
        )

    private fun searchCriteria(searchString: String?): Specification<UserEntity>? =
        searchString.nonBlankOrNull()?.let {
            Specification { root, _, cb ->
                cb.and(
                    cb.or(
                        cb.like(cb.upper(root.get(UserEntity::fullNameByFormula.name)), "%${it.uppercase(Locale.getDefault())}%"),
                        cb.like(cb.upper(root.get(UserEntity::email.name)), "%${it.uppercase(Locale.getDefault())}%"),
                    ),
                )
            }
        }

    private fun activeOnlyCriteria(activeOnly: Boolean?): Specification<UserEntity>? =
        activeOnly?.let {
            Specification { root, _, cb ->
                cb.and(cb.equal(root.get<EntityStatusEnum>(UserEntity::entityStatus.name), EntityStatusEnum.ACTIVE))
            }
        }

    private fun roleCriteria(role: String?): Specification<UserEntity>? =
        role.nonBlankOrNull()?.let {
            Specification { root, _, cb ->
                val joinAssignments = root.joinSet<UserEntity, RoleAssignmentEntity>(UserEntity::roleAssignments.name)
                val joinRoles = joinAssignments.join<RoleAssignmentEntity, RoleEntity>(RoleAssignmentEntity::role.name)
                cb.equal(joinRoles.get<String>(RoleEntity::name.name), role)
            }
        }

    private fun userStatusCriteria(userStatus: EntityStatusEnum?): Specification<UserEntity>? =
        userStatus?.let {
            Specification { root, _, cb ->
                cb.and(cb.equal(root.get<EntityStatusEnum>(UserEntity::entityStatus.name), userStatus))
            }
        }

    fun getUserById(id: Long): User? {
        return userRepository.findById(id).ifNotNull { it.toUserModel() }
    }
}

data class GetAllUsersQuery(
    val sortBy: String? = null,
    val sortDirection: String? = null,
    val pageNumber: Int? = null,
    val pageSize: Int? = null,
    val search: String? = null,
    val activeOnly: Boolean? = null,
    val role: RoleEnum? = null,
    val userStatus: BasicEntityStatus? = null,
)
