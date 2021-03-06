package league.models.json

import kotlinx.serialization.Serializable
import league.models.enums.ChallengeThresholdRewardCategory

@Serializable
@Suppress("unused")
class ChallengeThresholdReward {
    var asset: String? = null
    var category: ChallengeThresholdRewardCategory? = null
    var name: String? = null
    var quantity: Double? = null

    override fun toString(): String {
        return "ChallengeThresholdRewardInfo(asset=$asset, category=$category, name=$name, quantity=$quantity)"
    }
}