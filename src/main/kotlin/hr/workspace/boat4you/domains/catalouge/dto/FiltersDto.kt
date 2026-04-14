package hr.workspace.boat4you.domains.catalouge.dto

data class FiltersDto(
    val minPrice: PriceInfoDto,
    val maxPrice: PriceInfoDto,
    val minCabins: Short,
    val maxCabins: Short,
    val minPersons: Short,
    val maxPersons: Short,
    val minBerths: Short,
    val maxBerths: Short,
    val minLength: MeasurementUnitDto,
    val maxLength: MeasurementUnitDto,
    val minYear: Short,
    val maxYear: Short,
    val minWc: Short,
    val maxWc: Short,
    val minEnginePower: Short,
    val maxEnginePower: Short,
)
