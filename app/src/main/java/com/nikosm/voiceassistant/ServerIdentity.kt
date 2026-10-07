package com.nikosm.voiceassistant

/**
 * Server identity: name uniqueness and the rename rewrite.
 *
 * A persona stores its target as `"[Server Name] model"` (see `AssistantServiceChat`'s
 * `fetchModels`, which builds exactly that string), and every route decision parses the
 * name back out of it (`Persona.requestRouting`). Two consequences drive this file:
 *
 *  - **The name IS the key.** Renaming a server without rewriting those strings leaves
 *    every persona pointing at a server that no longer exists. The app's own guard then
 *    reads that persona as unconfigured and silently strips `spontaneousEnabled` — a
 *    rename that quietly turns off a feature the user never touched.
 *  - **An Ollama name and a cloud-provider name are indistinguishable** inside that
 *    string: `[OpenAI] gpt-4` and a local server literally named "OpenAI" are the same
 *    shape, and `PersonaRouting` picks between them using two different name sets. So the
 *    uniqueness check spans BOTH lists, or a cloud provider could be shadowed by a server.
 *
 * All pure, all unit-tested. The service chokepoint and the persistence live elsewhere.
 */

/** The `"[Name] model"` prefix, or null when [model] carries no such prefix. */
internal fun serverPrefixOf(model: String): String? {
    val m = model.trim()
    if (!m.startsWith("[") || !m.contains("] ")) return null
    return m.substring(1, m.indexOf("]"))
}

/**
 * Rebuild [model] with its server prefix renamed from [oldName] to [newName].
 *
 * Returns the input UNCHANGED when there is no prefix, or when the prefix is not exactly
 * [oldName]. Two cases this must get right, because both are real model names:
 *
 *  - **Brackets inside the model part.** A GGUF name like
 *    `hf.co/x/Qwen[2]-7B-GGUF:q4_k_m` contains `] `. The prefix is therefore everything
 *    up to the FIRST `] `, which is exactly what [serverPrefixOf] returns.
 *  - **A name that is a prefix of another.** Renaming `Main` must NOT touch `[Main2] llm`,
 *    so the comparison is on the extracted prefix as a whole, never `startsWith`.
 */
internal fun rewriteServerPrefix(model: String, oldName: String, newName: String): String {
    if (oldName == newName) return model
    val trimmed = model.trim()
    val prefix = serverPrefixOf(trimmed) ?: return model
    if (prefix != oldName) return model
    // `indexOf("] ")` points AT the closing bracket, so the model part starts one past
    // it and already carries its own leading space. Splicing from indexOf("]") instead
    // would re-emit that bracket and yield "[Work]] llama3".
    return "[$newName]" + trimmed.substring(trimmed.indexOf("] ") + 1)
}

/** [rewriteServerPrefix] across a list, e.g. the stored favorites. */
internal fun rewriteServerPrefixes(
    models: List<String>,
    oldName: String,
    newName: String
): List<String> = models.map { rewriteServerPrefix(it, oldName, newName) }

/** [rewriteServerPrefix] across every persona, leaving identity otherwise untouched. */
internal fun rewritePersonasServerPrefix(
    personas: List<Persona>,
    oldName: String,
    newName: String
): List<Persona> {
    if (oldName == newName) return personas
    return personas.map { p ->
        val rewritten = rewriteServerPrefix(p.model, oldName, newName)
        if (rewritten == p.model) p else p.copy(model = rewritten)
    }
}

/**
 * Repoint the personas that address a server by its URL at an edited URL.
 *
 * A persona addresses its **image-gen** server and its **gateway** through [Persona.backendUrl],
 * which the persona editor sets to the entry's URL (`backendUrl = cfg.url`). Editing that entry's
 * URL therefore used to leave every persona pointing at the OLD address: the resolver found no
 * matching entry, and the request went out to the stale address WITHOUT credentials — a 401 while
 * "Test Connection" (which reads the saved entry directly) succeeded. Rewriting the references
 * here, at the same chokepoint as the Ollama rename migration, keeps the persona and the entry in
 * step.
 *
 * Matching is EXACT on the NORMALIZED URL (trim + trailing slashes), so a persona saved as
 * `http://host:7860/` still follows an edit of `http://host:7860`, and a URL that merely shares a
 * prefix does NOT.
 *
 * [forImageGenerators] selects WHICH references this edit may touch, and is what keeps the two
 * registries from clobbering each other:
 *  - an image-gen URL edit rewrites only image-generator personas ([forImageGenerators] = true);
 *  - a gateway URL edit rewrites only non-image-generator (gateway/translator) personas
 *    ([forImageGenerators] = false).
 * A persona whose backendUrl happens to equal a DIFFERENT server type's URL is therefore left
 * alone — the gateway and image-gen lists are independent, and the same host can back both.
 */
internal fun rewritePersonasBackendUrl(
    personas: List<Persona>,
    oldUrl: String,
    newUrl: String,
    forImageGenerators: Boolean
): List<Persona> {
    if (oldUrl == newUrl) return personas
    val oldNorm = normalizeGatewayUrl(oldUrl)
    if (oldNorm.isEmpty()) return personas
    return personas.map { p ->
        if (p.isImageGenerator == forImageGenerators && normalizeGatewayUrl(p.backendUrl) == oldNorm) {
            p.copy(backendUrl = newUrl)
        } else p
    }
}



/**
 * Is [candidate] already used as a server or cloud-provider name?
 *
 * Trimmed and case-insensitive, because "main" and "Main " are indistinguishable inside a
 * `"[Name] model"` string and would collide on every lookup that does not trim.
 *
 * @param exclude existing names to ignore — the entry being edited, so saving a server
 *   without changing its name is not a self-collision.
 *
 * Note the asymmetry with a duplicate PAIR: excluding by name removes EVERY entry called
 * "Main", not just the one being edited. That is deliberate. If two servers already share
 * a name, renaming either one to yet another "Main" is not a new ambiguity — the pair
 * already existed — while renaming one out to a fresh name is exactly the repair the user
 * needs. The service passes the edited entry's name; see `updateOllamaBase`.
 */
internal fun isServerNameTaken(
    candidate: String,
    existing: Collection<String>,
    exclude: Collection<String> = emptyList()
): Boolean {
    val wanted = candidate.trim()
    val excluded = exclude.map { it.trim() }.toSet()
    return existing.any { name ->
        val t = name.trim()
        !excluded.contains(t) && t.equals(wanted, ignoreCase = true)
    }
}
