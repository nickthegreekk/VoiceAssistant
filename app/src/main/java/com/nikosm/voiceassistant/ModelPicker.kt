package com.nikosm.voiceassistant

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp

/**
 * The Ollama model picker for the persona editor (step 4).
 *
 * ## Strictly additive
 *
 * The one contract that cannot move: **every row reports the `"[ServerName] model"` string
 * it reports today**. [onSelect] and [onToggleFavorite] are both handed
 * [qualifiedModelName]'s value, which is byte-for-byte what the old inline dropdown passed
 * to `model = m` and to `service.toggleFavoriteModel(m)`. A selection made here therefore
 * writes exactly the string a selection made before this step wrote, and the star mutates
 * exactly the same `favorite_models_v2` entry. No new Persona field exists; this only
 * decides where the already-present list is read from and what each row looks like.
 *
 * ## Where the list comes from
 *
 * Ollama connections read the service's shared catalog for this server's URL, the same cache
 * the per-server models screen uses — so `/api/show` enrichment is finally visible in the
 * picker and the two surfaces cannot disagree about a model. Cloud connections are NOT
 * routed here; PersonaEditor keeps its existing list for them, untouched.
 *
 * Fetch-on-first-open lives here rather than in the editor because this composable only
 * exists while the dropdown is open. Never polled, exactly like the models screen.
 *
 * ## Nothing here gates anything
 *
 * There are deliberately no capability filter chips in a picker: a filter that hid models
 * would put the model a persona is currently using out of reach. Capability data here is
 * display only — nothing is hidden, disabled or greyed because of it, and an unreachable
 * server or a failed fetch both keep the rows on screen.
 *
 * @param currentModel the persona's stored model string, exactly as the field holds it.
 * @param onSelect receives the qualified string to store.
 */
