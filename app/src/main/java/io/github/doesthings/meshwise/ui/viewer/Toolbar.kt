package io.github.doesthings.meshwise.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded._360
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.doesthings.meshwise.R
import io.github.doesthings.meshwise.data.ToolbarItem
import io.github.doesthings.meshwise.viewer.Tool

val ToolbarItem.icon: ImageVector
    get() = when (this) {
        ToolbarItem.Files -> Icons.Rounded.FolderOpen
        ToolbarItem.Save -> Icons.Rounded.Save
        ToolbarItem.Color -> Icons.Rounded.Palette
        ToolbarItem.Print -> Icons.Rounded.Print
        ToolbarItem.Measure -> Icons.Rounded.Straighten
        ToolbarItem.Analyze -> Icons.Rounded.Insights
        ToolbarItem.View -> Icons.Rounded.WbSunny
        ToolbarItem.Material -> Icons.Rounded.Diamond
        ToolbarItem.Section -> Icons.Rounded.ContentCut
        ToolbarItem.Explode -> Icons.Rounded.Widgets
        ToolbarItem.Parts -> Icons.AutoMirrored.Rounded.FormatListBulleted
        ToolbarItem.Info -> Icons.Rounded.Info
        ToolbarItem.Snapshot -> Icons.Rounded.CameraAlt
        ToolbarItem.Turntable -> Icons.Rounded._360
    }

/** Short label (bottom bar) and the longer name used for accessibility and Settings. */
val ToolbarItem.label: Int
    get() = when (this) {
        ToolbarItem.Files -> R.string.label_files
        ToolbarItem.Save -> R.string.label_save
        ToolbarItem.Color -> R.string.label_color
        ToolbarItem.Print -> R.string.label_print
        ToolbarItem.Measure -> R.string.label_measure
        ToolbarItem.Analyze -> R.string.label_analyze
        ToolbarItem.View -> R.string.label_view
        ToolbarItem.Material -> R.string.label_material
        ToolbarItem.Section -> R.string.label_section
        ToolbarItem.Explode -> R.string.label_explode
        ToolbarItem.Parts -> R.string.label_parts
        ToolbarItem.Info -> R.string.label_info
        ToolbarItem.Snapshot -> R.string.label_snapshot
        ToolbarItem.Turntable -> R.string.label_turntable
    }

val ToolbarItem.longLabel: Int
    get() = when (this) {
        ToolbarItem.Files -> R.string.tool_files
        ToolbarItem.Save -> R.string.tool_save
        ToolbarItem.Color -> R.string.tool_color
        ToolbarItem.Print -> R.string.tool_print
        ToolbarItem.Measure -> R.string.tool_measure
        ToolbarItem.Analyze -> R.string.tool_analyze
        ToolbarItem.View -> R.string.tool_lighting
        ToolbarItem.Material -> R.string.tool_material
        ToolbarItem.Section -> R.string.tool_section
        ToolbarItem.Explode -> R.string.tool_explode
        ToolbarItem.Parts -> R.string.tool_parts
        ToolbarItem.Info -> R.string.model_info
        ToolbarItem.Snapshot -> R.string.snapshot
        ToolbarItem.Turntable -> R.string.turntable
    }

/** The tool panel a button opens, if any. */
val ToolbarItem.tool: Tool?
    get() = when (this) {
        ToolbarItem.Save -> Tool.Save
        ToolbarItem.Color -> Tool.Color
        ToolbarItem.Print -> Tool.Print
        ToolbarItem.Measure -> Tool.Measure
        ToolbarItem.Analyze -> Tool.Analyze
        ToolbarItem.View -> Tool.Lighting
        ToolbarItem.Material -> Tool.Material
        ToolbarItem.Section -> Tool.Section
        ToolbarItem.Explode -> Tool.Explode
        ToolbarItem.Parts -> Tool.Parts
        ToolbarItem.Info -> Tool.Info
        else -> null
    }
