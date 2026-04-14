package hr.workspace.boat4you.domains.settings.services

import hr.workspace.boat4you.common.exceptions.ParameterValidationException
import hr.workspace.boat4you.domains.settings.dto.SettingsDto
import hr.workspace.boat4you.domains.settings.enums.SettingsKeyEnum
import hr.workspace.boat4you.domains.settings.jpa.SettingsEntity
import hr.workspace.boat4you.domains.settings.jpa.SettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminSettingsService(
    private val settingsRepository: SettingsRepository,
) {
    fun getAllSettings(): List<SettingsDto> {
        val dbSettingsMap = settingsRepository.findAll().associateBy { it.name }

        val combinedSettings =
            SettingsKeyEnum.entries.map { entry ->
                if (dbSettingsMap[entry] == null) {
                    SettingsDto(
                        name = entry,
                        value = null,
                    )
                } else {
                    dbSettingsMap[entry]!!.toDto()
                }
            }

        return combinedSettings
    }

    fun getSetting(name: SettingsKeyEnum): SettingsDto {
        return settingsRepository.findByName(name)?.toDto() ?: SettingsDto(name = name, value = null)
    }

    @Transactional
    fun updateSetting(newSetting: SettingsDto): SettingsDto {
        validateSetting(newSetting)

        val dbSetting = settingsRepository.findByName(newSetting.name) ?: SettingsEntity().apply { name = newSetting.name }
        dbSetting.value = newSetting.value

        return settingsRepository.save(dbSetting).toDto()
    }

    private fun validateSetting(setting: SettingsDto) {
        when (setting.name) {
            SettingsKeyEnum.CARD_PAYMENT_SURCHARGE -> validateCardPaymentSurchargeValue(setting.value)
        }
    }

    private fun validateCardPaymentSurchargeValue(value: String?) {
        if (value == null || value.toDoubleOrNull() == null) {
            throw ParameterValidationException(mapOf("value" to "Value must be a valid decimal number"))
        }
        val doubleValue = value.toDouble()
        if (doubleValue !in 0.0..100.0) {
            throw ParameterValidationException(mapOf("value" to "Value must be between 0 and 100"))
        }
    }
}
