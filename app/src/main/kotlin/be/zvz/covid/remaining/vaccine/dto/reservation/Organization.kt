package be.zvz.covid.remaining.vaccine.dto.reservation

data class Organization(
    val status: String,
    val leftCounts: Int,
    val orgName: String,
    val orgCode: String,
    val address: String
)
