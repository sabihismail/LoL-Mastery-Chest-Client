package ui.views

import DEBUG_FAKE_UI_DATA_ARAM
import DEBUG_FAKE_UI_DATA_NORMAL
import javafx.beans.property.*
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.ScrollPane
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import league.api.LeagueCommunityDragonApi
import league.models.ChallengeFilter
import league.models.ChallengeUiRefreshData
import league.models.enums.ChallengeCategory
import league.models.enums.ChallengeLevel
import league.models.enums.GameMode
import league.models.enums.ImageCacheType
import league.models.json.ChallengeInfo
import league.models.json.ChallengeSummary
import tornadofx.*
import ui.controllers.MainViewController
import ui.mock.AramMockController
import ui.mock.NormalMockController
import util.constants.ViewConstants.DEFAULT_SPACING
import util.constants.ViewConstants.SCROLLBAR_HEIGHT


class ChallengesView : View("LoL Challenges") {
    private val challengesSummaryProperty = SimpleObjectProperty<ChallengeSummary>()
    private val categoriesProperty = SimpleListProperty<ChallengeCategory>()
    private val allChallengesProperty = SimpleMapProperty<ChallengeCategory, List<ChallengeInfo>>()
    private val filteredChallengesProperty = SimpleMapProperty<ChallengeCategory, List<ChallengeInfo>>()

    private val hideEarnPointChallengesProperty = SimpleBooleanProperty(true)
    private val hideCompletedChallengesProperty = SimpleBooleanProperty(true)
    private val showOnlyTitleChallengesProperty = SimpleBooleanProperty(false)
    private val currentSearchTextProperty = SimpleStringProperty("")

    val currentGameModeProperty = SimpleObjectProperty(GameMode.CLASSIC)

    private lateinit var grid: DataGrid<ChallengeCategory>

    fun setChallenges(summary: ChallengeSummary = challengesSummaryProperty.value,
                      challengeInfo: Map<ChallengeCategory, List<ChallengeInfo>> = allChallengesProperty.value,
                      categories: List<ChallengeCategory> = categoriesProperty.value) {
        runAsync {
            val filters = listOf(
                ChallengeFilter(hideEarnPointChallengesProperty.get()) { challengeInfo ->
                    !CRINGE_MISSIONS.any { x -> challengeInfo.description!!.contains(x) }
                },

                ChallengeFilter(hideCompletedChallengesProperty.get()) { challengeInfo -> !challengeInfo.isComplete },

                ChallengeFilter(showOnlyTitleChallengesProperty.get()) { challengeInfo -> challengeInfo.hasRewardTitle },

                ChallengeFilter(true) { challengeInfo ->
                    if (currentGameModeProperty.value == GameMode.ALL) true else challengeInfo.gameModeSet.contains(currentGameModeProperty.value)
                },

                ChallengeFilter(currentSearchTextProperty.value.isNotEmpty()) { challengeInfo ->
                    challengeInfo.description!!.lowercase().contains(currentSearchTextProperty.value.lowercase())
                },
            )

            val sortedMap = challengeInfo.toList().associate { (k, v) -> k to v.filter { challengeInfo -> filters.filter { it.isSet }.all { it.action(challengeInfo) } } }

            ChallengeUiRefreshData(summary, FXCollections.observableMap(challengeInfo), FXCollections.observableMap(sortedMap), FXCollections.observableList(categories))
        } ui {
            challengesSummaryProperty.value = it.challengesSummary
            categoriesProperty.value = it.categories
            allChallengesProperty.value = it.allChallenges
            filteredChallengesProperty.value = it.filteredChallenges

            grid.cellWidth = (CHALLENGE_IMAGE_WIDTH + DEFAULT_SPACING * 2) *
                    (categoriesProperty.maxOfOrNull { key -> filteredChallengesProperty[key]!!.size } ?: 1)
        }
    }

    @Suppress("unused")
    private val controller = find(
        if (DEBUG_FAKE_UI_DATA_ARAM) AramMockController::class
        else if (DEBUG_FAKE_UI_DATA_NORMAL) NormalMockController::class
        else MainViewController::class
    )

