package hr.workspace.boat4you.common

import hr.workspace.boat4you.common.services.extractAndMultiplyNumbers
import hr.workspace.boat4you.common.services.parseYachtSearchViewLocationName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UtilsTests {
    @Test
    fun `test extraction of horsepower`() {
        assertEquals(880, extractAndMultiplyNumbers("2x440 Hp Volvo"))
        assertEquals(880, extractAndMultiplyNumbers("2x 440 Hp Volvo"))
        assertEquals(880, extractAndMultiplyNumbers("2x Volvo 440 hp"))
        assertEquals(880, extractAndMultiplyNumbers("880 Hp Volvo"))
        assertEquals(400, extractAndMultiplyNumbers("4 x 100 Hp"))
    }

    @Test
    fun `test location name parsing`() {
        val locationParts = parseYachtSearchViewLocationName("32-Sukosan, D-Marin Dalmacija Marina-HR")
        assert(locationParts.id == "32")
        assert(locationParts.name == "Sukosan, D-Marin Dalmacija Marina")
        assert(locationParts.countryCode == "HR")

        val locationParts2 = parseYachtSearchViewLocationName("147-Athens, Alimos marina-GR")
        assert(locationParts2.id == "147")
        assert(locationParts2.name == "Athens, Alimos marina")
        assert(locationParts2.countryCode == "GR")

        val locationParts3 = parseYachtSearchViewLocationName("2310-Nesto- nesto, -sjebani ! podaci, ? marina-SFRJ")
        assert(locationParts3.id == "2310")
        assert(locationParts3.name == "Nesto- nesto, -sjebani ! podaci, ? marina")
        assert(locationParts3.countryCode == "SFRJ")
    }
}
