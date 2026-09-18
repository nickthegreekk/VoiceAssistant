package com.nikosm.voiceassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L6: pins the ordering of [matchPricingEntry] — which stored pricing row a persona's
 * display label ("[Anthropic] claude-3-5-sonnet-latest") resolves to.
 *
 * The heuristic itself is unchanged (token counting against the entry ids). What was
 * broken is that ties were broken by `maxByOrNull`, which keeps the FIRST maximum, i.e.
 * by map iteration order: "openai/gpt-4o" and "openai/gpt-4o-mini" both score 3 for the
 * label "gpt-4o" (tokens "gpt", "4o", and the vendor "openai"), so which model's rate was
 * charged depended on the order the pricing table happened to come back in. The
 * comparator is now a total order, and the order-sensitive expectations below are
 * asserted against BOTH insertion orders — a recidivist "first maximum wins"
 * implementation fails one of them.
 *
 * Extracted as a pure function (no Android surface) precisely so this can run on the JVM
 * with a synthetic table instead of a live gateway.
 */
class PricingMatchTest {

    private companion object {
        // Distinguishable rates so a test can tell which ROW won, not just which key.
        val CANONICAL = ModelPricing(prompt = 2.5, completion = 10.0)
        val SNAPSHOT = ModelPricing(prompt = 99.0, completion = 99.0)
        val MINI = ModelPricing(prompt = 0.15, completion = 0.6)
    }

    /** A pricing table whose iteration order is exactly the argument order. */
    private fun table(vararg entries: Pair<String, ModelPricing>): Map<String, ModelPricing> =
        linkedMapOf(*entries)

    /** Both insertion orders of [entries], for the order-independence assertions. */
    private fun bothOrders(vararg entries: Pair<String, ModelPricing>) =
        listOf(table(*entries), table(*entries.reversedArray()))

    @Test
    fun aDatedSnapshotLosesToTheCanonicalEntryRegardlessOfTableOrder() {
        bothOrders(
            "openai/gpt-4o-mini-2024-07-18" to SNAPSHOT,
            "openai/gpt-4o-mini" to MINI
        ).forEach { pricing ->
            val match = matchPricingEntry(pricing, "[OpenAI] gpt-4o-mini")

            assertEquals(
                "the dated snapshot beat the undated alias for '${pricing.keys}'",
                "openai/gpt-4o-mini",
                match?.first
            )
            assertEquals(MINI, match?.second)
        }
    }

    @Test
    fun theEntryThatIsTheModelBeatsOneThatMerelySharesItsTokens() {
        // Both rows score 2 for "gpt-4o" (tokens "gpt" and "4o"), so only the specificity
        // of the tie-break separates them: "openai/gpt-4o-mini" merely CONTAINS the model
        // portion, "somevendor/gpt-4o" ends with it.
        bothOrders(
            "openai/gpt-4o-mini" to MINI,
            "somevendor/gpt-4o" to CANONICAL
        ).forEach { pricing ->
            assertEquals(
                "a token-sharing entry beat the entry that IS the model",
                "somevendor/gpt-4o",
                matchPricingEntry(pricing, "gpt-4o")?.first
            )
        }
    }

    @Test
    fun anExactModelMatchBeatsAMoreSpecificSuffix() {
        bothOrders(
            "openai/gpt-4o-2024-08-06" to SNAPSHOT,
            "openai/gpt-4o" to CANONICAL
        ).forEach { pricing ->
            assertEquals(
                "the dated gpt-4o entry won over the exact model entry",
                "openai/gpt-4o",
                matchPricingEntry(pricing, "[OpenAI] gpt-4o")?.first
            )
        }
    }

    @Test
    fun theAliasTokenLatestDoesNotChangeWhichRowIsChosen() {
        // "-latest" is one of the ignored qualifiers, so the label reduces to "gpt-4o" and
        // must resolve to the canonical row — never to the "-latest" row it names.
        bothOrders(
            "openai/gpt-4o-latest" to SNAPSHOT,
            "openai/gpt-4o" to CANONICAL
        ).forEach { pricing ->
            assertEquals(
                "the '-latest' alias row won instead of the canonical model",
                "openai/gpt-4o",
                matchPricingEntry(pricing, "[OpenAI] gpt-4o-latest")?.first
            )
        }
    }

    @Test
    fun theVendorInTheLabelHasToAppearInTheEntryId() {
        val pricing = table(
            "anthropic/claude-3-5-sonnet" to SNAPSHOT,
            "openai/claude-clone" to CANONICAL
        )

        val match = matchPricingEntry(pricing, "[Anthropic] claude-3-5-sonnet")

        assertEquals(
            "the label's vendor was ignored when scoring",
            "anthropic/claude-3-5-sonnet",
            match?.first
        )
        assertEquals(SNAPSHOT, match?.second)
    }

    @Test
    fun aSingleTokenLabelMatchesOnOneHit() {
        // A one-token label can never reach the usual two-hit threshold, so the threshold
        // relaxes to one; without that, short local model names matched nothing at all.
        val match = matchPricingEntry(table("google/gemma-2-9b-it" to CANONICAL), "gemma")

        assertEquals("google/gemma-2-9b-it", match?.first)
    }

    // ---------------------------------------------------------------------
    // No-match and malformed-label cases: null, never a throw and never a guess.
    // ---------------------------------------------------------------------

    @Test
    fun anUnrelatedTableYieldsNoMatch() {
        assertNull(
            matchPricingEntry(
                table("anthropic/claude-3-5-sonnet" to SNAPSHOT),
                "[OpenAI] gpt-4o"
            )
        )
    }

    @Test
    fun anEmptyTableYieldsNoMatch() {
        assertNull(matchPricingEntry(emptyMap(), "[OpenAI] gpt-4o"))
    }

    @Test
    fun anEmptyLabelYieldsNoMatch() {
        val pricing = table("openai/gpt-4o" to CANONICAL)

        assertNull(matchPricingEntry(pricing, ""))
        assertNull(matchPricingEntry(pricing, "   "))
        assertNull(matchPricingEntry(pricing, "-"))
    }

    @Test
    fun anUnclosedProviderBracketIsHandledInsteadOfThrowing() {
        // A hand-typed persona label can lose its "]": the old code sliced
        // `substring(1, closingBracket)` with closingBracket == -1 and threw from inside
        // the response path. It is now simply treated as "no provider".
        val pricing = table("openai/gpt-4o" to CANONICAL)

        assertNull(matchPricingEntry(pricing, "[foo"))
        assertNull(matchPricingEntry(pricing, "["))
        assertNull(matchPricingEntry(pricing, "]"))
    }

    @Test
    fun aModelPartAfterAnUnclosedBracketStillMatches() {
        // Same shape as the persona editor's "[Provider] model" labels, but with the
        // bracket lost — the model tokens after "] " are still usable evidence.
        val match = matchPricingEntry(table("openai/gpt-4o" to CANONICAL), "[foo] gpt-4o")

        assertEquals("openai/gpt-4o", match?.first)
    }

    @Test
    fun aLabelWithNoModelPartFallsBackToTheVendorMatch() {
        // Degenerate but reachable: everything after "] " is blank, so the vendor token is
        // the only evidence left and the vendor's row is the honest answer.
        val match = matchPricingEntry(table("openai/gpt-4o" to CANONICAL), "[OpenAI]    ")

        assertEquals("openai/gpt-4o", match?.first)
    }
}
