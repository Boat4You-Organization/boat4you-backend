package hr.workspace.boat4you.domains.settings.jpa

import hr.workspace.boat4you.common.jpa.AbstractEntity
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table

@Entity
@Table(name = "settings")
class SettingsEntity : AbstractEntity<Long>() {
    @Column(name = "name", columnDefinition = "VARCHAR(255)", nullable = false)
    @Enumerated(EnumType.STRING)
    lateinit var name: SettingsKeyEnum

    @Column(name = "value", columnDefinition = "VARCHAR(255)", nullable = true)
    var value: String? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as SettingsEntity

        if (name != other.name) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (value?.hashCode() ?: 0)
        return result
    }
}
