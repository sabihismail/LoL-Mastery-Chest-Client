package league.models.json

import kotlinx.serialization.Serializable
import league.models.enums.EternalTrackingType
import util.StringUtil

@Serializable
data class ApiEternalsListing(val name: String, val contentId: String, val boundChampion: ApiEternalsChampion, val milestones: List<Int>, val trackingType: Int) {
    private val trackingTypeValue get() = EternalTrackingType.values()[trackingType]

    fun getMilestoneValues(): List<String> {
        val elements = milestones.fold<Int, List<Int>>(listOf()) { acc, e -> acc + (acc.sum() + e) }
        return when(trackingTypeValue) {
            EternalTrackingType.COUNT -> {
                elements.map { it.toString() }
            }
            EternalTrackingType.TIME -> {
                elements.map { StringUtil.parseSecondsToHMS(it) }
            }
            EternalTrackingType.DISTANCE -> {
                elements.map { it.toString() }
            }
        }
    }

    override fun toString(): String {
        return "ApiEternalsListing(name='$name', contentId='$contentId', boundChampion=$boundChampion, milestones=$milestones, trackingType=$trackingType, " +
                "trackingTypeValue=$trackingTypeValue)"
    }
}