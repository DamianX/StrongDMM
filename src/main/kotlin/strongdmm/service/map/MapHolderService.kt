package strongdmm.service.map

import gnu.trove.map.hash.TIntObjectHashMap
import gnu.trove.map.hash.TObjectIntHashMap
import strongdmm.PostInitialize
import strongdmm.Service
import strongdmm.StrongDMM
import strongdmm.byond.dme.Dme
import strongdmm.byond.dmm.Dmm
import strongdmm.byond.dmm.MapPath
import strongdmm.byond.dmm.parser.DmmParser
import strongdmm.byond.dmm.save.SaveMap
import strongdmm.event.Event
import strongdmm.event.EventHandler
import strongdmm.event.type.Provider
import strongdmm.event.type.Reaction
import strongdmm.event.type.service.TriggerActionService
import strongdmm.event.type.service.TriggerEnvironmentService
import strongdmm.event.type.service.TriggerMapHolderService
import strongdmm.event.type.ui.TriggerCloseMapDialogUi
import strongdmm.event.type.ui.TriggerSetMapSizeDialogUi
import strongdmm.event.type.ui.TriggerUnknownTypesPanelUi
import strongdmm.service.preferences.Preferences
import strongdmm.ui.dialog.close_map.model.CloseMapDialogStatus
import java.io.File
import java.nio.file.Path

class MapHolderService : Service, EventHandler, PostInitialize {
    companion object {
        private val backupsDir: Path = StrongDMM.homeDir.resolve("backups")
    }

    private lateinit var providedPreferences: Preferences
    private lateinit var providedActionBalanceStorage: TObjectIntHashMap<Dmm>

    private val mapsBackupPathsById: TIntObjectHashMap<String> = TIntObjectHashMap()
    private val openedMaps: MutableSet<Dmm> = mutableSetOf()
    private val availableMapsPaths: MutableSet<MapPath> = mutableSetOf()

    private var selectedMap: Dmm? = null

    init {
        consumeEvent(TriggerMapHolderService.CreateNewMap::class.java, ::handleCreateNewMap)
        consumeEvent(TriggerMapHolderService.OpenMap::class.java, ::handleOpenMap)
        consumeEvent(TriggerMapHolderService.CloseMap::class.java, ::handleCloseMap)
        consumeEvent(TriggerMapHolderService.CloseSelectedMap::class.java, ::handleCloseSelectedMap)
        consumeEvent(TriggerMapHolderService.CloseAllMaps::class.java, ::handleCloseAllMaps)
        consumeEvent(TriggerMapHolderService.FetchSelectedMap::class.java, ::handleFetchSelectedMap)
        consumeEvent(TriggerMapHolderService.ChangeSelectedMap::class.java, ::handleChangeSelectedMap)
        consumeEvent(TriggerMapHolderService.SaveSelectedMap::class.java, ::handleSaveSelectedMap)
        consumeEvent(TriggerMapHolderService.SaveSelectedMapToFile::class.java, ::handleSaveSelectedMapToFile)
        consumeEvent(TriggerMapHolderService.SaveAllMaps::class.java, ::handleSaveAllMaps)
        consumeEvent(TriggerMapHolderService.ChangeSelectedZ::class.java, ::handleChangeSelectedZ)
        consumeEvent(Reaction.EnvironmentReset::class.java, ::handleEnvironmentReset)
        consumeEvent(Reaction.EnvironmentChanged::class.java, ::handleEnvironmentChanged)
        consumeEvent(Provider.PreferencesControllerPreferences::class.java, ::handleProviderPreferencesControllerPreferences)
        consumeEvent(Provider.ActionControllerActionBalanceStorage::class.java, ::handleProviderActionControllerActionBalanceStorage)
    }

    override fun postInit() {
        ensureBackupsDirExists()

        sendEvent(Provider.MapHolderControllerOpenedMaps(openedMaps))
        sendEvent(Provider.MapHolderControllerAvailableMaps(availableMapsPaths))
    }

    private fun ensureBackupsDirExists() {
        backupsDir.toFile().mkdirs()
    }

    private fun createBackupFile(environment: Dme, mapFile: File, id: Int) {
        val tmpFileName = "${environment.name}-${mapFile.nameWithoutExtension}-${System.currentTimeMillis()}.backup"
        val tmpDmmDataFile = File(backupsDir.toFile(), tmpFileName)

        tmpDmmDataFile.createNewFile()
        tmpDmmDataFile.writeBytes(mapFile.readBytes())
        tmpDmmDataFile.deleteOnExit()

        mapsBackupPathsById.put(id, tmpDmmDataFile.absolutePath)
    }

    private fun isMapHasChanges(map: Dmm): Boolean {
        return providedActionBalanceStorage.containsKey(map) && providedActionBalanceStorage[map] != 0
    }

    private fun saveMap(map: Dmm, fileToSave: File? = null) {
        val initialDmmData = DmmParser.parse(File(mapsBackupPathsById.get(map.id)))
        SaveMap(map, initialDmmData, fileToSave, providedPreferences)
        sendEvent(TriggerActionService.ResetActionBalance(map))
    }

