package be.zvz.covid.remaining.vaccine

import be.zvz.covid.remaining.vaccine.dto.config.Config
import be.zvz.covid.remaining.vaccine.dto.config.Latitude
import be.zvz.covid.remaining.vaccine.dto.config.TelegramBotConfig
import be.zvz.covid.remaining.vaccine.dto.config.VaccineType
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
import java.net.SocketTimeoutException
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

    private val centerX: Double
    private val centerY: Double
    private val queryBody: String

    init {
        val configFile = File("config.json")
        config = if (configFile.exists()) {
            print("????????? ????????? ????????? ????????? ??????????????????? Y/N : ")
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
                log.info("???????????? ????????? ????????????????????????!")
            }
        } catch (ignored: IOException) {
            log.error("???????????? ??? ?????? ??????(telegram_config.json)??? ????????? ??????, ???????????? ????????? ??????????????? ???????????????.")
        }

        centerX = min(config.top.x, config.bottom.x) +
            (max(config.top.x, config.bottom.x) - min(config.top.x, config.bottom.x) / 2)
        centerY = min(config.top.y, config.bottom.y) +
            (max(config.top.y, config.bottom.y) - min(config.top.y, config.bottom.y) / 2)
        queryBody = mapper.writeValueAsString(
            mutableListOf<MapSearchInput>().apply {
                add(
                    MapSearchInput(
                        operationName = "vaccineList",
                        variables = MapSearchInput.Variables(
                            input = MapSearchInput.Variables.Input(
                                keyword = "?????????????????????????????????",
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
                            )
                        ),
                        query = "query vaccineList(\$input:RestsInput,\$businessesInput:RestsBusinessesInput){" +
                            "rests(input:\$input){" +
                            "businesses(input:\$businessesInput){" +
                            "items{" +
                            "id\n" +
                            "name\n" +
                            "roadAddress\n" +
                            "vaccineQuantity{" +
                            "totalQuantity\n" +
                            "totalQuantityStatus\n" +
                            "vaccineOrganizationCode\n" +
                            "list{" +
                            "quantity\n" +
                            "quantityStatus\n" +
                            "vaccineType" +
                            "}}}}}}"
                    )
                )
            }
        )
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
        println("=== ?????? ?????? ===")
        VACCINE_CANDIDATES.forEach { (name, code) ->
            if (name == UNUSED) {
                return@forEach
            }
            println(name.padEnd(10) + " : " + code)
        }

        fun getVaccineType(): VaccineType {
            val vaccineCode = run {
                print("?????? ????????? ?????? ????????? ??????????????? : ")
                readLine()!!
            }

            if (vaccineCode.startsWith("FORCE:")) {
                val forceNameAndCode = vaccineCode.split("FORCE:")[1].trim().split(':')
                val forceName = forceNameAndCode[1].trim()
                val forceCode = forceNameAndCode[2].trim()
                println(
                    "??????: ?????? ?????? ??????????????? ?????????????????????.\n" +
                        "??? ????????? ????????? ????????? ????????? ????????? '???????????? ?????? ????????????' ???????????? ?????????.\n" +
                        "???????????? ????????? ??????????????? ???????????? ?????? ???????????? ?????? ??????????????????.\n" +
                        "?????? ??????: $forceName\n" +
                        "?????? ??????: $forceCode"
                )
                val vaccineType = VaccineType(forceName, forceCode)
                VACCINE_CANDIDATES_MAP[vaccineType.name] = vaccineType.code
                KAKAO_TO_NAVER_MAP[vaccineType.code] = vaccineType.name
                return vaccineType
            }

            VACCINE_CANDIDATES.forEach {
                if (vaccineCode == it.code) {
                    return it
                }
            }
            println("?????? ????????? ??????????????????.")
            return getVaccineType()
        }

        val vaccineType = getVaccineType()
        println("?????? ????????? ???????????????. ?????? ?????? ?????? ?????? ????????? ???????????????.")

        fun getCoordinateFromInput(name: String, example: String): Double {
            print("???????????? ${name}?????? ???????????????. $example: ")
            return readLine()!!.toDoubleOrNull() ?: run {
                println("????????? ?????? ?????? ????????????.")
                getCoordinateFromInput(name, example)
            }
        }

        val topX = getCoordinateFromInput("?????? ?????? X", "1xx.xxxxxx")
        val topY = getCoordinateFromInput("?????? ?????? Y", "3x.xxxxxx")
        val bottomX = getCoordinateFromInput("?????? ?????? X", "1xx.xxxxxx")
        val bottomY = getCoordinateFromInput("?????? ?????? Y", "3x.xxxxxx")

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
            .post("https://api.place.naver.com/graphql")
            .body(queryBody)
            .header(NAVER_HEADERS)
            .timeout(5000)
            .responseObject<List<VaccineSearchResult>>(mapper = mapper)
            .third

        fuelError?.let {
            when {
                it.response.statusCode == 500 -> {
                    log.warn("?????? ?????? ?????? ???????????? ?????? ?????? (HTTP CODE 500). ??????????????????.")
                }
                it.exception is SocketTimeoutException -> {
                    log.warn("???????????? ??????. ??????????????????.")
                }
                else -> {
                    close(throwable = it.exception)
                }
            }
        }
        response?.let { result ->
            log.info("=== ????????? ?????? ?????? ===")
            result.first().data.rests?.businesses?.items?.forEachIndexed { index, organization ->
                log.info("${index + 1}: ${organization.name}")
            }
        }
    }

    private fun findVaccine() {
        while (true) {
            ignoreSsl()
            val (response, fuelError) = fuelManager
                .post("https://api.place.naver.com/graphql")
                .body(queryBody)
                .header(NAVER_HEADERS)
                .timeout(5000)
                .responseObject<List<VaccineSearchResult>>(mapper = mapper)
                .third

            fuelError?.let {
                when {
                    it.response.statusCode == 500 -> {
                        log.warn("?????? ?????? ?????? ???????????? ?????? ?????? (HTTP CODE 500). ??????????????????.")
                    }
                    it.exception is SocketTimeoutException -> {
                        log.warn("???????????? ??????. ??????????????????.")
                    }
                    else -> {
                        close(throwable = it.exception)
                    }
                }
            }

            response?.let { result ->
                result.first().data.rests?.businesses?.items?.forEach {
                    if (it.vaccineQuantity.totalQuantity > 0) {
                        log.info("${it.name}?????? ????????? ${it.vaccineQuantity.totalQuantity}??? ??????????????????.")
                        log.info("????????? ${it.roadAddress}?????????.")

                        var vaccineFoundCode: String? = null
                        when (config.vaccineType) {
                            ANY_TYPE -> {
                                it.vaccineQuantity.list.forEach { vaccineInfo ->
                                    if (vaccineInfo.quantity > 0) {
                                        vaccineFoundCode = VACCINE_CANDIDATES_MAP.getValue(vaccineInfo.vaccineType)
                                        // ??? ??? ?????? ?????? AZ ?????? ????????? / ????????? ??????
                                    }
                                }
                            }
                            PFI_MOD_TYPE -> {
                                for (vaccineInfo in it.vaccineQuantity.list) {
                                    if (vaccineInfo.quantity > 0) {
                                        when (vaccineInfo.vaccineType) {
                                            "?????????", "?????????" -> {
                                                vaccineFoundCode = VACCINE_CANDIDATES_MAP.getValue(vaccineInfo.vaccineType)
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                for (vaccineInfo in it.vaccineQuantity.list) {
                                    if (vaccineInfo.quantity > 0 && KAKAO_TO_NAVER_MAP[config.vaccineType] == vaccineInfo.vaccineType) {
                                        vaccineFoundCode = config.vaccineType
                                        break
                                    }
                                }
                            }
                        }

                        vaccineFoundCode?.let { code ->
                            log.info("${KAKAO_TO_NAVER_MAP[code]} ???????????? ????????? ???????????????.")

                            if (tryReservation(it.vaccineQuantity.vaccineOrganizationCode, code)) {
                                close()
                            }
                        } ?: run {
                            log.warn("????????? ????????? (${KAKAO_TO_NAVER_MAP[config.vaccineType]}) ????????? ????????????.")
                        }
                    }
                }
            }
            log.info("??????????????? ????????????.")
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

        fun parseReservationResult(it: ReservationResult): Boolean = when (it.code) {
            "NO_VACANCY" -> {
                log.error("???????????? ?????? ????????? ????????? ?????????????????????.")
                false
            }
            "TIMEOUT" -> {
                log.warn("TIMEOUT, ????????? ??????????????????.")
                tryReservation(orgCode, vaccineCode, true)
            }
            "SUCCESS" -> {
                sendSuccess(
                    "?????? ?????? ?????? ??????!" +
                        it.organization?.let { ro ->
                            "\n?????? ??????: ${ro.orgName}\n" +
                                "????????????: ${ro.phoneNumber}\n" +
                                "??????: ${ro.address}"
                        }
                )
                true
            }
            else -> {
                log.error("??? ??? ?????? ??????")
                false
            }
        }

        fuelError?.let {
            if (it.exception !is JacksonException) {
                val reservationResult = parseReservationResult(mapper.readValue(it.errorData))
                if (reservationResult) {
                    return reservationResult
                }
            } else {
                log.error("??????. ?????? ???????????? ??????, ????????? ????????? ?????? ?????? 1339??? ????????? ???????????? ??????????????????.")
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
            log.error("????????? ????????? ??????????????? ?????????????????????.")
            log.error("Chrome ?????????????????? ???????????? ????????? ???????????????????????? ??????????????????.")
            log.error("???????????? ?????? ???????????? ????????? ??? ?????????, ???????????? ?????? ???, ???????????? ????????? ??????????????????. ???????????? ????????? ???????????? ?????? ??? ?????? ??????????????????.")
        }

        data class InvalidUserStateException(override val message: String?, val throwable: Throwable? = null) : IllegalStateException(message, throwable)

        fun userState(userInfo: UserInfo?) {
            when (userInfo?.status) {
                "NORMAL" -> {
                    log.info("????????? ????????? ??????????????? ??????????????????. ????????????: ${userInfo.name}")
                    return
                }
                "UNKNOWN" -> {
                    val unknownUserStr = "????????? ??? ??? ?????? ??????????????????. 1339 ?????? ???????????? ??????????????????."
                    log.error(unknownUserStr)
                    close(InvalidUserStateException(unknownUserStr))
                }
                "REFUSED" -> {
                    val refusedUserStr = "${userInfo.name}?????? ????????? ???????????? ???????????? ????????? ????????? ????????????. ???????????? ????????? ??????????????????."
                    log.error(refusedUserStr)
                    close(InvalidUserStateException(refusedUserStr))
                }
                "ALREADY_RESERVED" -> {
                    val alreadyReservedUserStr = "${userInfo.name}?????? ?????? ?????? ????????? ????????????????????????.."
                    log.error(alreadyReservedUserStr)
                    close(InvalidUserStateException(alreadyReservedUserStr))
                }
                "ALREADY_VACCINATED" -> {
                    val alreadyVaccinatedUserStr = "${userInfo.name}?????? ?????? ????????? ?????????????????????."
                    log.error(alreadyVaccinatedUserStr)
                    close(InvalidUserStateException(alreadyVaccinatedUserStr))
                }
                else -> {
                    val unknownStr = "???????????? ?????? ?????? ???????????????. ????????????: ${userInfo?.status}"
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
        close(RuntimeException("??? ??? ?????? ??????"))
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
            sendError("???????????? ?????? ??????.", throwable)
            1
        } else {
            playSound(App::class.java.getResourceAsStream("/tada.wav")!!)
            sendSuccess("???????????? ?????? ??????!")
            sendSuccess("???????????? ????????? ???????????????.")
            0
        }
        run {
            println("????????? ????????? ???????????????...")
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
        const val UNUSED = "(?????????)"

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

        const val ANY_TYPE = "ANY"
        const val PFI_MOD_TYPE = "PFIMOD"

        val VACCINE_CANDIDATES = arrayOf(
            VaccineType("????????????", ANY_TYPE),
            VaccineType("????????? & ?????????", PFI_MOD_TYPE),
            VaccineType("?????????", "VEN00013"),
            VaccineType("?????????", "VEN00014"),
            VaccineType("AZ", "VEN00015"),
            VaccineType("??????", "VEN00016"),
            VaccineType(UNUSED, "VEN00017"),
            VaccineType(UNUSED, "VEN00018"),
            VaccineType(UNUSED, "VEN00019"),
            VaccineType(UNUSED, "VEN00020")
        )

        val VACCINE_CANDIDATES_MAP = VACCINE_CANDIDATES.associate { (name, code) -> name to code }.toMutableMap()
        val KAKAO_TO_NAVER_MAP = VACCINE_CANDIDATES_MAP.entries.associate { (name, code) -> code to name }.toMutableMap()
    }
}

fun main() {
    App().start()
}
