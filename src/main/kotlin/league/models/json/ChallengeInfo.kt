package league.models.json

import kotlinx.serialization.Serializable
import league.models.enums.ChallengeCategory
import league.models.enums.ChallengeLevel
import league.models.enums.ChallengeThresholdRewardCategory
import league.models.enums.GameMode
import util.KotlinExtensionUtil.toReadableNumber


@Serializable
@Suppress("unused")
class ChallengeInfo {
    var id: Long? = null
    var name: String? = null
    var description: String? = null
    var capstoneGroupId: Long? = null
    var capstoneGroupName: String? = null
    var capstoneId: Long? = null
    //var category: String? = null
    //var currentLevel: String? = null
    var currentLevelAchievedTime: Long? = null
    var currentThreshold: Double? = null
    var currentValue: Double? = null
    var descriptionShort: String? = null
    var gameModes: List<String>? = null
    var hasLeaderboard: Boolean? = null
    var iconPath: String? = null
    var isApex: Boolean? = null
    var isCapstone: Boolean? = null
    var isReverseDirection: Boolean? = null
    var nextLevel: ChallengeLevel? = null
    var nextLevelIconPath: String? = null
    var nextThreshold: Double? = null
    var percentile: Double? = null
    var pointsAwarded: Long? = null
    var position: Int? = null
    var previousLevel: String? = null
    var previousValue: Double? = null
    var source: String? = null
    //var thresholds: Any? = null
    var valueMapping: String? = null

    var thresholds: Map<ChallengeLevel, ChallengeThreshold>? = null
    var category: ChallengeCategory? = null
    var currentLevel: ChallengeLevel? = null

    val thresholdSummary by lazy {
        try {
            thresholds!!.toList().sortedBy { it.first }.filter { it.first > nextLevel!! }
                .joinToString(THRESHOLD_SEPARATOR) { it.second.value!!.toLong().toReadableNumber() }
        } catch (_: Exception) {
            ""
        }
    }

    val thresholdSummaryOneLiner by lazy {
        val maxNum = 18

        if (thresholdSummary.length <= maxNum) {
            thresholdSummary
        } else {
            val s = StringBuilder()

            for (value in thresholdSummary.split(THRESHOLD_SEPARATOR)) {
                if (s.length > maxNum - "...".length) break
                s.append(value)
                s.append(THRESHOLD_SEPARATOR)
            }

            val str = s.toString()
            str.substring(0, str.length - THRESHOLD_SEPARATOR.length) + "..."
        }
    }

    val currentLevelImage by lazy {
        if (currentLevel == ChallengeLevel.NONE)
            ChallengeLevel.IRON.name.lowercase()
        else
            currentLevel!!.name.lowercase()
    }

    val isComplete get() = currentLevel == thresholds!!.keys.maxOf { x -> x }
    var rewardTitle = ""
    var rewardLevel = ChallengeLevel.NONE
    val rewardObtained get() = rewardLevel <= currentLevel!!
    var hasRewardTitle = false
    var gameModeSet = setOf<GameMode>()
    val levelByThreshold get() = thresholds!!.keys.sorted().indexOf(currentLevel) + 1

    val percentage get() = currentValue!!.toDouble() / nextThreshold!!
    val nextLevelPoints get() = try {
        thresholds!![nextLevel]!!.rewards!!.firstOrNull { it.category == ChallengeThresholdRewardCategory.CHALLENGE_POINTS }!!.quantity!!.toInt()
    } catch (_: Exception) {
        -1
    }

    fun init() {
        initGameMode()
        initRewardTitle()
    }

    private fun initGameMode() {
        gameModeSet = gameModes!!.map { GameMode.valueOf(it) }.toSet()
    }

    private fun initRewardTitle() {
        val rewardCategory = thresholds!!.map { it.key to it.value.rewards!!.firstOrNull { reward -> reward.category == ChallengeThresholdRewardCategory.TITLE } }
            .firstOrNull { it.second != null }
        if (rewardCategory != null) {
            rewardTitle = rewardCategory.second!!.name.toString()
            rewardLevel = rewardCategory.first
            hasRewardTitle = true
            return
        }

        hasRewardTitle = false
    }

    operator fun minus(other: ChallengeInfo): Int {
        return (currentValue!! - other.currentValue!!).toInt()
    }

    override fun toString(): String {
        return "ChallengeInfo(id=$id, name=$name, description=$description, capstoneGroupId=$capstoneGroupId, capstoneGroupName=$capstoneGroupName, " +
                "capstoneId=$capstoneId, currentLevelAchievedTime=$currentLevelAchievedTime, currentThreshold=$currentThreshold, currentValue=$currentValue, " +
                "descriptionShort=$descriptionShort, gameModes=$gameModes, hasLeaderboard=$hasLeaderboard, iconPath=$iconPath, " +
                "isApex=$isApex, isCapstone=$isCapstone, isReverseDirection=$isReverseDirection, nextLevel=$nextLevel, " +
                "nextLevelIconPath=$nextLevelIconPath, nextThreshold=$nextThreshold, percentile=$percentile, pointsAwarded=$pointsAwarded, position=$position, " +
                "previousLevel=$previousLevel, previousValue=$previousValue, source=$source, valueMapping=$valueMapping, thresholds=$thresholds, category=$category, " +
                "currentLevel=$currentLevel, rewardTitle='$rewardTitle', rewardLevel=$rewardLevel, hasRewardTitle=$hasRewardTitle)"
    }

    companion object {
        const val THRESHOLD_SEPARATOR = " > "
    }
}