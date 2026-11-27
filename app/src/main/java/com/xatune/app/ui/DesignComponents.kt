package com.xatune.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xatune.app.model.Semester

private val GroupOuterRadius = 20.dp
private val GroupInnerRadius = 4.dp

internal fun groupedItemShape(index: Int, count: Int): Shape = when {
    count <= 1 -> RoundedCornerShape(GroupOuterRadius)
    index == 0 -> RoundedCornerShape(
        topStart = GroupOuterRadius, topEnd = GroupOuterRadius,
        bottomStart = GroupInnerRadius, bottomEnd = GroupInnerRadius
    )
    index == count - 1 -> RoundedCornerShape(
        topStart = GroupInnerRadius, topEnd = GroupInnerRadius,
        bottomStart = GroupOuterRadius, bottomEnd = GroupOuterRadius
    )
    else -> RoundedCornerShape(GroupInnerRadius)
}

internal class GroupedSectionScope {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()
    fun item(content: @Composable (Shape) -> Unit) {
        rows.add(content)
    }
}

@Composable
internal fun GroupedSection(
    title: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: GroupedSectionScope.() -> Unit
) {
    val scope = GroupedSectionScope().apply(content)
    if (scope.rows.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp)
            )
        }
        scope.rows.forEachIndexed { index, row ->
            row(groupedItemShape(index, scope.rows.size))
        }
        footer?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp, end = 6.dp)
            )
        }
    }
}

