package app.marlboroadvance.mpvex.ui.browser.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun SortDialog(
  isOpen: Boolean,
  onDismiss: () -> Unit,
  title: String,
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  types: List<String>,
  icons: List<ImageVector>,
  getLabelForType: (String, Boolean) -> Pair<String, String>,
  modifier: Modifier = Modifier,
  visibilityToggles: List<VisibilityToggle> = emptyList(),
  viewModeSelector: ViewModeSelector? = null,
  layoutModeSelector: ViewModeSelector? = null,
  folderGridColumnSelector: GridColumnSelector? = null,
  videoGridColumnSelector: GridColumnSelector? = null,
  showSortOptions: Boolean = true,
  enableViewModeOptions: Boolean = true,
  enableLayoutModeOptions: Boolean = true,
) {
  if (!isOpen) return

  val (ascLabel, descLabel) = getLabelForType(sortType, sortOrderAsc)

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
  ) {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      tonalElevation = 6.dp,
      modifier = modifier
        .fillMaxWidth(0.80f)
        .padding(vertical = 24.dp)
    ) {
      Column(
        modifier = Modifier.padding(24.dp)
      ) {
        // Title
        Text(
          text = title,
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.padding(bottom = 16.dp)
        )

        // Content
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        ) {
          HorizontalDivider()

          if (showSortOptions) {
            DialogSectionTitle(text = "Sort")

            SortTypeSelector(
              sortType = sortType,
              onSortTypeChange = onSortTypeChange,
              types = types,
              icons = icons,
              modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            SortOrderSelector(
              sortOrderAsc = sortOrderAsc,
              onSortOrderChange = onSortOrderChange,
              ascLabel = ascLabel,
              descLabel = descLabel,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          if (viewModeSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            DialogSectionTitle(text = viewModeSelector.label)
            ViewModeSelectorComponent(
              viewModeSelector = viewModeSelector,
              enabled = enableViewModeOptions,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          if (layoutModeSelector != null) {
            DialogSectionTitle(text = layoutModeSelector.label)
            ViewModeSelectorComponent(
              viewModeSelector = layoutModeSelector,
              enabled = enableLayoutModeOptions,
              modifier = Modifier.fillMaxWidth(),
            )
          }

          if (folderGridColumnSelector != null || videoGridColumnSelector != null) {
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            DialogSectionTitle(text = "Grid Columns")
            GridColumnsSection(
              folderGridColumnSelector = folderGridColumnSelector,
              videoGridColumnSelector = videoGridColumnSelector,
            )
          }

          if (visibilityToggles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            VisibilityTogglesSection(toggles = visibilityToggles)
          }
        }
      }
    }
  }
}

data class VisibilityToggle(
  val label: String,
  val checked: Boolean,
  val onCheckedChange: (Boolean) -> Unit,
)

data class ViewModeSelector(
  val label: String,
  val options: List<String>,
  val icons: List<ImageVector>,
  val selectedIndex: Int,
  val onViewModeChange: (Int) -> Unit,
)

data class GridColumnSelector(
  val label: String,
  val currentValue: Int,
  val onValueChange: (Int) -> Unit,
  val valueRange: ClosedFloatingPointRange<Float> = 1f..4f,
  val steps: Int = 2,
)

// -----------------------------------------------------------------------------
// UI Components
// -----------------------------------------------------------------------------

@Composable
private fun DialogSectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    color = MaterialTheme.colorScheme.onSurface
  )
}