    init {
        //ROW_COUNT = controller.leagueConnection.challengeInfo.keys.size
        //OUTER_GRID_PANE_HEIGHT = (INNER_CELL_HEIGHT + SPACING_BETWEEN_ROW * 2) * ROW_COUNT + DEFAULT_SPACING * 2

        setOf(
            hideEarnPointChallengesProperty,
            hideCompletedChallengesProperty,
            showOnlyTitleChallengesProperty,
            currentGameModeProperty,
            currentSearchTextProperty,
        ).forEach { it.onChange { setChallenges() } }
    }

    private fun getWorldPercentage(percentage: Double): String {
        return "Top " + "%.2f".format(percentage) + "% World"
    }

    private fun getChallengeString(level: ChallengeLevel, key: String, current: Long, s: String = " - "): String {
        val maxPoints = LeagueCommunityDragonApi.getChallenge(key, ChallengeLevel.values()[level.ordinal + 1])
        val currentPercentage = "%.2f".format(current.toDouble().div(maxPoints) * 100) + "%"

        return "$level$s$currentPercentage ($current/$maxPoints)"
    }

    override val root = vbox {
        alignment = Pos.CENTER
        minWidth = 820.0

        hbox {
            spacing = 0.0

            label(challengesSummaryProperty.select {
                getChallengeString(it.overallChallengeLevel!!, TOTAL_CHALLENGE_POINTS_KEY, it.totalChallengeScore!!).toProperty()
            }) {
                textFill = Color.WHITE
                font = Font.font(Font.getDefault().family, FontWeight.BOLD, HEADER_FONT_SIZE + 2.0)
                paddingHorizontal = 16.0
                paddingVertical = 0.0

                fitToParentWidth()
                style {
                    backgroundColor += Color.BLACK
                }
            }

            label(challengesSummaryProperty.select { getWorldPercentage(it.positionPercentile!!).toProperty() }) {
                textFill = Color.WHITE
                alignment = Pos.TOP_RIGHT
                font = Font.font(Font.getDefault().family, FontWeight.BOLD, HEADER_FONT_SIZE + 2.0)
                paddingHorizontal = 8.0

                fitToParentWidth()
                style {
                    backgroundColor += Color.BLACK
                }
            }
        }

        scrollpane(fitToWidth = true) {
            vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            minHeight = OUTER_GRID_PANE_HEIGHT
            maxHeight = OUTER_GRID_PANE_HEIGHT

            grid = datagrid(categoriesProperty) {
                maxCellsInRow = 1
                verticalCellSpacing = SPACING_BETWEEN_ROW
                cellHeight = INNER_CELL_HEIGHT
                minHeight = OUTER_GRID_PANE_HEIGHT

                cellFormat {
                    graphic = vbox {
                        alignment = Pos.CENTER_LEFT
                        maxHeight = INNER_CELL_HEIGHT
                        minHeight = INNER_CELL_HEIGHT

                        label(
                            challengesSummaryProperty.select { summary ->
                                val category = summary.categoryProgress!!.firstOrNull { category -> category.category == it }
                                if (category == null)
                                    it.name.toProperty()
                                else
                                    (it.name + " (" + getChallengeString(category.level!!, category.category!!.name, category.current!!.toLong(), s=") - ") + " --- " +
                                            getWorldPercentage(category.positionPercentile!!)).toProperty()
                            }
                        ) {
                            textFill = Color.WHITE
                            font = Font.font(HEADER_FONT_SIZE)
                            paddingHorizontal = 8.0

                            fitToParentWidth()
                            style {
                                backgroundColor += Color.BLACK
                            }
                        }

                        datagrid(filteredChallengesProperty[it]) {
                            alignment = Pos.CENTER
                            maxRows = 1
                            cellWidth = CHALLENGE_IMAGE_WIDTH
                            cellHeight = CHALLENGE_IMAGE_WIDTH

                            cellFormat {
                                graphic = stackpane {
                                    alignment = Pos.TOP_CENTER
                                    maxHeight = CHALLENGE_IMAGE_WIDTH

                                    imageview {
                                        fitWidth = CHALLENGE_IMAGE_WIDTH
                                        fitHeight = CHALLENGE_IMAGE_WIDTH

                                        val currentLevel = if (it.currentLevel == ChallengeLevel.NONE)
                                            ChallengeLevel.IRON.name.lowercase()
                                        else
                                            it.currentLevel!!.name.lowercase()

                                        image = LeagueCommunityDragonApi.getImage(ImageCacheType.CHALLENGE, it.id!!, currentLevel).apply {
                                            effect = LeagueCommunityDragonApi.getChallengeImageEffect(it)
                                        }
                                    }

                                    label(it.description!!) {
                                        textFill = Color.WHITE
                                        textAlignment = TextAlignment.CENTER
                                        isWrapText = true
                                        paddingHorizontal = 8
                                        font = Font.font(9.0)

                                        style {
                                            backgroundColor += Color.BLACK
                                        }
                                    }

                                    stackpane {
                                        vbox {
                                            alignment = Pos.BOTTOM_CENTER

                                            if (it.hasRewardTitle) {
                                                label("Title: ${it.rewardTitle}" + if (it.rewardObtained) " ✓" else " (${it.rewardLevel.toString()[0]})") {
                                                    textFill = Color.WHITE
                                                    textAlignment = TextAlignment.CENTER
                                                    isWrapText = true
                                                    paddingHorizontal = 8
                                                    font = Font.font(9.0)

                                                    style {
                                                        backgroundColor += Color.BLACK
                                                    }
                                                }
                                            }

                                            label("${it.currentLevel} (${it.thresholds!!.keys.sorted().indexOf(it.currentLevel) + 1}/${it.thresholds!!.count()})") {
                                                textFill = Color.WHITE
                                                textAlignment = TextAlignment.CENTER
                                                isWrapText = true
                                                paddingHorizontal = 8
                                                font = Font.font(9.0)

                                                style {
                                                    backgroundColor += Color.BLACK
                                                }
                                            }

                                            label("${it.currentValueProper}/${it.nextLevelValueProper} (+${it.nextLevelPoints})") {
                                                textFill = Color.WHITE
                                                textAlignment = TextAlignment.CENTER
                                                isWrapText = true
                                                paddingHorizontal = 8
                                                font = Font.font(9.0)

                                                tooltip(it.thresholdSummary) {
                                                    style {
                                                        font = Font.font(9.0)
                                                    }
                                                }

                                                style {
                                                    backgroundColor += Color.BLACK
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        vbox {
            textfield {
                textProperty().addListener { _, _, newValue ->
                    currentSearchTextProperty.set(newValue)
                }
            }

            hbox {
                paddingHorizontal = 10.0
                spacing = 10.0

                checkbox("Hide Grind/Time Missions", hideEarnPointChallengesProperty)
                checkbox("Hide Completed Missions", hideCompletedChallengesProperty)
                checkbox("Show Title Missions", showOnlyTitleChallengesProperty)
                combobox(currentGameModeProperty, listOf(GameMode.ALL, GameMode.ARAM, GameMode.CLASSIC))
            }
        }
    }

    companion object {
        private const val TOTAL_CHALLENGE_POINTS_KEY = "CRYSTAL"

        private const val HEADER_FONT_SIZE = 14.0
        private const val SPACING_BETWEEN_ROW = 4.0
        private const val CHALLENGE_IMAGE_WIDTH = 112.0

        // image_height + 2 * verticalCellSpacing + font size of label
        private const val INNER_CELL_HEIGHT = CHALLENGE_IMAGE_WIDTH + (DEFAULT_SPACING * 2) + HEADER_FONT_SIZE

        private var ROW_COUNT = 6
        // cell + row_spacing for 6 rows + vert spacing
        private var OUTER_GRID_PANE_HEIGHT = (INNER_CELL_HEIGHT + SPACING_BETWEEN_ROW * 2) * ROW_COUNT + DEFAULT_SPACING * 2 + SCROLLBAR_HEIGHT

        private val CRINGE_MISSIONS = setOf(
            "Earn points from challenges in the ",
        )
    }
}