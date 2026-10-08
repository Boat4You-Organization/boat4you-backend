package hr.workspace.boat4you.domains.catalouge.jpa

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * Explicit link of one partner equipment item to our catalogue, ahead of the name matcher (V9_74, equipment audit
 * 8.10.2026). A catalogue item is (system, partner item id, ''); an MMK free-text item (parentId -1) is
 * (1, -1, EquipmentNames.normalize(name)). [equipmentId] null = deliberately no link. Rows are written by R__1_05 only
 * (single writer, upsert; table from V9_74); read through partnerEquipmentMappingCache.
 */
@Entity
@Table(name = "partner_equipment_mapping")
open class PartnerEquipmentMapping {
    @Id
    @Column(name = "id", columnDefinition = "BIGSERIAL", unique = true, updatable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "external_system_id", nullable = false)
    open var externalSystemId: Int? = null

    @Column(name = "partner_item_id", nullable = false)
    open var partnerItemId: Long? = null

    @Column(name = "partner_name_norm", nullable = false, length = Integer.MAX_VALUE)
    open var partnerNameNorm: String = ""

    @Column(name = "equipment_id")
    open var equipmentId: Long? = null

    @Column(name = "note", nullable = false, length = Integer.MAX_VALUE)
    open var note: String = ""
}
