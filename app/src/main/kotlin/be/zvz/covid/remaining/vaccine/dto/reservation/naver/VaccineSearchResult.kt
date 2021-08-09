package be.zvz.covid.remaining.vaccine.dto.reservation.naver

data class VaccineSearchResult(
    val data: Data
) {
    data class Data(
        val rests: Rests
    ) {
        data class Rests(
            val businesses: Businesses
        ) {
            data class Businesses(
                val total: Int,
                val items: List<Business>,
            ) {
                data class Business(
                    val id: String,
                    val name: String,
                    val roadAddress: String,
                    val vaccineQuantity: VaccineQuantity
                ) {
                    data class VaccineQuantity(
                        val totalQuantity: Int,
                        val totalQuantityStatus: String,
                        val vaccineOrganizationCode: String,
                        val list: List<VaccineInfo>
                    ) {
                        data class VaccineInfo(
                            val quantity: Int,
                            val quantityStatus: String,
                            val vaccineType: String
                        )
                    }
                }
            }
        }
    }
}
