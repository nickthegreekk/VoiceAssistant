package com.nikosm.voiceassistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The per-server models screen (step 3).
 *
 * ## Reading discipline
 *
 * Everything shown here is INFORMATIONAL and is worded "reported by this server". No
 * capability value hides, blocks, disables or greys anything on this screen, and none of it
 * feeds a gate anywhere else: the persona editor, image attach and tool use are untouched
 * until step 4, and even then only for the model CHOICE, never for capability gating. The
 * reason is measured, not theoretical — `/api/tags` and `/api/show` disagree with each
 * other in both directions (see ModelCatalog.kt), so a value read here cannot be trusted as
 * a capability claim.
 *
 * ## Layout
 *
 * A plain [Column] with no scroller of its own, exactly like `RoomEditor`/`SkillEditor`: the
 * category page's LazyColumn is the single scroller. That is why the filter chips sit in a
 * horizontally-scrolling Row — a nested vertical scroller here would fight the page.
 */
@Composable
internal fun ModelsScreen(
    server: ServerConfig,
    state: ModelCatalogState,
    healthStatus: String?,
    favoriteModels: List<String>,
    onRefresh: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    accentColor: Color,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // System back returns to the server list, like the header arrow.
    BackHandler(onBack = onBack)

    var filters by rememberSaveable { mutableStateOf(setOf<ModelCapability>()) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(ModelSort.NAME) }

    // Fetch on FIRST open only. Never polled: the refresh control is the only other trigger.
    LaunchedEffect(server.url) {
        if (state is ModelCatalogState.NotLoaded) onRefresh()
    }

    val favorites = remember(favoriteModels) { favoriteModels.toSet() }
    // No favorites for THIS server means the toggle starts on "Show all"; otherwise the
    // list the user curated is what they want to see first.
    val hasFavorites = remember(favorites, server.name) { serverHasFavorites(server.name, favorites) }
    LaunchedEffect(hasFavorites) { if (!hasFavorites) favoritesOnly = false }

    val loaded = state as? ModelCatalogState.Loaded
    val unreachable = isServerUnreachable(healthStatus)
    val selected = loaded?.let {
        selectModels(it.entries, server.name, favorites, filters, favoritesOnly, sort)
    } ?: emptyList()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ModelsHeader(
            server = server,
            state = state,
            unreachable = unreachable,
            onRefresh = onRefresh,
            onBack = onBack
        )

        // Controls: filters, sort, favorites toggle.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Deliberately NOT disabled when there are no favorites: switching to it is how
            // the user reaches the empty state, which explains how to add a favorite.
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly },
                label = { Text(if (favoritesOnly) "Favorites" else "Show all") }
            )
            FilterChip(
                selected = sort == ModelSort.NAME,
                onClick = { sort = if (sort == ModelSort.NAME) ModelSort.SIZE else ModelSort.NAME },
                label = { Text(if (sort == ModelSort.NAME) "Name" else "Size") }
            )
            FILTERABLE_CAPABILITIES.forEach { capability ->
                FilterChip(
                    selected = capability in filters,
                    onClick = {
                        filters = if (capability in filters) filters - capability else filters + capability
                    },
                    label = { Text(capabilityLabel(capability)) }
                )
            }
        }

        when {
            state is ModelCatalogState.Loading -> LoadingRow()
            state is ModelCatalogState.Failed -> EmptyNote(
                "Could not load models from this server. ${(state as ModelCatalogState.Failed).reason}"
            )
            loaded != null && selected.isEmpty() && !loaded.entries.isEmpty() ->
                EmptyNote(filterEmptyMessage(filters, favoritesOnly))
            loaded != null && selected.isEmpty() -> EmptyNote("No models on this server")
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A refresh keeps the stale list on screen rather than blanking it.
                if (loaded?.stale == true) {
                    StaleNote(loaded.degradedReason, unreachable)
                }
                selected.forEach { entry ->
                    val favoriteKey = "[${server.name}] ${entry.name}"
                    ModelRow(
                        entry = entry,
                        serverName = server.name,
                        showState = loaded?.showStates?.get(entry.name) ?: ModelShowState.PENDING,
                        isFavorite = favoriteKey in favorites,
                        accentColor = accentColor,
                        onToggleFavorite = { onToggleFavorite(favoriteKey) }
                    )
                }
            }
        }
    }
}

