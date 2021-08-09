package be.zvz.covid.remaining.vaccine

import be.zvz.covid.remaining.vaccine.dto.config.Config
import be.zvz.covid.remaining.vaccine.dto.config.Latitude
import be.zvz.covid.remaining.vaccine.dto.config.TelegramBotConfig
import be.zvz.covid.remaining.vaccine.dto.config.VaccineType
import be.zvz.covid.remaining.vaccine.dto.reservation.kakao.FindVaccineResult
import be.zvz.covid.remaining.vaccine.dto.reservation.kakao.ReservationResult
import be.zvz.covid.remaining.vaccine.dto.reservation.naver.MapSearchInput
import be.zvz.covid.remaining.vaccine.dto.reservation.naver.VaccineSearchResult
import be.zvz.covid.remaining.vaccine.dto.user.UserInfo
import be.zvz.covid.remaining.vaccine.dto.user.UserInfoResult
import cmonster.browsers.ChromeBrowser
import cmonster.cookies.DecryptedCookie
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.afterburner.AfterburnerModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.kittinunf.fuel.core.FuelManager
import com.github.kittinunf.fuel.core.Headers
import com.github.kittinunf.fuel.jackson.responseObject
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.SendMessage
import org.slf4j.LoggerFactory
import java.io.* // ktlint-disable no-wildcard-imports
import java.security.cert.X509Certificate
import javax.net.ssl.* // ktlint-disable no-wildcard-imports
import javax.sound.sampled.AudioSystem
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

class App {
    private val log = LoggerFactory.getLogger(this::class.java)

    private val mapper = JsonMapper()
        .registerKotlinModule()
        .registerModule(AfterburnerModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val config: Config
    private lateinit var telegramBotConfig: TelegramBotConfig
    private lateinit var telegramBot: TelegramBot

    private val cookies = ChromeBrowser().getCookiesForDomain(".kakao.com")
    private val fuelManager = FuelManager()

    init {
        val configFile = File("config.json")
        config = if (configFile.exists()) {
            print("기존에 입력한 정보로 재검색 하시겠습니까? Y/N : ")
            when (readLine()!!.lowercase()) {
                "y" -> {
                    try {
                        mapper.readValue(configFile)
                    } catch (e: IOException) {
                        generateConfig()
                    }
                }
                else -> generateConfig()
            }
        } else {
            generateConfig()
        }

        BufferedWriter(FileWriter(configFile)).use {
            it.write(mapper.writeValueAsString(config))
        }

        try {
            val telegramBotConfigFile = File("telegram_config.json")
            if (telegramBotConfigFile.exists()) {
                telegramBotConfig = mapper.readValue(telegramBotConfigFile)
                telegramBot = TelegramBot(telegramBotConfig.token)
                log.info("텔레그램 알림이 활성화되었습니다!")
            }
        } catch (ignored: IOException) {
            log.error("텔레그램 봇 설정 파일(telegram_config.json)에 오류가 있어, 텔레그램 알림이 활성화되지 않았습니다.")
        }
    }

    private fun ignoreSsl() {
        val hv = HostnameVerifier { _, _ -> true }
        trustAllHttpsCertificates()
        HttpsURLConnection.setDefaultHostnameVerifier(hv)
    }

    private fun trustAllHttpsCertificates() {
        val trustAllCerts = arrayOfNulls<TrustManager>(1)
        val tm = MiTM()
        trustAllCerts[0] = tm
        val sc = SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, null)
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
    }

    internal class MiTM : TrustManager, X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate>? {
            return null
        }

        fun isServerTrusted(certs: Array<X509Certificate?>?): Boolean {
            return true
        }

        fun isClientTrusted(certs: Array<X509Certificate?>?): Boolean {
            return true
        }

        override fun checkServerTrusted(certs: Array<X509Certificate?>?, authType: String?) {
            return
        }

