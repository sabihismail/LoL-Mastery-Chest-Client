package league.api

import com.stirante.lolclient.libs.com.google.gson.reflect.TypeToken
import javafx.scene.effect.*
import javafx.scene.image.Image
import league.models.CacheInfo
import league.models.ChampionInfo
import league.models.enums.CacheType
import league.models.enums.ChallengeLevel
import league.models.enums.ChampionOwnershipStatus
import league.models.enums.Role
import league.models.json.*
import util.LogType
import util.Logging
import util.StringUtil
import util.constants.GenericConstants.GSON
import util.constants.ViewConstants.CHAMPION_STATUS_AVAILABLE_CHEST_COLOR
import util.constants.ViewConstants.CHAMPION_STATUS_UNAVAILABLE_CHEST_COLOR
import util.constants.ViewConstants.IMAGE_WIDTH
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.*
import kotlin.reflect.KMutableProperty0


object LeagueCommunityDragonApi {
    var VERSION = Paths.get(Paths.get("").toAbsolutePath().toString(), "/cache/json")
        .listDirectoryEntries()
        .map { it.name.replace("_", ".") }
        .sorted()
        .firstOrNull { it != "latest" } ?: "latest"

    var CHAMPION_ROLE_MAPPING = hashMapOf<Role, HashMap<Int, Float>>()
    var QUEUE_MAPPING = hashMapOf<Int, ApiQueueInfoResponse>()
    var CHALLENGE_MAPPING = hashMapOf<String, Long>()
    var ETERNALS_MAPPING = hashMapOf<String, List<Pair<Int, String>>>()

