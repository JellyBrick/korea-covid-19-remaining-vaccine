package be.zvz.covid.remaining.vaccine.dto.config

data class Config(
    val vaccineType: String,
    val top: Latitude,
    val bottom: Latitude,
    val searchTime: Double = 0.2,
)
