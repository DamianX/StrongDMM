package io.github.spair.strongdmm.logic

import io.github.spair.dmm.io.reader.DmmReader
import io.github.spair.strongdmm.diInstance
import io.github.spair.strongdmm.gui.mapcanvas.MapCanvasController
import io.github.spair.strongdmm.gui.objtree.ObjectTreeController
import io.github.spair.strongdmm.logic.dme.Dme
import io.github.spair.strongdmm.logic.dme.parseDme
import io.github.spair.strongdmm.logic.map.Dmm
import java.io.File

class Environment {

    private lateinit var dme: Dme

    lateinit var absoluteRootPath: String
    val availableMaps = mutableListOf<String>()

    private val objectTreeController by diInstance<ObjectTreeController>()
    private val mapCanvasController by diInstance<MapCanvasController>()

    fun parseAndPrepareEnv(dmeFile: File): Dme {
        val s = System.currentTimeMillis()

        dme = parseDme(dmeFile.absolutePath)

        absoluteRootPath = dmeFile.parentFile.absolutePath
        objectTreeController.populateTree(dme)
        findAvailableMaps(dmeFile.parentFile)
        System.gc()

        println(System.currentTimeMillis() - s)

        return dme
    }

    fun openMap(mapPath: String) {
        val dmmData = DmmReader.readMap(File(mapPath))
        val dmm = Dmm(dmmData, dme)
        mapCanvasController.selectMap(dmm)
    }

    private fun findAvailableMaps(rootFolder: File) {
        rootFolder.walkTopDown().forEach {
            if (it.path.endsWith(".dmm")) {
                availableMaps.add(it.path)
            }
        }
    }
}