    private val CHAMPION_ROLE_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/plugins/rcp-fe-lol-champion-statistics/global/default/rcp-fe-lol-champion-statistics.js"
    }
    private val QUEUE_TYPE_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/queues.json"
    }
    private val CHALLENGES_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/challenges.json"
    }
    private val CHAMPION_PORTRAIT_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/champion-icons/%s.png"
    }
    private val ETERNALS_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/plugins/rcp-be-lol-game-data/global/default/v1/statstones.json"
    }
    private val CHALLENGE_IMAGE_ENDPOINT by lazy {
        "https://raw.communitydragon.org/$VERSION/game/assets/challenges/config/%s/tokens/%s.png"
    }

    private val CACHE_MAPPING by lazy {
        mapOf(
            CacheType.CHAMPION to CacheInfo("champion", CHAMPION_PORTRAIT_ENDPOINT),
            CacheType.CHALLENGE to CacheInfo("challenge", CHALLENGE_IMAGE_ENDPOINT),
            CacheType.JSON to CacheInfo("json/${VERSION.replace(".", "_")}")
        )
    }

    fun getChampionsByRole(role: Role): List<Int> {
        checkIfJsonCached(::CHAMPION_ROLE_MAPPING, ::populateRoleMapping)

        return CHAMPION_ROLE_MAPPING[role]?.map { it.key }!!
    }

    fun getQueueMapping(id: Int): ApiQueueInfoResponse {
        checkIfJsonCached(::QUEUE_MAPPING, ::populateQueueMapping)

        return QUEUE_MAPPING[id]!!
    }

    fun getChallenge(id: String, challengeLevel: ChallengeLevel): Long {
        checkIfJsonCached(::CHALLENGE_MAPPING, ::populateChallengeMapping)

        return CHALLENGE_MAPPING[id + challengeLevel.name]!!
    }

    fun getEternal(contentId: String): List<Pair<Int, String>> {
        checkIfJsonCached(::ETERNALS_MAPPING, ::populateEternalsMapping)

        return ETERNALS_MAPPING[contentId]!!
    }

    fun getImage(t: CacheType, vararg params: Any): Image {
        val path = getImagePath(t, *params)

        return Image(path!!.toUri().toString())
    }

    fun getPath(cacheType: CacheType): Path {
        val info = CACHE_MAPPING[cacheType]!!
        val path = Paths.get(Paths.get("").toAbsolutePath().toString(), "/cache/${info.folder.replace(".", "_")}")
        path.createDirectories()

        return path
    }

    fun getImagePath(cacheType: CacheType, vararg params: Any): Path? {
        val path = getPath(cacheType)

        if (path.notExists()) {
            path.createDirectory()
        }

        val imagePath = path.resolve(params.joinToString("-") + ".png")
        if (!imagePath.exists()) {
            val urlStr = CACHE_MAPPING[cacheType]!!.endpoint!!.format(*params)

            val connection = URL(urlStr).openConnection()
            connection.setRequestProperty("User-Agent", "LoL-Mastery-Box-Client")

            try {
                val readableByteChannel = Channels.newChannel(connection.getInputStream())
                val fileOutputStream = FileOutputStream(imagePath.toFile())

                fileOutputStream.channel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE)
            } catch (e: FileNotFoundException) {
                if (cacheType == CacheType.CHALLENGE) return null

                throw e
            }

            Logging.log("Image Download: '$imagePath'", LogType.INFO)
        }

        return imagePath
    }

    fun getChampionImageEffect(championInfo: ChampionInfo): Effect {
        if (championInfo.ownershipStatus == ChampionOwnershipStatus.NOT_OWNED || championInfo.ownershipStatus == ChampionOwnershipStatus.RENTAL ||
            championInfo.ownershipStatus == ChampionOwnershipStatus.FREE_TO_PLAY) {
            return ColorAdjust(0.0, -1.0, -0.7, -0.1)
        }

        val colorInput = ColorInput().apply {
            width = IMAGE_WIDTH
            height = IMAGE_WIDTH

            paint = if (championInfo.ownershipStatus == ChampionOwnershipStatus.BOX_ATTAINED)
                CHAMPION_STATUS_UNAVAILABLE_CHEST_COLOR
            else
                CHAMPION_STATUS_AVAILABLE_CHEST_COLOR
        }

        val blend = Blend().apply {
            mode = BlendMode.SRC_OVER
            opacity = 0.7
            topInput = colorInput
        }

        return blend
    }

    fun getChallengeImageEffect(challengeInfo: ChallengeInfo): Effect? {
        if (challengeInfo.currentLevel != ChallengeLevel.NONE) return null

        return ColorAdjust(0.0, -1.0, -0.7, -0.1)
    }

    private fun populateQueueMapping() {
        QUEUE_MAPPING.clear()

        val jsonStr = sendRequest(QUEUE_TYPE_ENDPOINT)
        val json = StringUtil.extractJSONMapFromString<ApiQueueInfoResponse>(jsonStr)

        QUEUE_MAPPING = HashMap(json.mapKeys { it.key.toInt() })
        addJsonCache(::QUEUE_MAPPING)
    }

    private fun populateRoleMapping() {
        CHAMPION_ROLE_MAPPING.clear()

        val jsonStr = sendRequest(CHAMPION_ROLE_ENDPOINT)
        val json = StringUtil.extractJSONFromString<RoleMapping>(jsonStr, "a.exports=")

        CHAMPION_ROLE_MAPPING[Role.TOP] = json.top
        CHAMPION_ROLE_MAPPING[Role.JUNGLE] = json.jungle
        CHAMPION_ROLE_MAPPING[Role.MIDDLE] = json.middle
        CHAMPION_ROLE_MAPPING[Role.BOTTOM] = json.bottom
        CHAMPION_ROLE_MAPPING[Role.SUPPORT] = if (json.support.isNullOrEmpty()) json.utility!! else json.support
        addJsonCache(::CHAMPION_ROLE_MAPPING)
    }

    private fun populateChallengeMapping() {
        CHALLENGE_MAPPING.clear()

        val jsonStr = sendRequest(CHALLENGES_ENDPOINT)
        val json = StringUtil.extractJSONFromString<ApiChallengeResponse>(jsonStr)

        CHALLENGE_MAPPING = HashMap(json.challenges.values.flatMap { c -> c.thresholds!!.map { (k, v) -> (c.name!! + k.name) to v.value!!.toLong() } }
            .toMap())
        addJsonCache(::CHALLENGE_MAPPING)
    }

    private fun populateEternalsMapping() {
        ETERNALS_MAPPING.clear()

        val jsonStr = sendRequest(ETERNALS_ENDPOINT)
        val json = StringUtil.extractJSONFromString<ApiEternalsResponse>(jsonStr)

        ETERNALS_MAPPING = HashMap(json.statstoneData.flatMap { data ->
            data.statstones.map { it.contentId to it.getMilestoneValues() }
        }.toMap())
        addJsonCache(::ETERNALS_MAPPING)
    }

    private fun sendRequest(url: String): String {
        val connection = URL(url).openConnection()
        connection.setRequestProperty("User-Agent", "LoL-Mastery-Box-Client")

        return connection.getInputStream().bufferedReader().use { it.readText() }
    }

    private fun <T1, T2> addJsonCache(data: KMutableProperty0<HashMap<T1, T2>>) {
        val json = GSON.toJson(data.get())

        val path = getPath(CacheType.JSON).resolve(data.name + ".json")
        path.deleteIfExists()
        path.createFile()
        path.writeText(json)
    }

    private inline fun <reified T> checkIfJsonCached(data: KMutableProperty0<T>, runnable: () -> Unit) {
        val path = getPath(CacheType.JSON).resolve(data.name + ".json")
        if (!path.exists()) {
            runnable()
            return
        }

        val jsonStr = path.readText()
        val json: T = GSON.fromJson(jsonStr, object: TypeToken<T>(){}.type)

        data.set(json)
    }
}