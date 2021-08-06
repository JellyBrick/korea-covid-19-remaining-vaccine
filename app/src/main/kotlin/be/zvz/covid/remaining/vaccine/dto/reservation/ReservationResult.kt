package be.zvz.covid.remaining.vaccine.dto.reservation

data class ReservationResult(
    val code: String,
    val organization: ReservationOrganization?
)
