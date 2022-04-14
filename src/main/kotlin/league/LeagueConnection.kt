package league

import com.stirante.lolclient.*
import com.stirante.lolclient.libs.org.apache.http.HttpException
import com.stirante.lolclient.libs.org.apache.http.conn.HttpHostConnectException
import generated.*
import league.models.*
import league.models.Role
import tornadofx.*
import util.LogType
import util.Logging
import util.ProcessExecutor
import java.io.*
import java.net.ConnectException
import java.util.*
import kotlin.concurrent.thread

class LeagueConnection {
    var clientAPI: ClientApi? = null
    var socket: ClientWebSocket? = null

    var gameMode = GameMode.NONE
    var summonerInfo = SummonerInfo()
    var championSelectInfo = ChampionSelectInfo()
    var championInfo = mapOf<Int, ChampionInfo>()

    private var clientApiListener: ClientConnectionListener? = null

    private var clientState = LolGameflowGameflowPhase.NONE
    private var masteryChestInfo = MasteryChestInfo()

    private val onSummonerChangeList = ArrayList<(SummonerInfo) -> Unit>()
    private val onMasteryChestChangeList = ArrayList<(MasteryChestInfo) -> Unit>()
    private val onChampionSelectChangeList = ArrayList<(ChampionSelectInfo) -> Unit>()
    private val onClientStateChangeList = ArrayList<(LolGameflowGameflowPhase) -> Unit>()

    private var isConnected = false

    fun start() {
        thread {
            lateinit var console: ProcessExecutor

            while (true) {
                console = ProcessExecutor(PowershellProcessWatcher.EXECUTABLE, "--end-marker--")
                console.addFinalOutputListener { s: String ->
                    val isAlive = s.contains("LeagueClientUx.exe") && s.contains("--install-directory=")

                    if (isConnected != isAlive) {
                        isConnected = isAlive

                        Logging.log(if (isConnected) "Client Process Connected" else "Client Process Closed", LogType.INFO)
                        if (isConnected) {
                            startClientAPI()
                        } else {
                            stopClientAPI()
                        }
                    }
                }
                console.writeCommand(PowershellProcessWatcher.COMMAND)
                console.run()

                Thread.sleep(1000)
            }
        }
    }

    private fun startClientAPI() {
        thread {
            waitForLcuServerStart()

            Logging.log("Client Server running! Starting ClientAPI...", LogType.INFO)
            setupClientAPI()

            while (summonerInfo.status == SummonerStatus.NOT_CHECKED) {
                Thread.sleep(1000)
            }
        }
    }

    private fun stopClientAPI() {
        clientAPI?.stop()
        clientAPI?.removeClientConnectionListener(clientApiListener)
        socket?.close()

        socket = null
        clientAPI = null
        clientApiListener = null

        gameMode = GameMode.NONE
        summonerInfo = SummonerInfo()
        championSelectInfo = ChampionSelectInfo()
        championInfo = mapOf()

        clientState = LolGameflowGameflowPhase.NONE
        masteryChestInfo = MasteryChestInfo()
    }

    private fun waitForLcuServerStart() {
        var success = false
        while (!success) {
            try {
                LeagueConnectionUtil.tryLcuRequest {
                    Logging.log(it, LogType.DEBUG)
                }

                success = true
            } catch (e: Exception) {
                when(e) {
                    is HttpException, is ConnectException -> {
                        Logging.log("Client Server not yet running...", LogType.INFO)
                    }
                }
            }
        }
    }

    fun updateClientState() {
        clientState = clientAPI!!.executeGet("/lol-gameflow/v1/gameflow-phase", LolGameflowGameflowPhase::class.java).responseObject
        Logging.log(clientState, LogType.DEBUG)

        handleClientStateChange(clientState)

        when (clientState) {
            LolGameflowGameflowPhase.CHAMPSELECT -> {
                val championSelectSession = clientAPI!!.executeGet("/lol-champ-select/v1/session", LolChampSelectChampSelectSession::class.java).responseObject

                handleChampionSelectChange(championSelectSession)
            }
            else -> return
        }
    }