@Composable
private fun SortTypeSelector(
  sortType: String,
  onSortTypeChange: (String) -> Unit,
  types: List<String>,
  icons: List<ImageVector>,
  modifier: Modifier = Modifier,
) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    types.forEachIndexed { index, type ->
      val isSelected = sortType == type

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .weight(1f)
          .border(
            width = 1.dp,
            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            shape = RoundedCornerShape(12.dp)
          )
          .clip(RoundedCornerShape(12.dp))
          .background(
            if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
          )
          .clickable { onSortTypeChange(type) }
          .padding(vertical = 10.dp, horizontal = 4.dp)
      ) {
        Icon(
          imageVector = icons[index],
          contentDescription = null,
          modifier = Modifier.size(24.dp),
          tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                 else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = type,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
          color = if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun SortOrderSelector(
  sortOrderAsc: Boolean,
  onSortOrderChange: (Boolean) -> Unit,
  ascLabel: String,
  descLabel: String,
  modifier: Modifier = Modifier,
) {
  val options = listOf(ascLabel, descLabel)
  val selectedIndex = if (sortOrderAsc) 0 else 1

  SingleChoiceSegmentedButtonRow(
    modifier = modifier.fillMaxWidth(),
  ) {
    options.forEachIndexed { index, label ->
      SegmentedButton(
        shape = SegmentedButtonDefaults.itemShape(
          index = index,
          count = options.size,
        ),
        onClick = { onSortOrderChange(index == 0) },
        selected = index == selectedIndex,
        colors = SegmentedButtonDefaults.colors(
          activeContentColor = MaterialTheme.colorScheme.primary,
          activeBorderColor = MaterialTheme.colorScheme.primary,
        ),
        icon = {
          Icon(
            if (index == 0) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
        },
      ) {
        Text(label)
      }
    }
  }
}

@Composable
fun FieldChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  selectedIcon: ImageVector = Icons.Filled.CheckBox,
  unselectedIcon: ImageVector = Icons.Filled.CheckBoxOutlineBlank,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(text = label) },
    leadingIcon = {
      Icon(
        imageVector = if (selected) selectedIcon else unselectedIcon,
        contentDescription = null,
        modifier = Modifier.size(FilterChipDefaults.IconSize),
        tint = MaterialTheme.colorScheme.secondary,
      )
    },
    border = FilterChipDefaults.filterChipBorder(
      enabled = true,
      selected = selected,
      selectedBorderWidth = 1.dp,
      selectedBorderColor = MaterialTheme.colorScheme.primary,
    ),
    modifier = modifier,
  )
}

@Composable
private fun VisibilityTogglesSection(
  toggles: List<VisibilityToggle>,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  Column(modifier = modifier) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { expanded = !expanded }
        .padding(vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Fields",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
      Icon(
        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
        contentDescription = if (expanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }

    if (expanded) {
      androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
          .fillMaxWidth()
          .wrapContentHeight(align = Alignment.Top),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        toggles.forEach { toggle ->
          FieldChip(
            label = toggle.label,
            selected = toggle.checked,
            onClick = { toggle.onCheckedChange(!toggle.checked) },
          )
        }
      }
    }
  }
}

@Composable
private fun ViewModeSelectorComponent(
  viewModeSelector: ViewModeSelector,
  enabled: Boolean = true,
  modifier: Modifier = Modifier,
) {
  val options = viewModeSelector.options
  val icons = viewModeSelector.icons
  val selectedIndex = viewModeSelector.selectedIndex

  SingleChoiceSegmentedButtonRow(
    modifier = modifier.fillMaxWidth()
  ) {
    options.forEachIndexed { index, label ->
      SegmentedButton(
        shape = SegmentedButtonDefaults.itemShape(
          index = index,
          count = options.size
        ),
        onClick = {
          if (enabled) {
            viewModeSelector.onViewModeChange(index)
          }
        },
        selected = index == selectedIndex,
        enabled = enabled,
        colors = SegmentedButtonDefaults.colors(
          activeContentColor = MaterialTheme.colorScheme.primary,
          activeBorderColor = MaterialTheme.colorScheme.primary,
        ),
         icon = {
          Icon(
            imageVector = icons[index],
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
      ) {
        Text(text = label)
      }
    }
  }
}

@Composable
private fun GridColumnsSection(
  folderGridColumnSelector: GridColumnSelector?,
  videoGridColumnSelector: GridColumnSelector?,
  modifier: Modifier = Modifier,
) {
  if (folderGridColumnSelector == null && videoGridColumnSelector == null) return

  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.Top,
    ) {
      if (folderGridColumnSelector != null) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Folder Grid",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Slider(
            value = folderGridColumnSelector.currentValue.toFloat(),
            onValueChange = { folderGridColumnSelector.onValueChange(it.toInt()) },
            valueRange = folderGridColumnSelector.valueRange,
            steps = folderGridColumnSelector.steps,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            text = "${folderGridColumnSelector.currentValue} columns",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
          )
        }
      }

      if (videoGridColumnSelector != null) {
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "Video Grid",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Slider(
            value = videoGridColumnSelector.currentValue.toFloat(),
            onValueChange = { videoGridColumnSelector.onValueChange(it.toInt()) },
            valueRange = videoGridColumnSelector.valueRange,
            steps = videoGridColumnSelector.steps,
            modifier = Modifier.fillMaxWidth(),
          )
          Text(
            text = "${videoGridColumnSelector.currentValue} columns",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
          )
        }
      }
    }
  }
}
