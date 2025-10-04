package com.example.openeer.data.search

import com.example.openeer.data.Note
import com.example.openeer.data.tag.TagDao
import kotlin.math.min

class SearchRepository(
    private val searchDao: SearchDao,
    private val tagDao: TagDao // laissé tel quel (non utilisé ici pour ne rien casser)
) {
    suspend fun query(q: String): List<Note> {
        val raw = q.trim()

        // 0) Pas de requête → on remonte tout (LIKE attrape tout, même si FTS vide)
        if (raw.isEmpty()) {
            return safeLike("") // "%%" => toutes les notes
                .ifEmpty { safeFts("*") } // au cas où
        }

        // 1) Parsing léger : tags facultatifs via #tag:... ou #tag ou #mot
        val hintedTags = parseTags(raw)

        // 2) Geo bbox “technique” conservée pour test
        val bbox = parseGeo(raw)
        if (bbox != null) {
            return safeGeo(
                minLat = bbox[0], maxLat = bbox[1],
                minLon = bbox[2], maxLon = bbox[3]
            )
        }

        // 3) Requête FTS tolérante (token* AND token2*)
        val fts = toFtsQuery(raw)

        // 4) Stratégie : on essaie FTS, on complète avec LIKE (ville/adresse/tags/texte)
        val fromFts = if (hintedTags.isNotEmpty()) {
            safeFtsWithTags(fts, hintedTags)
                .ifEmpty { safeFts(fts) } // si pas d’exact match tags, on élargit
        } else {
            safeFts(fts)
        }

        val fromLike = safeLike(raw)

        // 5) Union stable (on garde l’ordre de pertinence du premier, on complète avec le second)
        return mergeUnique(fromFts, fromLike)
    }

    // -----------------------
    // Helpers “safe” (ne jettent pas d’exception)
    // -----------------------

    private suspend fun safeFts(q: String): List<Note> =
        try { searchDao.searchText(q) } catch (_: Throwable) { emptyList() }

    private suspend fun safeFtsWithTags(q: String, tags: List<String>): List<Note> =
        try { searchDao.searchTextWithTags(q, tags) } catch (_: Throwable) { emptyList() }

    private suspend fun safeLike(q: String): List<Note> =
        try { searchDao.searchLike(q) } catch (_: Throwable) { emptyList() }

    private suspend fun safeGeo(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<Note> =
        try { searchDao.searchByGeoBounds(minLat, maxLat, minLon, maxLon) } catch (_: Throwable) { emptyList() }

    private fun mergeUnique(primary: List<Note>, secondary: List<Note>): List<Note> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val seen = HashSet<Long>(min(primary.size + secondary.size, 1024))
        val out = ArrayList<Note>(primary.size + secondary.size)
        for (n in primary) if (seen.add(n.id)) out.add(n)
        for (n in secondary) if (seen.add(n.id)) out.add(n)
        return out
    }

    // -----------------------
    // Parsing “humain”
    // -----------------------

    private fun parseTags(q: String): List<String> {
        val tags = mutableListOf<String>()
        // a) #tag:projet ou #tag:projet,#perso
        q.split(Regex("\\s+")).forEach { part ->
            if (part.startsWith("#tag:", ignoreCase = true)) {
                val chunk = part.removePrefix("#tag:").trim()
                chunk.split(',').map { it.trim() }.filter { it.isNotBlank() }.forEach(tags::add)
            }
        }
        // b) #mot tout court (ex: #travail)
        Regex("#([\\p{L}\\p{N}_-]+)").findAll(q).forEach { m ->
            val name = m.groupValues[1]
            if (name.isNotBlank()) tags += name
        }
        // NB: pour “travail” sans dièse, on ne force rien ici → LIKE couvrira t.name LIKE '%travail%'
        return tags.distinct()
    }

    private fun parseGeo(q: String): DoubleArray? {
        // format technique conservé : geo:minLat,maxLat,minLon,maxLon
        val m = Regex("geo:([+-]?[0-9]*\\.?[0-9]+),([+-]?[0-9]*\\.?[0-9]+),([+-]?[0-9]*\\.?[0-9]+),([+-]?[0-9]*\\.?[0-9]+)")
            .find(q) ?: return null
        return doubleArrayOf(
            m.groupValues[1].toDouble(),
            m.groupValues[2].toDouble(),
            m.groupValues[3].toDouble(),
            m.groupValues[4].toDouble()
        )
    }

    private fun toFtsQuery(q: String): String {
        // On retire les contrôles (#tag:..., geo:..., #mot), on garde le reste
        val core = q.split(Regex("\\s+"))
            .filterNot { it.startsWith("#tag:", true) || it.startsWith("geo:", true) || it.startsWith("#") }
            .joinToString(" ")
            .trim()

        if (core.isBlank()) return "*"

        // Transforme “marseille rendez-vous” → "\"marseille\"* AND \"rendez-vous\"*"
        val tokens = core.split(Regex("\\s+"))
            .mapNotNull { t ->
                val x = t.trim().trim('"')
                if (x.isBlank()) null else x
            }
            .map { tok -> "\"${tok.replace("\"", "\"\"")}\"*" }

        return if (tokens.isEmpty()) "*" else tokens.joinToString(" AND ")
    }
}
