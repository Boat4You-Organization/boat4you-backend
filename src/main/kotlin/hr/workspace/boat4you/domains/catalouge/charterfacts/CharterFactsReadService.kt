package hr.workspace.boat4you.domains.catalouge.charterfacts

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import hr.workspace.boat4you.domains.catalouge.enums.VesselType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** Public read side: one indexed row lookup, no aggregation (runs on the API node). */
@Service
class CharterFactsReadService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun find(
        did: String,
        vesselType: VesselType?,
    ): ObjectNode? {
        val row =
            jdbcTemplate
                .query(
                    "SELECT payload::text, computed_at FROM charter_facts WHERE did = ? AND COALESCE(vessel_type, '') = ?",
                    { rs, _ -> rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java) },
                    did,
                    vesselType?.name ?: "",
                ).firstOrNull() ?: return null
        val out = objectMapper.createObjectNode()
        out.put("did", did)
        out.put("vesselType", vesselType?.name)
        out.put("computedAt", row.second.toInstant().toString())
        out.setAll<ObjectNode>(objectMapper.readTree(row.first) as ObjectNode)
        return out
    }
}