    private fun closeMap(map: Dmm) {
        val mapIndex = openedMaps.indexOf(map)

        mapsBackupPathsById.remove(map.id)
        openedMaps.remove(map)
        sendEvent(Reaction.OpenedMapClosed(map))

        if (selectedMap === map) {
            sendEvent(Reaction.SelectedMapClosed())

            if (openedMaps.isEmpty()) {
                selectedMap = null
            } else {
                val index = if (mapIndex == openedMaps.size) mapIndex - 1 else mapIndex
                val nextMap = openedMaps.toList()[index]
                selectedMap = nextMap
                sendEvent(Reaction.SelectedMapChanged(nextMap))
            }
        }
    }

    private fun tryCloseMap(map: Dmm, callback: ((Boolean) -> Unit)? = null) {
        if (isMapHasChanges(map)) {
            sendEvent(TriggerCloseMapDialogUi.Open(map) { closeMapStatus ->
                when (closeMapStatus) {
                    CloseMapDialogStatus.CLOSE_WITH_SAVE -> {
                        saveMap(map)
                        closeMap(map)
                    }
                    CloseMapDialogStatus.CLOSE -> closeMap(map)
                    CloseMapDialogStatus.CANCEL -> {
                    }
                }

                callback?.invoke(closeMapStatus != CloseMapDialogStatus.CANCEL)
            })
        } else {
            closeMap(map)
            callback?.invoke(true)
        }
    }

    private fun handleCreateNewMap(event: Event<File, Unit>) {
        var newMapFile: File = event.body

        if (!newMapFile.exists() && event.body.extension != "dmm") {
            newMapFile = File(event.body.parent, "${event.body.name}.dmm")
        }

        this::class.java.classLoader.getResourceAsStream("new_map_data.txt").use {
            newMapFile.writeBytes(it!!.readAllBytes())
        }

        sendEvent(TriggerMapHolderService.OpenMap(newMapFile))
        selectedMap?.setMapSize(0, 0, 0) // -_-
        sendEvent(TriggerSetMapSizeDialogUi.Open())
    }

    private fun handleOpenMap(event: Event<File, Unit>) {
        val id = event.body.absolutePath.hashCode()

        if (selectedMap?.id == id) {
            return
        }

        val dmm = openedMaps.find { it.id == id }

        if (dmm != null) {
            selectedMap = dmm
            sendEvent(Reaction.SelectedMapChanged(dmm))
        } else {
            val mapFile = event.body

            if (!mapFile.isFile) {
                return
            }

            sendEvent(TriggerEnvironmentService.FetchOpenedEnvironment { environment ->
                val dmmData = DmmParser.parse(mapFile)
                val map = Dmm(mapFile, dmmData, environment)

                createBackupFile(environment, mapFile, id)

                openedMaps.add(map)
                selectedMap = map

                sendEvent(Reaction.SelectedMapChanged(map))

                if (map.unknownTypes.isNotEmpty()) {
                    sendEvent(TriggerUnknownTypesPanelUi.Open(map.unknownTypes))
                }
            })
        }
    }

    private fun handleCloseMap(event: Event<Int, Unit>) {
        openedMaps.find { it.id == event.body }?.let { tryCloseMap(it) }
    }

    private fun handleCloseSelectedMap() {
        selectedMap?.let { map -> tryCloseMap(map) }
    }

    private fun handleCloseAllMaps(event: Event<Unit, Boolean>) {
        if (openedMaps.isEmpty()) {
            event.reply(true)
            return
        }

        openedMaps.firstOrNull()?.let { map ->
            tryCloseMap(map) {
                if (it) {
                    handleCloseAllMaps(event)
                } else {
                    event.reply(false)
                }
            }
        }
    }

    private fun handleFetchSelectedMap(event: Event<Unit, Dmm>) {
        selectedMap?.let { event.reply(it) }
    }

    private fun handleChangeSelectedMap(event: Event<Int, Unit>) {
        openedMaps.find { it.id == event.body }?.let {
            if (selectedMap !== it) {
                selectedMap = it
                sendEvent(Reaction.SelectedMapChanged(it))
            }
        }
    }

    private fun handleSaveSelectedMap() {
        selectedMap?.let { saveMap(it) }
    }

    private fun handleSaveSelectedMapToFile(event: Event<File, Unit>) {
        selectedMap?.let { saveMap(it, event.body) }
    }

    private fun handleSaveAllMaps() {
        openedMaps.forEach { saveMap(it) }
    }

    private fun handleChangeSelectedZ(event: Event<Int, Unit>) {
        selectedMap?.let { map ->
            if (event.body == map.zSelected || event.body < 1 || event.body > map.maxZ) {
                return
            }

            map.zSelected = event.body
            sendEvent(Reaction.SelectedMapZSelectedChanged(map.zSelected))
        }
    }

    private fun handleEnvironmentReset() {
        selectedMap = null
        openedMaps.clear()
        availableMapsPaths.clear()
    }

    private fun handleEnvironmentChanged(event: Event<Dme, Unit>) {
        File(event.body.absRootDirPath).walkTopDown().forEach {
            if (it.extension == "dmm") {
                val absoluteFilePath = it.absolutePath
                val readableName = File(event.body.absRootDirPath).toPath().relativize(it.toPath()).toString()
                availableMapsPaths.add(MapPath(readableName, absoluteFilePath))
            }
        }
    }

    private fun handleProviderPreferencesControllerPreferences(event: Event<Preferences, Unit>) {
        providedPreferences = event.body
    }

    private fun handleProviderActionControllerActionBalanceStorage(event: Event<TObjectIntHashMap<Dmm>, Unit>) {
        providedActionBalanceStorage = event.body
    }
}