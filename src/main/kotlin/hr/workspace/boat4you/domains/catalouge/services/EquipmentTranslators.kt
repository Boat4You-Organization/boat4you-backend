package hr.workspace.boat4you.domains.catalouge.services

import hr.workspace.boat4you.domains.catalouge.dto.EquipmentAdminDto
import hr.workspace.boat4you.domains.catalouge.dto.EquipmentDto
import hr.workspace.boat4you.domains.catalouge.dto.YachtEquipmentDto
import hr.workspace.boat4you.domains.catalouge.jpa.Equipment
import hr.workspace.boat4you.domains.catalouge.jpa.YachtEquipment

fun Equipment.toDto(): EquipmentDto =
    EquipmentDto(
        id = id!!,
        labelCode = labelCode!!,
        category = category!!,
        filterOrder = filterOrder,
    )

fun Equipment.toAdminDto(): EquipmentAdminDto =
    EquipmentAdminDto(
        id = id!!,
        labelCode = labelCode!!,
        category = category!!,
        filterOrder = filterOrder,
        matchKeys = matchKeys,
    )

fun YachtEquipment.toDto(): YachtEquipmentDto =
    YachtEquipmentDto(
        id = id!!,
        name = name,
        equipment = equipment?.toDto(),
        highlight = highlight,
        quantity = quantity,
        comment = comment,
    )

/** yacht_equipment.external_id of an MMK free-text item: every line an agency types itself shares partner item -1. */
const val FREE_TEXT_PARTNER_ITEM: Long = -1

private val ABSENT_VALUE = Regex("^(false|no|0|none|n/a)$", RegexOption.IGNORE_CASE)

/**
 * Whether the partner row says the boat has the item: its value (comment) is not "false" / "no" / "0" / "none" / "n/a"
 * and its quantity is not 0 - the web's isAmenityPresent, here because the public DTO drops the free-text value.
 */
fun YachtEquipment.isPresent(): Boolean = !ABSENT_VALUE.matches(comment?.trim().orEmpty()) && quantity?.signum() != 0

/**
 * The equipment of a yacht for a public page (yacht details, my-bookings; equipment audit 8.10.2026): rows linked to our
 * catalogue that the partner marks present, one per code. Each carries our catalogue name, never the partner's wording,
 * and an MMK free-text row no comment: name and value of those lines are an agency's own text (review 8.10.2026). The
 * web shows the translated label code; the name is only its fallback. Admins get every row (toDto).
 */
fun publicAmenities(rows: Collection<YachtEquipment>): List<YachtEquipmentDto> =
    rows
        .filter { it.equipmentId != null && it.equipment != null && it.isPresent() }
        .distinctBy { it.equipmentId }
        .map { row ->
            YachtEquipmentDto(
                id = row.id!!,
                name = row.equipment!!.name,
                equipment = row.equipment!!.toDto(),
                highlight = row.highlight,
                quantity = row.quantity,
                comment = if (row.externalId == FREE_TEXT_PARTNER_ITEM) null else row.comment,
            )
        }