    fun updateChampionMasteryInfo() {
        val champions = clientAPI!!.executeGet("/lol-champions/v1/inventories/${summonerInfo.summonerID}/champions",
            Array<LolChampionsCollectionsChampion>::class.java).responseObject ?: return
        Logging.log(champions, LogType.VERBOSE)

        val championMasteryList = clientAPI!!.executeGet("/lol-collections/v1/inventories/${summonerInfo.summonerID}/champion-mastery",
            Array<LolCollectionsCollectionsChampionMastery>::class.java).responseObject ?: return
        Logging.log(championMasteryList, LogType.VERBOSE)

        val masteryPairing = champions.filter { it.id != -1 }
            .map {
                lateinit var championOwnershipStatus: ChampionOwnershipStatus
                var championPoints = 0
                var championLevel = 0
                var tokens = 0

                if (!it.ownership.owned) {
                    championOwnershipStatus = if (it.ownership.rental.rented) {
                        ChampionOwnershipStatus.RENTAL
                    } else if (it.freeToPlay) {
                        ChampionOwnershipStatus.FREE_TO_PLAY
                    } else {
                        ChampionOwnershipStatus.NOT_OWNED
                    }
                } else {
                    val championMastery = championMasteryList.firstOrNull { championMastery -> championMastery.championId == it.id }

                    if (championMastery == null) {
                        championOwnershipStatus = ChampionOwnershipStatus.BOX_NOT_ATTAINED
                    } else {
                        championOwnershipStatus = if (championMastery.chestGranted) ChampionOwnershipStatus.BOX_ATTAINED else ChampionOwnershipStatus.BOX_NOT_ATTAINED

                        championPoints = championMastery.championPoints
                        championLevel = championMastery.championLevel
                        tokens = championMastery.tokensEarned
                    }
                }

                ChampionInfo(it.id, it.name, championOwnershipStatus, championPoints, championLevel, tokens)
            }

        championInfo = masteryPairing.associateBy({ it.id }, { it })
    }

    fun updateMasteryChestInfo(force: Boolean = false) {
        if (!force && masteryChestInfo.nextChestDate != null && Calendar.getInstance().time.before(masteryChestInfo.nextChestDate)) {
            masteryChestChanged()
            return
        }

        val chestEligibility = clientAPI!!.executeGet("/lol-collections/v1/inventories/chest-eligibility",
            LolCollectionsCollectionsChestEligibility::class.java).responseObject

        handleMasteryChestChange(chestEligibility)
    }

    fun onSummonerChange(callable: (SummonerInfo) -> Unit) {
        onSummonerChangeList.add(callable)
    }

    fun onMasteryChestChange(callable: (MasteryChestInfo) -> Unit) {
        onMasteryChestChangeList.add(callable)
    }

    fun onChampionSelectChange(callable: (ChampionSelectInfo) -> Unit) {
        onChampionSelectChangeList.add(callable)
    }

    fun onClientStateChange(callable: (LolGameflowGameflowPhase) -> Unit) {
        onClientStateChangeList.add(callable)
    }

