package hr.workspace.boat4you.common.errorhandling

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * The 1502 "Yacht is not active" body: [code] and [message] exactly as every other error ([org.openapitools.model.ErrorSchema]),
 * plus - only when the boat page names one (yacht_successor, V9_73) - the live listing of the same boat, so the web sites
 * can redirect the old URL permanently. Absent fields are left out of the JSON, never sent as null. Public: no agency
 * or partner data, and no field name may hint at one.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class YachtNotActiveErrorBody(
    val code: Int,
    val message: String,
    val successorSlug: String? = null,
    val successorId: Long? = null,
)