        override fun checkClientTrusted(certs: Array<X509Certificate?>?, authType: String?) {
            return
        }
    }

    private fun generateConfig(): Config {
        println("=== 백신 목록 ===")
        VACCINE_CANDIDATES.forEach { (name, code) ->
            if (name == UNUSED) {
                return@forEach
            }
            println(name.padEnd(10) + " : " + code)
        }

        fun getVaccineType(): VaccineType {
            val vaccineCode = run {
                print("예약 시도할 백신 코드를 알려주세요 : ")
                readLine()!!
            }

            if (vaccineCode.startsWith("FORCE:")) {
                val forceCode = vaccineCode.split("FORCE:")[1].trim()
                println(
                    "경고: 강제 코드 입력모드를 사용하셨습니다.\n" +
                        "이 모드는 새로운 백신이 예약된 코드로 '등록되지 않은 경우에만' 사용해야 합니다.\n" +
                        "입력하신 코드가 정상적으로 작동하는 백신 코드인지 필히 확인해주세요.\n" +
                        "현재 코드: $forceCode"
                )
                return VaccineType("(강제)", forceCode)
            }

            VACCINE_CANDIDATES.forEach {
                if (vaccineCode == it.code) {
                    return it
                }
            }
            println("백신 코드를 확인해주세요.")
            return getVaccineType()
        }

        val vaccineType = getVaccineType()
        println("백신 범위를 지정하세요. 해당 범위 안에 있는 백신을 조회합니다.")

        fun getCoordinateFromInput(name: String, example: String): Double {
            print("사각형의 ${name}값을 넣어주세요. $example: ")
            return readLine()!!.toDoubleOrNull() ?: run {
                println("올바른 좌표 값이 아닙니다.")
                getCoordinateFromInput(name, example)
            }
        }

        val topX = getCoordinateFromInput("좌측 상단 X", "1xx.xxxxxx")
        val topY = getCoordinateFromInput("좌측 상단 Y", "3x.xxxxxx")
        val bottomX = getCoordinateFromInput("우측 하단 X", "1xx.xxxxxx")
        val bottomY = getCoordinateFromInput("우측 하단 Y", "3x.xxxxxx")

        return Config(
            vaccineType = vaccineType.code,
            top = Latitude(topX, topY),
            bottom = Latitude(bottomX, bottomY)
        )
    }

    private fun playSound(stream: InputStream) {
        try {
            val clip = AudioSystem.getClip()
            val inputStream = AudioSystem.getAudioInputStream(stream)
            clip.open(inputStream)
            clip.start()
        } catch (ignored: Exception) {
        }
    }

    private fun showOrganizationList() {
        ignoreSsl()
        val (response, fuelError) = fuelManager
            .post("https://vaccine-map.kakao.com/api/v3/vaccine/left_count_by_coords")
            .body(
                "{\"bottomRight\":{\"x\":${config.bottom.x},\"y\":${config.bottom.y}},\"onlyLeft\":false,\"order\":\"latitude\",\n" +
                    "\"topLeft\":{\"x\":${config.top.x},\"y\":${config.top.y}}}"
            )
            .header(NORMAL_HEADERS)
            .timeout(5000)
            .responseObject<FindVaccineResult>(mapper = mapper)
            .third

        fuelError?.let {
            close(throwable = it.exception)
        }
        response?.let { result ->
            log.info("=== 감지된 병원 목록 ===")
            result.organizations.forEachIndexed { index, organization ->
                log.info("${index + 1}: ${organization.orgName}")
            }
        }
    }

    private fun findVaccine() {
        while (true) {
            val centerX = min(config.top.x, config.bottom.x) + (max(config.top.x, config.bottom.x) - min(config.top.x, config.bottom.x) / 2)
            val centerY = min(config.top.y, config.bottom.y) + (max(config.top.y, config.bottom.y) - min(config.top.y, config.bottom.y) / 2)
            ignoreSsl()
            val (response, fuelError) = fuelManager
                .post("https://api.place.naver.com/graphql")
                .body(
                    mapper.writeValueAsString(
                        mutableListOf<MapSearchInput>().apply {
                            add(
                                MapSearchInput(
                                    operationName = "vaccineList",
                                    variables = MapSearchInput.Variables(
                                        input = MapSearchInput.Variables.Input(
                                            keyword = "코로나백신위탁의료기관",
                                            x = centerX.toString(),
                                            y = centerY.toString()
                                        ),
                                        businessesInput = MapSearchInput.Variables.BusinessesInput(
                                            bounds = "${min(config.top.x, config.bottom.x)};${
                                            min(
                                                config.top.y,
                                                config.bottom.y
                                            )
                                            };${max(config.top.x, config.bottom.x)};${max(config.top.y, config.bottom.y)}",
                                            start = 0,
                                            display = 100,
                                            deviceType = "mobile",
                                            x = centerX.toString(),
                                            y = centerY.toString(),
                                            sortingOrder = "distance"
                                        ),
                                        isNmap = false,
                                        isBounds = false
                                    ),
                                    query = "query vaccineList(\$input: RestsInput, \$businessesInput: RestsBusinessesInput, \$isNmap: Boolean!, \$isBounds: Boolean!) {\n  rests(input: \$input) {\n    businesses(input: \$businessesInput) {\n      total\n      vaccineLastSave\n      isUpdateDelayed\n      items {\n        id\n        name\n        dbType\n        phone\n        virtualPhone\n        hasBooking\n        hasNPay\n        bookingReviewCount\n        description\n        distance\n        commonAddress\n        roadAddress\n        address\n        imageUrl\n        imageCount\n        tags\n        distance\n        promotionTitle\n        category\n        routeUrl\n        businessHours\n        x\n        y\n        imageMarker @include(if: \$isNmap) {\n          marker\n          markerSelected\n          __typename\n        }\n        markerLabel @include(if: \$isNmap) {\n          text\n          style\n          __typename\n        }\n        isDelivery\n        isTakeOut\n        isPreOrder\n        isTableOrder\n        naverBookingCategory\n        bookingDisplayName\n        bookingBusinessId\n        bookingVisitId\n        bookingPickupId\n        vaccineOpeningHour {\n          isDayOff\n          standardTime\n          __typename\n        }\n        vaccineQuantity {\n          totalQuantity\n          totalQuantityStatus\n          startTime\n          endTime\n          vaccineOrganizationCode\n          list {\n            quantity\n            quantityStatus\n            vaccineType\n            __typename\n          }\n          __typename\n        }\n        __typename\n      }\n      optionsForMap @include(if: \$isBounds) {\n        maxZoom\n        minZoom\n        includeMyLocation\n        maxIncludePoiCount\n        center\n        __typename\n      }\n      __typename\n    }\n    queryResult {\n      keyword\n      vaccineFilter\n      categories\n      region\n      isBrandList\n      filterBooking\n      hasNearQuery\n      isPublicMask\n      __typename\n    }\n    __typename\n  }\n}\n"
                                )
                            )
                        }
                    )
                )
                .header(NAVER_HEADERS)
                .timeout(5000)
                .responseObject<List<VaccineSearchResult>>(mapper = mapper)
                .third

            fuelError?.let {
                close(throwable = it.exception)
            }

            response?.let { result ->
                result.first().data.rests.businesses.items.forEach topLevelForEach@{
                    if (it.vaccineQuantity.totalQuantity > 0) {
                        log.info("${it.name}에서 백신을 ${it.vaccineQuantity.totalQuantity}개 발견했습니다.")
                        log.info("주소는 ${it.roadAddress}입니다.")

                        var vaccineFoundCode: String? = null
                        if (config.vaccineType == "ANY") {
                            it.vaccineQuantity.list.forEach { vaccineInfo ->
                                if (vaccineInfo.quantity > 0) {
                                    vaccineFoundCode = VACCINE_CANDIDATES_MAP.getValue(vaccineInfo.vaccineType).code
                                    // 될 수 있는 경우 AZ 대신 화이자 / 모더나 선택
                                }
                            }
                        } else {
                            for (vaccineInfo in it.vaccineQuantity.list) {
                                if (KAKAO_TO_NAVER_MAP[config.vaccineType] == vaccineInfo.vaccineType && vaccineInfo.quantity > 0) {
                                    vaccineFoundCode = config.vaccineType
                                    break
                                }
                            }
                        }

                        if (vaccineFoundCode === null) {
                            log.error("$vaccineFoundCode 백신이 없습니다.")
                            return@topLevelForEach
                        }

                        log.info("$vaccineFoundCode 백신으로 예약을 시도합니다.")

                        if (tryReservation(it.vaccineQuantity.vaccineOrganizationCode, vaccineFoundCode!!)) {
                            close()
                        }
                    }
                }
            }
            log.info("잔여백신이 없습니다.")
            Thread.sleep((config.searchTime * 1000L).toLong())
        }
    }

    private fun tryReservation(orgCode: String, vaccineCode: String, retry: Boolean = false): Boolean {
        ignoreSsl()
        val (response, fuelError) = fuelManager.post("https://vaccine.kakao.com/api/v2/reservation" + if (retry) "/retry" else "")
            .body(
                "{\"from\":\"List\",\"vaccineCode\":\"$vaccineCode\"," +
                    "\"orgCode\":\"$orgCode\",\"distance\":null}",
            )
            .header(VACCINE_HEADER)
            .header(
                Headers.COOKIE,
                mutableListOf<String>().apply {
                    cookies.forEach { cookie ->
                        if (cookie is DecryptedCookie) {
                            add(cookie.name + "=" + cookie.decryptedValue)
                        }
                    }
                }.joinToString("; ")
            )
            .responseObject<ReservationResult>()
            .third

        fun parseReservationResult(it: ReservationResult): Boolean {
            return when (it.code) {
                "NO_VACANCY" -> {
                    log.error("잔여백신 접종 신청이 선착순 마감되었습니다.")
                    false
                }
                "TIMEOUT" -> {
                    log.warn("TIMEOUT, 예약을 재시도합니다.")
                    tryReservation(orgCode, vaccineCode, true)
                }
                "SUCCESS" -> {
                    sendSuccess(
                        "백신 접종 신청 완료!" +
                            it.organization?.let { ro ->
                                "\n병원 이름: ${ro.orgName}\n" +
                                    "전화번호: ${ro.phoneNumber}\n" +
                                    "주소: ${ro.address}"
                            }
                    )
                    true
                }
                else -> {
                    log.error("알 수 없는 오류")
                    false
                }
            }
        }

        fuelError?.let {
            if (it.exception !is JacksonException) {
                val reservationResult = parseReservationResult(mapper.readValue(it.errorData))
                if (reservationResult) {
                    return reservationResult
                }
            } else {
                log.error("오류. 아래 메시지를 보고, 예약이 신청된 병원 또는 1339에 예약이 되었는지 확인해보세요.")
                close(it.exception)
            }
        }
        response?.let {
            return parseReservationResult(it)
        }
        return false
    }

    private fun checkUserInfoLoaded() {
        ignoreSsl()
        val (response, fuelError) = fuelManager.get("https://vaccine.kakao.com/api/v1/user")
            .header(VACCINE_HEADER)
            .header(
                Headers.COOKIE,
                mutableListOf<String>().apply {
                    cookies.forEach { cookie ->
                        if (cookie is DecryptedCookie) {
                            add(cookie.name + "=" + cookie.decryptedValue)
                        }
                    }
                }.joinToString("; ")
            )
            .responseObject<UserInfoResult>()
            .third

        fun logFailToLoadUserInfo() {
            log.error("사용자 정보를 불러오는데 실패하였습니다.")
            log.error("Chrome 브라우저에서 카카오에 제대로 로그인되어있는지 확인해주세요.")
            log.error("로그인이 되어 있는데도 실행이 안 된다면, 카카오톡 접속 후, 잔여백신 알림을 신청해보세요. 정보제공 동의가 나온다면 동의 후 다시 시도해주세요.")
        }

        data class InvalidUserStateException(override val message: String?, val throwable: Throwable? = null) : IllegalStateException(message, throwable)

        fun userState(userInfo: UserInfo?) {
            when (userInfo?.status) {
                "NORMAL" -> {
                    log.info("사용자 정보를 불러오는데 성공했습니다. 사용자명: ${userInfo.name}")
                    return
                }
                "UNKNOWN" -> {
                    val unknownUserStr = "상태를 알 수 없는 사용자입니다. 1339 또는 보건소에 문의해주세요."
                    log.error(unknownUserStr)
                    close(InvalidUserStateException(unknownUserStr))
                }
                "REFUSED" -> {
                    val refusedUserStr = "${userInfo.name}님은 백신을 예약하고 방문하지 않았던 것으로 보입니다. 잔여백신 예약이 불가능합니다."
                    log.error(refusedUserStr)
                    close(InvalidUserStateException(refusedUserStr))
                }
                "ALREADY_RESERVED" -> {
                    val alreadyReservedUserStr = "${userInfo.name}님은 이미 백신 예약이 완료되어있습니다.."
                    log.error(alreadyReservedUserStr)
                    close(InvalidUserStateException(alreadyReservedUserStr))
                }
                "ALREADY_VACCINATED" -> {
                    val alreadyVaccinatedUserStr = "${userInfo.name}님은 이미 접종이 완료되었습니다."
                    log.error(alreadyVaccinatedUserStr)
                    close(InvalidUserStateException(alreadyVaccinatedUserStr))
                }
                else -> {
                    val unknownStr = "알려지지 않은 상태 코드입니다. 상태코드: ${userInfo?.status}"
                    log.error(unknownStr)
                    close(InvalidUserStateException(unknownStr))
                }
            }
        }

        fuelError?.let {
            logFailToLoadUserInfo()
            if (it.exception !is JacksonException) {
                val userInfoResult: UserInfoResult = mapper.readValue(it.errorData)
                userInfoResult.user?.let { userInfo ->
                    userState(userInfo)
                }
                userInfoResult.error?.let { error ->
                    close(InvalidUserStateException(error))
                }
            }
            close(it.exception)
        }

        response?.let { userInfoResult ->
            userState(userInfoResult.user)
            return
        }

        logFailToLoadUserInfo()
        close(RuntimeException("알 수 없는 오류"))
    }

    fun start() {
        checkUserInfoLoaded()
        showOrganizationList()
        findVaccine()
        close()
    }

    private fun close(throwable: Throwable? = null) {
        val exitCode = if (throwable !== null) {
            playSound(App::class.java.getResourceAsStream("/xylophon.wav")!!)
            sendError("잔여백신 예약 실패.", throwable)
            1
        } else {
            playSound(App::class.java.getResourceAsStream("/tada.wav")!!)
            sendSuccess("잔여백신 예약 성공!")
            sendSuccess("카카오톡 지갑을 확인하세요.")
            0
        }
        run {
            println("엔터를 누르면 종료합니다...")
            readLine()
        }
        exitProcess(exitCode)
    }

    private fun sendSuccess(message: String) {
        log.info(message)
        if (::telegramBot.isInitialized) {
            telegramBot.execute(SendMessage(telegramBotConfig.chatId, "\uD83D\uDFE2" + message))
        }
    }

    private fun sendError(message: String, throwable: Throwable) {
        log.error(message, throwable)
        if (::telegramBot.isInitialized) {
            telegramBot.execute(
                SendMessage(
                    telegramBotConfig.chatId,
                    "\uD83D\uDD34$message\nStacktrace:\n" + StringWriter().apply {
                        throwable.printStackTrace(PrintWriter(this))
                    }.toString()
                )
            )
        }
    }

    companion object {
        const val UNUSED = "(미사용)"

        val NORMAL_HEADERS = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json;charset=utf-8",
            "Origin" to "https://vaccine-map.kakao.com",
            "Accept-Language" to "en-us",
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 14_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 9.4.2",
            "Referer" to "https://vaccine-map.kakao.com/"
        )

        val NAVER_HEADERS = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json;charset=utf-8",
            "Origin" to "https://m.place.naver.com",
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 14_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 9.4.2",
            "Referer" to "https://m.place.naver.com/rest/vaccine?vaccineFilter=used"
        )

        val VACCINE_HEADER = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json;charset=utf-8",
            "Origin" to "https://vaccine.kakao.com",
            "Accept-Language" to "en-us",
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 14_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 KAKAOTALK 9.4.2",
            "Referer" to "https://vaccine.kakao.com/"
        )

        val VACCINE_CANDIDATES = arrayOf(
            VaccineType("아무거나", "ANY"),
            VaccineType("화이자", "VEN00013"),
            VaccineType("모더나", "VEN00014"),
            VaccineType("AZ", "VEN00015"),
            VaccineType("얀센", "VEN00016"),
            VaccineType(UNUSED, "VEN00017"),
            VaccineType(UNUSED, "VEN00018"),
            VaccineType(UNUSED, "VEN00019"),
            VaccineType(UNUSED, "VEN00020")
        )

        val VACCINE_CANDIDATES_MAP = mapOf(
            "화이자" to VACCINE_CANDIDATES[1],
            "모더나" to VACCINE_CANDIDATES[2],
            "AZ" to VACCINE_CANDIDATES[3],
            "얀센" to VACCINE_CANDIDATES[4]
        )

        val KAKAO_TO_NAVER_MAP = mapOf(
            VACCINE_CANDIDATES_MAP.getValue("화이자").code to "화이자",
            VACCINE_CANDIDATES_MAP.getValue("모더나").code to "모더나",
            VACCINE_CANDIDATES_MAP.getValue("AZ").code to "AZ",
            VACCINE_CANDIDATES_MAP.getValue("얀센").code to "얀센"
        )
    }
}

fun main() {
    App().start()
}
