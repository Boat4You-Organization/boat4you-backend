package hr.workspace.boat4you.domains.settings.jpa

import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import org.springframework.data.jpa.repository.JpaRepository

interface SettingsRepository : JpaRepository<SettingsEntity, Long> {
    fun findByName(name: SettingsKeyEnum): SettingsEntity?

    /** Only the rows of the given keys: other rows of the table (the `cc.*` keys of CcSettings) have no enum constant. */
    fun findAllByNameIn(names: Collection<SettingsKeyEnum>): List<SettingsEntity>
}
