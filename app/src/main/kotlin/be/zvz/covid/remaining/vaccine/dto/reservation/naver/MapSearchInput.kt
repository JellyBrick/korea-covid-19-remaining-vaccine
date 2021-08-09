package be.zvz.covid.remaining.vaccine.dto.reservation.naver

data class MapSearchInput(
    val operationName: String,
    val variables: Variables,
    val query: String
) {
    data class Variables(
        val input: Input,
        val businessesInput: BusinessesInput,
        val isNmap: Boolean,
        val isBounds: Boolean
    ) {
        data class Input(
            val keyword: String,
            val x: String,
            val y: String
        )
        data class BusinessesInput(
            val bounds: String,
            val start: Int,
            val display: Int,
            val deviceType: String,
            val x: String,
            val y: String,
            val sortingOrder: String
        )
    }
}
