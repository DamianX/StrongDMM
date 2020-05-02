package strongdmm.ui.panel.tool_select

import strongdmm.controller.tool.ToolType

class State {
    val tools: Array<ToolType> = ToolType.values()
    var selectedTool: ToolType = ToolType.TILE
}
