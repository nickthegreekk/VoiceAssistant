package com.nikosm.voiceassistant

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings home screen's row summaries and section grouping.
 *
 * These are the parts of the redesign that carry INFORMATION rather than looks, so they are
 * pinned as pure functions: a row that says "2 Ollama, 1 gateways" or drops the Cloud summary
 * is a bug a screenshot would not reliably catch, but these assertions will.
 *
 * The summaries deliberately read only state the dialog already collects, so there is nothing
 * here about disk or the service — only formatting rules.
 */
class SettingsCategorySummaryTest {

    // ------------------------------------------------------------- counted items

    @Test fun `countedItems pluralizes at n greater than one`() {
        assertEquals("1 persona", countedItems(1, "persona", "personas"))
        assertEquals("2 personas", countedItems(2, "persona", "personas"))
        assertEquals("0 personas", countedItems(0, "persona", "personas"))
    }

    @Test fun `countedItems keeps an invariant label invariant`() {
        // "Ollama" is a product name, not a countable noun.
        assertEquals("1 Ollama", countedItems(1, "Ollama", "Ollama"))
        assertEquals("4 Ollama", countedItems(4, "Ollama", "Ollama"))
    }

    // ------------------------------------------------------------- servers

    @Test fun `serversSummary reports every configured kind in order`() {
        assertEquals(
            "2 Ollama, 1 gateway, 1 image-gen",
            serversSummary(ollama = 2, gateway = 1, imageGen = 1)
        )
    }

    @Test fun `serversSummary omits the kinds that are not configured`() {
        // "2 Ollama, 0 gateway" is noise; the absent kind should simply not appear.
        assertEquals("2 Ollama", serversSummary(ollama = 2, gateway = 0, imageGen = 0))
        assertEquals("1 gateway", serversSummary(ollama = 0, gateway = 1, imageGen = 0))
        assertEquals("3 image-gen", serversSummary(ollama = 0, gateway = 0, imageGen = 3))
        assertEquals(
            "1 Ollama, 2 image-gen",
            serversSummary(ollama = 1, gateway = 0, imageGen = 2)
        )
    }

    @Test fun `serversSummary pluralizes gateway but not the product names`() {
        assertEquals("2 gateways", serversSummary(ollama = 0, gateway = 2, imageGen = 0))
        assertEquals(
            "1 Ollama, 2 gateways, 2 image-gen",
            serversSummary(ollama = 1, gateway = 2, imageGen = 2)
        )
    }

    @Test fun `serversSummary says so when nothing is configured at all`() {
        assertEquals("No servers yet", serversSummary(ollama = 0, gateway = 0, imageGen = 0))
    }

    // ------------------------------------------------------------- cloud

    @Test fun `cloudSummary counts only providers that are actually set up`() {
        // Built-in providers exist in the list with or without a key, so the count is of
        // keyed ones plus custom ones — never of the raw list length.
        assertEquals("3 providers", cloudSummary(builtInWithKey = 3, custom = 0))
        assertEquals("1 provider", cloudSummary(builtInWithKey = 1, custom = 0))
        assertEquals("2 providers", cloudSummary(builtInWithKey = 0, custom = 2))
        assertEquals("4 providers", cloudSummary(builtInWithKey = 3, custom = 1))
    }

    @Test fun `cloudSummary says so when no provider is set up`() {
        assertEquals("None set up yet", cloudSummary(builtInWithKey = 0, custom = 0))
    }

    // ------------------------------------------------------------- counts

    @Test fun `personas rooms and skills handle zero one and many`() {
        assertEquals("No personas yet", personasSummary(0))
        assertEquals("1 persona", personasSummary(1))
        assertEquals("14 personas", personasSummary(14))

        assertEquals("No rooms yet", roomsSummary(0))
        assertEquals("1 room", roomsSummary(1))
        assertEquals("2 rooms", roomsSummary(2))

        assertEquals("No skills yet", skillsSummary(0))
        assertEquals("1 skill", skillsSummary(1))
        assertEquals("4 skills", skillsSummary(4))
    }

    // ------------------------------------------------------------- grouping

    @Test fun `sections appear in registry order with their own members`() {
        val groups = settingsCategoryGroups()
        assertEquals(
            listOf("App", "Connections", "Assistants"),
            groups.map { it.caption }
        )
        assertEquals(listOf("General", "About"), groups[0].categories.map { it.title })
        assertEquals(listOf("Servers", "Cloud", "MCP"), groups[1].categories.map { it.title })
        assertEquals(listOf("Personas", "Rooms", "Skills"), groups[2].categories.map { it.title })
    }

    @Test fun `grouping is derived from the registry so every category appears exactly once`() {
        val grouped = settingsCategoryGroups().flatMap { it.categories }
        assertEquals("no category may be dropped or duplicated", SETTINGS_CATEGORIES, grouped)
    }

    @Test fun `a future category joins its section without touching the composable`() {
        // The registry is the single source: adding an entry to a group is all it takes, and
        // the derived grouping must file it under that caption in the right position.
        val extended = SETTINGS_CATEGORIES + SettingsCategory(
            "Knowledge", Icons.Default.Book, SettingsGroup.CONNECTIONS
        )
        val groups = settingsCategoryGroups(extended)
        assertEquals(
            listOf("Servers", "Cloud", "MCP", "Knowledge"),
            groups.first { it.caption == "Connections" }.categories.map { it.title }
        )
        // Note the order: the new entry is filed UNDER its section, so it appears with
        // Connections rather than where it was appended to the flat list. That is the
        // grouping being derived from the registry rather than from append order.
        assertEquals(
            listOf("General", "About", "Servers", "Cloud", "MCP", "Knowledge", "Personas", "Rooms", "Skills"),
            groups.flatMap { it.categories }.map { it.title }
        )
        assertEquals(
            "every category survives, exactly once",
            extended.map { it.title }.sorted(),
            groups.flatMap { it.categories }.map { it.title }.sorted()
        )
    }

    // ------------------------------------------------------------- the whole set

    @Test fun `every category has a summary and none is blank`() {
        val summaries = settingsCategorySummaries(
            ollamaServers = 2,
            gatewayServers = 1,
            imageGenServers = 0,
            cloudProvidersWithKey = 3,
            customCloudProviders = 0,
            personas = 14,
            rooms = 2,
            skills = 4,
            mcpServers = 1
        )
        assertEquals(SETTINGS_CATEGORIES, summaries.keys.toList())
        summaries.forEach { (category, summary) ->
            // "About" intentionally has no summary -- it's the screen's own landing label.
            if (category.title != "About") {
                assertTrue("'" + category.title + "'" + " must have a summary", summary.isNotBlank())
            }
        }
        assertEquals("2 Ollama, 1 gateway", summaries[SETTINGS_CATEGORIES.first { it.title == "Servers" }])
        assertEquals("14 personas", summaries[SETTINGS_CATEGORIES.first { it.title == "Personas" }])
        assertEquals(GENERAL_SETTINGS_SUMMARY, summaries[SETTINGS_CATEGORIES.first { it.title == "General" }])
    }

    @Test fun `an empty configuration still describes every category`() {
        val summaries = settingsCategorySummaries(0, 0, 0, 0, 0, 0, 0, 0)
        // About is excluded because it intentionally has a blank summary.
        assertEquals(
            listOf(
                "No servers yet",
                "None set up yet",
                "No tool servers yet",
                "No personas yet",
                "No rooms yet",
                "No skills yet"
            ),
            summaries.filterKeys { it.title != "General" && it.title != "About" }.values.toList()
        )
    }
}
