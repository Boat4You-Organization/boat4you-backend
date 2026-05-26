package hr.workspace.boat4you.common.models

import java.io.Serializable

data class Addres11sInfoJsonb(
    var countryCode: String = "HR",
    var streetName: String? = null,
    var streetNumber: String? = null,
    var city: String? = null,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