@Composable
internal fun OllamaModelPicker(
    server: ServerConfig,
    state: ModelCatalogState,
    healthStatus: String?,
    favoriteModels: List<String>,
    currentModel: String,
    onRefresh: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelect: (String) -> Unit,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    // Fetch on FIRST open only, and only when this server has never been fetched. Never
    // polled: the refresh control is the only other trigger.
    LaunchedEffect(server.url) {
        if (state is ModelCatalogState.NotLoaded) onRefresh()
    }

    val favorites = remember(favoriteModels) { favoriteModels.toSet() }
    val hasFavorites = remember(favorites, server.name) { serverHasFavorites(server.name, favorites) }
    // The user's own choice, and null while they have not made one. "No choice" has to stay
    // distinct from a choice of false: the editor starts its favourites list empty and fills
    // it from a flow, so a picker opened before the first emission used to bake false into
    // the saved state and then keep it — every model listed on a server the user had curated,
    // with the chip reading "Show all" the whole time.
    //
    // What rememberSaveable buys here is exactly that distinction, and only for as long as THIS
    // menu stays open: a deliberate "Show all" outranks the default and is not undone by a
    // later favourites emission. It does not outlive the menu. DropdownMenu composes its
    // content only while expanded, so closing it disposes this composable, and the saved
    // provider is unregistered rather than written out — the next open starts from "no choice"
    // and lands back on Favorites. Confirmed on the device (tap "Show all", press back,
    // reopen: the chip reads "Favorites") and pinned by theShowAllChoiceLastsForThisOpenOnly.
    var chosenFavoritesOnly by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Nothing to curate means there is nothing to switch to, so the list is everything.
    val favoritesOnly = hasFavorites && (chosenFavoritesOnly ?: true)

    // Only a model carrying THIS server's prefix belongs to this picker; one belonging to a
    // different connection must never be injected as if this server offered it.
    val prefix = "[${server.name}] "
    val currentBare = currentModel.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)

    val loaded = state as? ModelCatalogState.Loaded
    val unreachable = isServerUnreachable(healthStatus)
    val rows = selectPickerModels(
        entries = loaded?.entries ?: emptyList(),
        serverName = server.name,
        favoriteNames = favorites,
        favoritesOnly = favoritesOnly,
        currentModelName = currentBare
    )
    // An injected stand-in is not pending: nothing is on its way for it, the server simply did
    // not list it. Marking it PENDING would show "checking capabilities…" forever.
    val catalogNames = remember(loaded) {
        loaded?.entries?.mapTo(mutableSetOf<String>()) { it.name } ?: emptySet<String>()
    }

    // No scroll of its own: the host DropdownMenu already scrolls its content, and a
    // verticalScroll nested inside another one measures against infinite height and throws.
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Hidden when this server has no favorites: with none, everything is listed and
            // the control could only flip a label. (The models screen keeps it visible, but
            // there the empty favorites view is itself worth reaching.)
            if (hasFavorites) {
                FilterChip(
                    selected = favoritesOnly,
                    onClick = { chosenFavoritesOnly = !favoritesOnly },
                    label = { Text(if (favoritesOnly) "Favorites" else "Show all") }
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = modelFreshnessLabel(loaded?.fetchedAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh models for ${server.name}")
            }
        }

        if (unreachable) {
            Text(
                text = UNREACHABLE_LABEL,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // The load states, worded exactly as the models screen words them.
        when (state) {
            is ModelCatalogState.Loading -> LoadingRow()
            is ModelCatalogState.Failed -> EmptyNote(
                "Could not load models from this server. ${(state as ModelCatalogState.Failed).reason}"
            )
            else -> Unit
        }

        if (rows.isEmpty()) {
            EmptyNote("No models on this server")
        } else {
            // Kept on screen above the rows rather than replacing them: a refresh that failed
            // must not empty the list the user was choosing from.
            if (loaded?.stale == true) StaleNote(loaded.degradedReason, unreachable)
            rows.forEach { entry ->
                PickerModelRow(
                    entry = entry,
                    server = server,
                    isCurrent = entry.name == currentBare,
                    isFavorite = qualifiedModelName(server.name, entry.name) in favorites,
                    showState = if (entry.name in catalogNames) {
                        loaded?.showStates?.get(entry.name) ?: ModelShowState.PENDING
                    } else {
                        ModelShowState.RESOLVED
                    },
                    accentColor = accentColor,
                    onToggleFavorite = {
                        onToggleFavorite(qualifiedModelName(server.name, entry.name))
                    },
                    onSelect = { onSelect(qualifiedModelName(server.name, entry.name)) }
                )
            }
        }
    }
}

/**
 * One selectable row.
 *
 * A [Surface] rather than a `DropdownMenuItem`, because a row now carries chips and a spec
 * line and has to stay a sane tap target. The star sits inside the row's clickable area and
 * consumes its own click before the row sees it; because it declares its own
 * contentDescription it is its own semantics node rather than being merged away.
 */
@Composable
private fun PickerModelRow(
    entry: ModelEntry,
    server: ServerConfig,
    isCurrent: Boolean,
    isFavorite: Boolean,
    showState: ModelShowState,
    accentColor: Color,
    onToggleFavorite: () -> Unit,
    onSelect: () -> Unit
) {
    val description = buildString {
        append(modelContentDescription(entry, server.name, isFavorite))
        if (isCurrent) append(", current model")
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description }
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(12.dp),
        color = if (isCurrent) accentColor.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.2f),
        // The selected model is outlined in the accent at full strength, so the selection is
        // visible without relying on colour alone — it is also spelled out in the row.
        border = BorderStroke(1.dp, if (isCurrent) accentColor else accentColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ModelRowBody(
                entry = entry,
                showState = showState,
                accentColor = accentColor,
                modifier = Modifier.weight(1f)
            ) {
                if (isCurrent) CurrentBadge(accentColor)
            }
            FavoriteStar(
                isFavorite = isFavorite,
                entryName = entry.name,
                serverName = server.name,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

/** "✓ Current" — the one row that is what this persona is using right now. */
@Composable
private fun CurrentBadge(accentColor: Color) {
    Spacer(Modifier.width(8.dp))
    Surface(shape = RoundedCornerShape(6.dp), color = accentColor.copy(alpha = 0.25f)) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "Current",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
