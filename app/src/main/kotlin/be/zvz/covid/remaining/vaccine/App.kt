package be.zvz.covid.remaining.vaccine

import cmonster.browsers.ChromeBrowser
import cmonster.cookies.DecryptedCookie
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

    data class FindVaccineResult(
        val organizations: List<Organization>
    )

    data class Organization(
        val status: String,
        val leftCounts: Int,
        val orgName: String,
        val orgCode: String,
        val address: String
    )

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
            ignoreSsl()
            val (response, fuelError) = fuelManager
                .post("https://vaccine-map.kakao.com/api/v3/vaccine/left_count_by_coords")
                .body(
                    "{\"bottomRight\":{\"x\":${config.bottom.x},\"y\":${config.bottom.y}},\"onlyLeft\":false,\"order\":\"latitude\"," +
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
                result.organizations.forEach {
                    if (it.status == "AVAILABLE" || it.leftCounts != 0) {
                        log.info("${it.orgName}에서 백신을 ${it.leftCounts}개 발견했습니다.")
                        log.info("주소는 ${it.address}입니다.")

                        val vaccineFoundCode = if (config.vaccineType == "ANY") {
                            data class OrganizationInfo(
                                val leftCount: Int,
                                val vaccineName: String,
                                val vaccineCode: String
                            )

                            data class OrganizationInfos(
                                val lefts: List<OrganizationInfo>
                            )

                            ignoreSsl()
                            val (checkOrganizationResponse, checkOrganizationFuelError) = fuelManager
                                .get("https://vaccine.kakao.com/api/v3/org/org_code/${it.orgCode}")
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
                                .responseObject<OrganizationInfos>()
                                .third

                            checkOrganizationFuelError?.let { fe ->
                                close(fe.exception)
                            }
                            checkOrganizationResponse?.let { infos ->
                                infos.lefts.forEach { info ->
                                    if (info.leftCount != 0) {
                                        log.info("${info.vaccineName} 백신을 ${info.leftCount}개 발견했습니다.")
                                        info.vaccineCode
                                    } else {
                                        log.error("${info.vaccineName} 백신이 없습니다.")
                                    }
                                }
                            }
                            return@forEach
                        } else {
                            config.vaccineType
                        }

                        log.info("$vaccineFoundCode 백신으로 예약을 시도합니다.")

                        if (tryReservation(it.orgCode, vaccineFoundCode)) {
                            close()
                        }
                    }
                }
            }
            log.info("잔여백신이 없습니다.")
            Thread.sleep((config.searchTime * 1000L).toLong())
        }
    }

    data class ReservationOrganization(
        val orgName: String,
        val phoneNumber: String,
        val address: String
    )

    data class ReservationResult(
        val code: String,
        val organization: ReservationOrganization?
    )

    private fun tryReservation(orgCode: String, vaccineCode: String, retry: Boolean = false): Boolean {
        ignoreSsl()
        val (response, fuelError) = fuelManager.post("https://vaccine.kakao.com/api/v2/reservation" + if (retry) "/retry" else "")
            .body(
                "{\"from\":\"Map\",\"vaccineCode\":\"$vaccineCode\"," +
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

        fuelError?.let {
            log.error("오류. 아래 메시지를 보고, 예약이 신청된 병원 또는 1339에 예약이 되었는지 확인해보세요.")
            close(it.exception)
        }
        response?.let {
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
        return false
    }

    private fun checkUserInfoLoaded() {
        data class UserInfo(
            val status: String?
        )
        data class UserInfoResult(
            val user: UserInfo?,
            val error: String?
        )

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

        fuelError?.let {
            logFailToLoadUserInfo()
            close(it.exception)
        }
        response?.let { userInfoResult ->
            when (userInfoResult.user?.status) {
                "NORMAL" -> {
                    log.info("사용자 정보를 불러오는데 성공했습니다.")
                    return
                }
                "UNKNOWN" -> {
                    log.info("상태를 알 수 없는 사용자입니다. 1339 또는 보건소에 문의해주세요.")
                    close(RuntimeException("상태를 알 수 없음"))
                }
                else -> {
                    log.info("이미 접종이 완료되었거나 예약이 완료된 사용자입니다.")
                    close()
                }
            }
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
            VaccineType("아스트라제네카", "VEN00015"),
            VaccineType("얀센", "VEN00016"),
            VaccineType(UNUSED, "VEN00017"),
            VaccineType(UNUSED, "VEN00018"),
            VaccineType(UNUSED, "VEN00019"),
            VaccineType(UNUSED, "VEN00020")
        )
    }
}

fun main() {
    App().start()
}