    private fun setupClientAPI() {
        clientAPI = ClientApi()

        clientApiListener = object : ClientConnectionListener {
            override fun onClientConnected() {
                var success = handleClientConnection()
                if (!success) {
                    while (summonerInfo.status == SummonerStatus.LOGGED_IN_UNAUTHORIZED) {
                        Thread.sleep(100)
                        success = handleClientConnection()
                    }

                    if (!success) return
                }

                updateChampionMasteryInfo()

                socket = clientAPI?.openWebSocket()
                socket?.setSocketListener(object : ClientWebSocket.SocketListener {
                    override fun onEvent(event: ClientWebSocket.Event?) {
                        if (event == null || event.uri == null || event.data == null) return

                        when (event.uri) {
                            "/lol-champ-select/v1/session" -> handleChampionSelectChange(event.data as LolChampSelectChampSelectSession)
                            "/lol-gameflow/v1/gameflow-phase" -> handleClientStateChange(event.data as LolGameflowGameflowPhase)
                            "/lol-login/v1/session" -> handleSignOnStateChange(event.data as LolLoginLoginSession)
                            "/lol-collections/v1/inventories/chest-eligibility" -> handleMasteryChestChange(event.data as LolCollectionsCollectionsChestEligibility)
                            else -> {
                                if (event.uri.contains("chest")) {
                                    Logging.log(event.data, LogType.DEBUG, event.uri + " - " + event.eventType)
                                }

                                Logging.log(event.data, LogType.VERBOSE, "ClientAPI WebSocket: " + event.uri + " - " + event.eventType)
                            }
                        }
                    }

                    override fun onClose(code: Int, reason: String?) {
                        println("ClientAPI WebSocket: Closed - $code")

                        summonerInfo = SummonerInfo(SummonerStatus.NOT_LOGGED_IN)
                    }
                })
            }

            override fun onClientDisconnected() {
                summonerInfo = SummonerInfo(SummonerStatus.NOT_LOGGED_IN)

                socket?.close()
            }
        }

        clientAPI?.addClientConnectionListener(clientApiListener)
    }

    private fun handleMasteryChestChange(chestEligibility: LolCollectionsCollectionsChestEligibility) {
        Logging.log(chestEligibility, LogType.DEBUG)

        val nextChestDate = Date(chestEligibility.nextChestRechargeTime)
        val chestCount = chestEligibility.earnableChests

        masteryChestInfo = MasteryChestInfo(nextChestDate, chestCount)
        masteryChestChanged()
    }

    private fun handleSignOnStateChange(loginSession: LolLoginLoginSession) {
        Logging.log(loginSession, LogType.DEBUG)

        when (loginSession.state) {
            LolLoginLoginSessionStates.LOGGING_OUT -> {
                summonerInfo = SummonerInfo(SummonerStatus.NOT_LOGGED_IN)
                summonerChanged()
            }
            LolLoginLoginSessionStates.SUCCEEDED -> {
                handleClientConnection()
            }
            else -> return
        }
    }

    private fun handleClientStateChange(gameFlowPhase: LolGameflowGameflowPhase) {
        Logging.log(gameFlowPhase, LogType.DEBUG)

        when (gameFlowPhase) {
            LolGameflowGameflowPhase.CHAMPSELECT -> {
                val gameFlow = clientAPI!!.executeGet("/lol-gameflow/v1/session", LolGameflowGameflowSession::class.java).responseObject ?: return
                Logging.log(gameFlow, LogType.DEBUG)

                gameMode = when (gameFlow.gameData.queue.gameMode) {
                    "CLASSIC" -> GameMode.BLIND_PICK
                    "RANKED_SOLO_5x5" -> GameMode.RANKED_SOLO
                    "RANKED_FLEX_SR" -> GameMode.RANKED_FLEX
                    "CLASH" -> GameMode.CLASH
                    "ARAM" -> GameMode.ARAM
                    "HEXAKILL" -> GameMode.HEXAKILL
                    "ONEFORALL" -> GameMode.ONE_FOR_ALL
                    "URF" -> GameMode.URF
                    "TUTORIAL" -> GameMode.TUTORIAL
                    "BOT" -> GameMode.BOT
                    "PRACTICETOOL" -> GameMode.PRACTICE_TOOL
                    else -> GameMode.UNKNOWN
                }

                if (gameMode == GameMode.BLIND_PICK) {
                    val gameType = LeagueCommunityDragonAPI.getQueueMapping(gameFlow.gameData.queue.id)

                    gameMode = when (gameType.description) {
                        "Blind Pick" -> GameMode.BLIND_PICK
                        "Draft Pick" -> GameMode.DRAFT_PICK
                        "Ranked Solo/Duo" -> GameMode.RANKED_SOLO
                        "Ranked Flex" -> GameMode.RANKED_FLEX
                        "Clash" -> GameMode.CLASH
                        "Beginner" -> GameMode.BOT
                        "Intermediate" -> GameMode.BOT
                        "Co-op vs. AI" -> GameMode.BOT
                        else -> GameMode.UNKNOWN
                    }
                }
            }
            else -> {
                gameMode = GameMode.NONE
            }
        }

        clientState = gameFlowPhase
        clientStateChanged()
    }

