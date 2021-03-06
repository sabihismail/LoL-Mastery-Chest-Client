package ui

import javafx.stage.Stage
import tornadofx.App
import ui.views.MainView
import util.ViewUtil
import kotlin.system.exitProcess

class MainApp: App(MainView::class) {
    override fun start(stage: Stage) {
        super.start(stage)

        ViewUtil.moveToScreen(stage)
    }

    override fun stop() {
        super.stop()

        exitProcess(0)
    }
}