@Composable
internal fun GroupedNavigationRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconContainerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    modifier: Modifier = Modifier,
    selectableDetail: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(shape).clickable(enabled = enabled, onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            icon?.let {
                Surface(shape = RoundedCornerShape(12.dp), color = iconContainerColor, contentColor = iconTint) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(it, null, Modifier.size(20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    if (selectableDetail) {
                        SelectionContainer {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!value.isNullOrBlank()) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun GroupedValueRow(
    shape: Shape,
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    selectableValue: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val clickMod = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickMod),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            icon?.let {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(it, null, Modifier.size(20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectableValue) {
                SelectionContainer {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun GroupedToggleRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .then(switchModifier)
                .clip(shape)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            icon?.let {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Icon(it, null, Modifier.size(20.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

@Composable
internal fun ResponsiveWidthBox(
    maxWidth: Dp = 840.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(), content = content)
    }
}

@Composable
internal fun SectionHeading(title: String, detail: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun IconTile(
    icon: ImageVector,
    prominent: Boolean = false,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val bg = containerColor ?: if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val fg = contentColor ?: if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = RoundedCornerShape(16.dp), color = bg, contentColor = fg) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

@Composable
internal fun ServiceRow(
    title: String,
    detail: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selectableDetail: Boolean = false,
    onClick: () -> Unit
) {
    GroupedNavigationRow(
        shape = RoundedCornerShape(GroupOuterRadius),
        title = title,
        subtitle = detail,
        icon = icon,
        modifier = modifier,
        selectableDetail = selectableDetail,
        onClick = onClick
    )
}

@Composable
internal fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    switchModifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.then(switchModifier)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked, null, enabled = enabled)
        }
    }
}

@Composable
internal fun InfoPanel(text: String, icon: ImageVector = Icons.Default.Info) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.size(18.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun EmptyState(title: String, detail: String, icon: ImageVector = Icons.Default.Info) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ContentCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    val shape = RoundedCornerShape(GroupOuterRadius)
    if (onClick == null) Card(modifier = modifier, shape = shape, colors = colors, content = content)
    else Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, content = content)
}

// --- School Year / Term hierarchical menu helpers ---

internal data class SemesterParts(val year: String, val term: String)

private val yearPattern = Regex("""(\d{4}\s*[-–]\s*\d{4})""")

internal fun parseSemesterParts(name: String): SemesterParts? {
    val match = yearPattern.find(name) ?: return null
    val year = match.groupValues[1].replace(" ", "").replace("–", "-")
    val remainder = name.removeRange(match.range)
        .replace("学年", "")
        .trim(' ', '-', '–', '·', '(', ')', '（', '）')
    if (remainder.isBlank()) return null
    return SemesterParts(year = year, term = remainder)
}

internal class SemesterHierarchy(val semesters: List<Semester>, val selectedId: String) {
    private val parsed: List<Pair<Semester, SemesterParts?>> = semesters.map { it to parseSemesterParts(it.name) }
    val selected: Semester? = semesters.firstOrNull { it.id == selectedId }
    val selectedParts: SemesterParts? = selected?.let { parseSemesterParts(it.name) }
    val selectedYear: String = selectedParts?.year.orEmpty()
    val selectedTerm: String = selectedParts?.term.orEmpty()
    val years: List<String> = parsed.mapNotNull { it.second?.year }.distinct().sortedDescending()
    val canSplit: Boolean = years.isNotEmpty() && parsed.all { it.second != null }

    fun termsFor(year: String): List<String> {
        val names = parsed.filter { it.second?.year == year }.mapNotNull { it.second?.term }.distinct()
        val preferred = listOf("1学期", "2学期", "第一学期", "第二学期").filter { it in names }
        return preferred + names.filterNot { it in preferred }.sorted()
    }

    fun pickYear(year: String): Semester? {
        val candidates = parsed.filter { it.second?.year == year }.map { it.first }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { parseSemesterParts(it.name)?.term == selectedTerm }
            ?: candidates.minByOrNull { it.id }
    }

    fun pickTerm(term: String): Semester? {
        return parsed.firstOrNull { it.second?.year == selectedYear && it.second?.term == term }?.first
            ?: parsed.firstOrNull { it.second?.term == term }?.first
    }
}

internal enum class SemesterMenuLevel { ROOT, YEAR, TERM, KIND }

@Composable
internal fun SemesterSplitMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    semesters: List<Semester>,
    selectedId: String,
    enabled: Boolean = true,
    onSelect: (Semester) -> Unit,
    selectedKind: com.xatune.app.model.TableKind? = null,
    showReturnCurrent: Boolean = false,
    onReturnCurrent: (() -> Unit)? = null,
    onSelectKind: ((com.xatune.app.model.TableKind) -> Unit)? = null
) {
    val hierarchy = remember(semesters, selectedId) { SemesterHierarchy(semesters, selectedId) }
    var level by remember(expanded) { mutableStateOf(SemesterMenuLevel.ROOT) }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(228.dp)
    ) {
        if (!hierarchy.canSplit && selectedKind == null) {
            semesters.forEach { term ->
                DropdownMenuItem(
                    text = { Text(term.name) },
                    enabled = enabled,
                    trailingIcon = if (term.id == selectedId) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                    onClick = { onDismiss(); onSelect(term) }
                )
            }
        } else {
            Crossfade(
                targetState = level,
                animationSpec = tween(110),
                label = "semesterMenuLevel"
            ) { currentLevel ->
                Column(Modifier.fillMaxWidth()) {
                    when (currentLevel) {
                        SemesterMenuLevel.ROOT -> {
                            if (hierarchy.canSplit) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("学年")
                                            if (hierarchy.selectedYear.isNotBlank()) {
                                                Text("${hierarchy.selectedYear}学年", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    enabled = enabled && hierarchy.years.isNotEmpty(),
                                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp)) },
                                    onClick = { level = SemesterMenuLevel.YEAR }
                                )
                                val terms = hierarchy.termsFor(hierarchy.selectedYear)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("学期")
                                            if (hierarchy.selectedTerm.isNotBlank()) {
                                                Text(hierarchy.selectedTerm, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    enabled = enabled && terms.isNotEmpty(),
                                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp)) },
                                    onClick = { level = SemesterMenuLevel.TERM }
                                )
                            } else {
                                semesters.forEach { term ->
                                    DropdownMenuItem(
                                        text = { Text(term.name) },
                                        enabled = enabled,
                                        trailingIcon = if (term.id == selectedId) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                                        onClick = { onDismiss(); onSelect(term) }
                                    )
                                }
                            }
                            if (selectedKind != null && onSelectKind != null) {
                                if (semesters.isNotEmpty()) HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("课表类型")
                                            Text(selectedKind.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    enabled = enabled,
                                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp)) },
                                    onClick = { level = SemesterMenuLevel.KIND }
                                )
                            }
                            if (showReturnCurrent && onReturnCurrent != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("返回当前学生课表", color = MaterialTheme.colorScheme.primary) },
                                    enabled = enabled,
                                    onClick = { onDismiss(); onReturnCurrent() }
                                )
                            }
                        }
                        SemesterMenuLevel.YEAR -> {
                            DropdownMenuItem(
                                text = { Text("学年", fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(18.dp)) },
                                onClick = { level = SemesterMenuLevel.ROOT }
                            )
                            HorizontalDivider()
                            hierarchy.years.forEach { year ->
                                DropdownMenuItem(
                                    text = { Text("${year}学年") },
                                    enabled = enabled,
                                    trailingIcon = if (year == hierarchy.selectedYear) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                                    onClick = {
                                        onDismiss()
                                        hierarchy.pickYear(year)?.let(onSelect)
                                    }
                                )
                            }
                        }
                        SemesterMenuLevel.TERM -> {
                            DropdownMenuItem(
                                text = { Text("学期", fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(18.dp)) },
                                onClick = { level = SemesterMenuLevel.ROOT }
                            )
                            HorizontalDivider()
                            hierarchy.termsFor(hierarchy.selectedYear).forEach { term ->
                                DropdownMenuItem(
                                    text = { Text(term) },
                                    enabled = enabled,
                                    trailingIcon = if (term == hierarchy.selectedTerm) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                                    onClick = {
                                        onDismiss()
                                        hierarchy.pickTerm(term)?.let(onSelect)
                                    }
                                )
                            }
                        }
                        SemesterMenuLevel.KIND -> {
                            DropdownMenuItem(
                                text = { Text("课表类型", fontWeight = FontWeight.SemiBold) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(18.dp)) },
                                onClick = { level = SemesterMenuLevel.ROOT }
                            )
                            HorizontalDivider()
                            com.xatune.app.model.TableKind.entries.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind.title) },
                                    enabled = enabled,
                                    trailingIcon = if (kind == selectedKind) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                                    onClick = {
                                        onDismiss()
                                        onSelectKind?.invoke(kind)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