private fun filterEmptyMessage(filters: Set<ModelCapability>, favoritesOnly: Boolean): String {
    if (favoritesOnly) return "No favorites yet. Tap a star to add one."
    if (filters.isEmpty()) return "No models on this server"
    return "No model on this server reports " +
        filters.joinToString(", ") { capabilityLabel(it).lowercase() } +
        " — reported by this server"
}

@Composable
private fun ModelsHeader(
    server: ServerConfig,
    state: ModelCatalogState,
    unreachable: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back to servers")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // Informational only: the health dot never hides or disables anything here.
                Text(
                    text = if (unreachable) "Unreachable — reported by this server" else "Online",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (unreachable) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh models for ${server.name}")
            }
        }
        val fetchedAt = (state as? ModelCatalogState.Loaded)?.fetchedAtMillis
        Text(
            text = modelFreshnessLabel(fetchedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Loading models…", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StaleNote(reason: String?, unreachable: Boolean) {
    val text = when {
        unreachable -> "This server looks unreachable. Showing the last models loaded."
        reason != null -> "Showing the last models loaded ($reason)."
        else -> "Showing the last models loaded."
    }
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/**
 * One model row.
 *
 * The short name is prominent and the tag quieter, with the full name available underneath:
 * model names like `hf.co/DavidAU/L3.1-Dark-Planet-SpinFire-Uncensored-8B-GGUF:q4_k_m` are
 * far too long for one line, and the full name is never clipped — it wraps to two lines
 * with an ellipsis.
 */
@Composable
private fun ModelRow(
    entry: ModelEntry,
    serverName: String,
    showState: ModelShowState,
    isFavorite: Boolean,
    accentColor: Color,
    onToggleFavorite: () -> Unit
) {
    val description = modelContentDescription(entry, serverName, isFavorite)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.2f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayModelName(entry.name),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val tag = modelTag(entry.name)
                    if (tag.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1
                        )
                    }
                }
                // The full name is always available, never clipped.
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // While /api/show is in flight, a placeholder holds the chips' place so they
                // do not flip from tags values to show values under the reader.
                if (showState == ModelShowState.PENDING) {
                    PendingBadge()
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (entry.isEmbeddingOnly) {
                            // "Embedding only" IS the embedding statement; repeating it as a
                            // plain "Embedding" chip beside it says the same thing twice.
                            CapabilityChip("Embedding only", accentColor)
                        }
                        deriveCapabilityBadges(entry)
                            .filterNot { it == ModelCapability.EMBEDDING && entry.isEmbeddingOnly }
                            .forEach { capability ->
                                CapabilityChip(capabilityLabel(capability), accentColor)
                            }
                    }
                }

                val spec = modelSpecLine(entry)
                if (spec.isNotEmpty()) {
                    Text(
                        text = spec,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    // The row itself carries the full spoken label; the star adds only what
                    // its own action is, so TalkBack never reads the same sentence twice.
                    contentDescription = "Toggle favorite ${entry.name} on $serverName",
                    tint = if (isFavorite) Color.Yellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun CapabilityChip(label: String, accentColor: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accentColor.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun PendingBadge() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)) {
        Text(
            text = "checking capabilities…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            // Deliberately NOT cleared: "checking capabilities…" is a real state, and a
            // screen-reader user is entitled to hear that the chips are still on their way.
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * The per-server "Models" row inside the Ollama servers list.
 *
 * A full-width tappable row rather than another icon button: the server row already carries
 * four icon buttons, and this one opens a screen.
 */
@Composable
internal fun ModelsEntryRow(
    server: ServerConfig,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.2f))
    ) {
        Text(
            text = "Models",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        )
    }
}