    private fun handleChampionSelectChange(champSelectSession: LolChampSelectChampSelectSession) {
        Logging.log(champSelectSession, LogType.DEBUG)

        if (gameMode == GameMode.NONE) return
        if (champSelectSession.myTeam.isEmpty()) return

        if (championInfo.isEmpty()) {
            updateChampionMasteryInfo()
        }

        val selectedChamp = champSelectSession.myTeam.find { it.summonerId == summonerInfo.summonerID }!!

        val benchedChampions = champSelectSession.benchChampionIds.map { championInfo[it]!! }
        val teamChampions = champSelectSession.myTeam.sortedBy { it.cellId }
            .map {
                if (championInfo.contains(it.championId)) {
                    championInfo[it.championId]
                } else {
                    null
                }
            }

        teamChampions.filterNotNull().forEach {
            it.isSummonerSelectedChamp = it.id == selectedChamp.championId
        }

        val assignedRole = when (selectedChamp.assignedPosition.uppercase()) {
            "TOP" -> Role.TOP
            "JUNGLE" -> Role.JUNGLE
            "MIDDLE" -> Role.MIDDLE
            "BOTTOM" -> Role.BOTTOM
            "UTILITY" -> Role.SUPPORT
            else -> Role.ANY
        }

        championSelectInfo = ChampionSelectInfo(teamChampions, benchedChampions, assignedRole)
        championSelectChanged()
    }

    private fun handleClientConnection(): Boolean {
        var str = "ClientConnection: isConnected=${clientAPI?.isConnected}"

        if (!clientAPI!!.isConnected) {
            Logging.log("Login - " + SummonerStatus.NOT_LOGGED_IN, LogType.INFO, ignorableDuplicate = true)
            return false
        }

        try {
            str += " - isAuthorized=${clientAPI?.isAuthorized}"

            if (!clientAPI!!.isAuthorized) {
                Logging.log("Login - " + SummonerStatus.LOGGED_IN_UNAUTHORIZED, LogType.INFO, ignorableDuplicate = true)

                summonerInfo = SummonerInfo(SummonerStatus.LOGGED_IN_UNAUTHORIZED)
                summonerChanged()

                return false
            }
        } catch (e: HttpHostConnectException) {
            if (e.message?.contains("Connection refused: connect") == true) {
                return false
            }

            e.printStackTrace()
            return false
        }

        Logging.log("Login - " + SummonerStatus.LOGGED_IN_AUTHORIZED, LogType.INFO, ignorableDuplicate = true)

        val summoner = clientAPI!!.executeGet("/lol-summoner/v1/current-summoner", LolSummonerSummoner::class.java).responseObject
        Logging.log(summoner, LogType.DEBUG)

        summonerInfo = SummonerInfo(SummonerStatus.LOGGED_IN_AUTHORIZED, summoner.accountId, summoner.summonerId, summoner.displayName, summoner.internalName,
            summoner.percentCompleteForNextLevel, summoner.summonerLevel, summoner.xpUntilNextLevel)
        summonerChanged()

        return true
    }

    private fun summonerChanged() {
        onSummonerChangeList.forEach { it(summonerInfo) }
    }

    private fun masteryChestChanged() {
        onMasteryChestChangeList.forEach { it(masteryChestInfo) }
    }

    private fun championSelectChanged() {
        onChampionSelectChangeList.forEach { it(championSelectInfo) }
    }

    private fun clientStateChanged() {
        onClientStateChangeList.forEach { it(clientState) }
    }
}
