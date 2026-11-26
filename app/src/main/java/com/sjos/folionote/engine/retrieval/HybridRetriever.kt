package com.sjos.folionote.engine.retrieval

import com.sjos.folionote.data.local.DocumentChunkEntity
import com.sjos.folionote.data.local.EmbeddingEntity
import com.sjos.folionote.data.local.SourceEntity
import com.sjos.folionote.data.local.VectorCodec
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

data class RetrievedPassage(
    val chunk: DocumentChunkEntity,
    val sourceName: String,
    val lexicalScore: Float,
    val semanticScore: Float,
    val fusedScore: Float
)

/**
 * Hybrid RAG retriever combining offline BM25 lexical search and cosine semantic similarity
 * via Reciprocal Rank Fusion (RRF), scoped strictly to notebook and selected sources.
 */
class HybridRetriever {

    private val stopwords = setOf(
        "de", "da", "do", "das", "dos", "a", "o", "as", "os", "e", "em", "um", "uma",
        "para", "com", "por", "que", "se", "no", "na", "nos", "nas", "ao", "aos",
        "the", "and", "of", "to", "in", "is", "it", "for", "on", "with", "as", "at"
    )

    fun retrieve(
        query: String,
        sources: List<SourceEntity>,
        chunks: List<DocumentChunkEntity>,
        embeddings: List<EmbeddingEntity>,
        queryEmbedding: FloatArray?,
        topK: Int = 6
    ): List<RetrievedPassage> {
        if (chunks.isEmpty()) return emptyList()
        val sourceNames = sources.associate { it.id to it.name }
        val allowedSourceIds = sources.filter { it.isSelectedForChat }.map { it.id }.toSet()
        val scopedChunks = chunks.filter { it.sourceId in allowedSourceIds }
        if (scopedChunks.isEmpty()) return emptyList()

        val lexicalRanked = scoreLexicalBm25(query, scopedChunks)
        val semanticRanked = if (queryEmbedding != null && embeddings.isNotEmpty()) {
            scoreSemanticCosine(queryEmbedding, scopedChunks, embeddings)
        } else {
            emptyMap()
        }

        val lexicalRankOrder = lexicalRanked.entries
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .mapIndexed { idx, entry -> entry.key to (idx + 1) }
            .toMap()

        val semanticRankOrder = semanticRanked.entries
            .filter { it.value > 0.1f }
            .sortedByDescending { it.value }
            .mapIndexed { idx, entry -> entry.key to (idx + 1) }
            .toMap()

        val rrfK = 60f
        val candidates = scopedChunks.mapNotNull { chunk ->
            val lexScore = lexicalRanked[chunk.id] ?: 0f
            val semScore = semanticRanked[chunk.id] ?: 0f
            val lexRank = lexicalRankOrder[chunk.id]
            val semRank = semanticRankOrder[chunk.id]

            if (lexRank == null && semRank == null) {
                null
            } else {
                val rrf = (lexRank?.let { 1f / (rrfK + it) } ?: 0f) +
                    (semRank?.let { 1.15f / (rrfK + it) } ?: 0f)
                RetrievedPassage(
                    chunk = chunk,
                    sourceName = sourceNames[chunk.sourceId] ?: "Fonte",
                    lexicalScore = lexScore,
                    semanticScore = semScore,
                    fusedScore = rrf
                )
            }
        }

        val sorted = if (candidates.isNotEmpty()) {
            candidates.sortedByDescending { it.fusedScore }
        } else {
            emptyList()
        }

        return deduplicatePassages(sorted).take(topK.coerceAtLeast(1))
    }

    fun searchLexicalOffline(
        query: String,
        sources: List<SourceEntity>,
        chunks: List<DocumentChunkEntity>,
        limit: Int = 30
    ): List<RetrievedPassage> {
        if (query.isBlank() || chunks.isEmpty()) return emptyList()
        val sourceNames = sources.associate { it.id to it.name }
        val scores = scoreLexicalBm25(query, chunks)
        return chunks.mapNotNull { chunk ->
            val score = scores[chunk.id] ?: 0f
            if (score <= 0f) null
            else RetrievedPassage(
                chunk = chunk,
                sourceName = sourceNames[chunk.sourceId] ?: "Fonte",
                lexicalScore = score,
                semanticScore = 0f,
                fusedScore = score
            )
        }.sortedByDescending { it.lexicalScore }.take(limit)
    }

    suspend fun retrieveOnDemand(
        query: String,
        sources: List<SourceEntity>,
        queryEmbedding: FloatArray?,
        topK: Int = 6,
        pageSize: Int = 64,
        loadChunkPage: suspend (limit: Int, offset: Int) -> List<DocumentChunkEntity>,
        loadEmbeddingPage: suspend (limit: Int, offset: Int) -> List<EmbeddingEntity>,
        loadChunksByIds: suspend (ids: List<String>) -> List<DocumentChunkEntity>
    ): List<RetrievedPassage> {
        val allowedSourceIds = sources.filter { it.isSelectedForChat }.map { it.id }.toSet()
        if (allowedSourceIds.isEmpty()) return emptyList()
        val sourceNames = sources.associate { it.id to it.name }
        val safePageSize = pageSize.coerceAtLeast(1)
        val safeTopK = topK.coerceAtLeast(1)
        val candidateCap = (safeTopK * 8).coerceAtLeast(32)

        val lexicalRanked = scoreLexicalBm25OnDemand(
            query = query,
            allowedSourceIds = allowedSourceIds,
            pageSize = safePageSize,
            candidateCap = candidateCap,
            loadChunkPage = loadChunkPage
        )
        val semanticRanked = if (queryEmbedding != null) {
            scoreSemanticCosineOnDemand(
                queryVector = queryEmbedding,
                allowedSourceIds = allowedSourceIds,
                pageSize = safePageSize,
                candidateCap = candidateCap,
                loadEmbeddingPage = loadEmbeddingPage
            )
        } else {
            emptyMap()
        }

        if (lexicalRanked.isEmpty() && semanticRanked.isEmpty()) return emptyList()

        val lexicalRankOrder = lexicalRanked.entries
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .mapIndexed { idx, entry -> entry.key to (idx + 1) }
            .toMap()

        val semanticRankOrder = semanticRanked.entries
            .filter { it.value > 0.1f }
            .sortedByDescending { it.value }
            .mapIndexed { idx, entry -> entry.key to (idx + 1) }
            .toMap()

        val candidateIds = (lexicalRankOrder.keys + semanticRankOrder.keys).distinct()
        if (candidateIds.isEmpty()) return emptyList()

        val rrfK = 60f
        val scoredIds = candidateIds.map { id ->
            val lexRank = lexicalRankOrder[id]
            val semRank = semanticRankOrder[id]
            val rrf = (lexRank?.let { 1f / (rrfK + it) } ?: 0f) +
                (semRank?.let { 1.15f / (rrfK + it) } ?: 0f)
            id to rrf
        }.sortedByDescending { it.second }.take(candidateCap)

        val loadedById = scoredIds.map { it.first }
            .chunked(900)
            .flatMap { batch -> loadChunksByIds(batch) }
            .associateBy { it.id }

        val passages = scoredIds.mapNotNull { (chunkId, rrf) ->
            val chunk = loadedById[chunkId] ?: return@mapNotNull null
            RetrievedPassage(
                chunk = chunk,
                sourceName = sourceNames[chunk.sourceId] ?: "Fonte",
                lexicalScore = lexicalRanked[chunkId] ?: 0f,
                semanticScore = semanticRanked[chunkId] ?: 0f,
                fusedScore = rrf
            )
        }

        return deduplicatePassages(passages).take(safeTopK)
    }

    suspend fun searchLexicalOnDemand(
        query: String,
        sources: List<SourceEntity>,
        limit: Int = 30,
        pageSize: Int = 64,
        loadChunkPage: suspend (limit: Int, offset: Int) -> List<DocumentChunkEntity>,
        loadChunksByIds: suspend (ids: List<String>) -> List<DocumentChunkEntity>
    ): List<RetrievedPassage> {
        if (query.isBlank() || sources.isEmpty()) return emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        val sourceNames = sources.associate { it.id to it.name }
        val allowedIds = sources.map { it.id }.toSet()
        val topScores = scoreLexicalBm25OnDemand(
            query = query,
            allowedSourceIds = allowedIds,
            pageSize = pageSize.coerceAtLeast(1),
            candidateCap = safeLimit * 2,
            loadChunkPage = loadChunkPage
        )
        if (topScores.isEmpty()) return emptyList()

        val sortedIds = topScores.entries.sortedByDescending { it.value }.take(safeLimit)
        val chunksById = sortedIds.map { it.key }
            .chunked(900)
            .flatMap { loadChunksByIds(it) }
            .associateBy { it.id }

        return sortedIds.mapNotNull { (chunkId, score) ->
            val chunk = chunksById[chunkId] ?: return@mapNotNull null
            RetrievedPassage(
                chunk = chunk,
                sourceName = sourceNames[chunk.sourceId] ?: "Fonte",
                lexicalScore = score,
                semanticScore = 0f,
                fusedScore = score
            )
        }
    }

    private data class CompactLexicalMatch(
        val chunkId: String,
        val docLength: Int,
        val queryTermFreqs: IntArray,
        val hasExactPhrase: Boolean
    )

    private suspend fun scoreLexicalBm25OnDemand(
        query: String,
        allowedSourceIds: Set<String>,
        pageSize: Int,
        candidateCap: Int,
        loadChunkPage: suspend (limit: Int, offset: Int) -> List<DocumentChunkEntity>
    ): Map<String, Float> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyMap()
        val uniqueQueryTerms = queryTokens.distinct()
        val queryTermIndex = uniqueQueryTerms.withIndex().associate { (idx, term) -> term to idx }
        val normalizedQuery = normalizeText(query)

        val docFreq = IntArray(uniqueQueryTerms.size)
        val matchingDocs = mutableListOf<CompactLexicalMatch>()
        var totalDocs = 0L
        var totalTokens = 0L
        var offset = 0

        while (true) {
            val page = loadChunkPage(pageSize, offset)
            if (page.isEmpty()) break
            for (chunk in page) {
                if (chunk.sourceId !in allowedSourceIds) continue
                val tokens = tokenize("${chunk.sectionTitle} ${chunk.content}")
                totalDocs++
                totalTokens += tokens.size
                if (tokens.isEmpty()) continue

                val tfArray = IntArray(uniqueQueryTerms.size)
                var hasAnyMatch = false
                for (token in tokens) {
                    val qIdx = queryTermIndex[token] ?: continue
                    tfArray[qIdx]++
                    hasAnyMatch = true
                }
                if (hasAnyMatch) {
                    for (i in tfArray.indices) {
                        if (tfArray[i] > 0) docFreq[i]++
                    }
                    val hasPhrase = normalizedQuery.length >= 4 &&
                        normalizeText(chunk.content).contains(normalizedQuery)
                    matchingDocs.add(
                        CompactLexicalMatch(
                            chunkId = chunk.id,
                            docLength = tokens.size,
                            queryTermFreqs = tfArray,
                            hasExactPhrase = hasPhrase
                        )
                    )
                }
            }
            if (page.size < pageSize) break
            offset += page.size
        }

        if (totalDocs == 0L || matchingDocs.isEmpty()) return emptyMap()
        val avgDocLen = (totalTokens.toDouble() / totalDocs.toDouble()).coerceAtLeast(1.0)
        val totalDocsDouble = totalDocs.toDouble()
        val k1 = 1.5
        val b = 0.75

        val scored = ArrayList<Pair<String, Float>>(matchingDocs.size)
        for (doc in matchingDocs) {
            var score = 0.0
            val normLen = doc.docLength / avgDocLen
            for (qTerm in queryTokens) {
                val qIdx = queryTermIndex[qTerm] ?: continue
                val tf = doc.queryTermFreqs[qIdx]
                if (tf == 0) continue
                val df = docFreq[qIdx]
                val idf = ln(((totalDocsDouble - df + 0.5) / (df + 0.5)) + 1.0)
                val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * normLen))
                score += idf * tfNorm
            }
            if (score > 0.0 && doc.hasExactPhrase) {
                score *= 1.35
            }
            if (score > 0.0) {
                scored.add(doc.chunkId to score.toFloat())
            }
        }
        return scored.sortedByDescending { it.second }
            .take(candidateCap)
            .toMap()
    }

    private suspend fun scoreSemanticCosineOnDemand(
        queryVector: FloatArray,
        allowedSourceIds: Set<String>,
        pageSize: Int,
        candidateCap: Int,
        loadEmbeddingPage: suspend (limit: Int, offset: Int) -> List<EmbeddingEntity>
    ): Map<String, Float> {
        val topHeap = java.util.PriorityQueue<Pair<String, Float>>(compareBy { it.second })
        var offset = 0
        while (true) {
            val page = loadEmbeddingPage(pageSize, offset)
            if (page.isEmpty()) break
            for (emb in page) {
                if (emb.sourceId !in allowedSourceIds) continue
                val vec = VectorCodec.bytesToFloatArray(emb.vectorBlob)
                if (vec.size == queryVector.size && vec.isNotEmpty()) {
                    val cos = cosineSimilarity(queryVector, vec)
                    if (cos > 0f) {
                        if (topHeap.size < candidateCap) {
                            topHeap.offer(emb.chunkId to cos)
                        } else if (cos > (topHeap.peek()?.second ?: 0f)) {
                            topHeap.poll()
                            topHeap.offer(emb.chunkId to cos)
                        }
                    }
                }
            }
            if (page.size < pageSize) break
            offset += page.size
        }
        val result = HashMap<String, Float>(topHeap.size)
        for ((id, score) in topHeap) {
            result[id] = score
        }
        return result
    }

    private fun scoreLexicalBm25(query: String, chunks: List<DocumentChunkEntity>): Map<String, Float> {
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return emptyMap()
        val normalizedQuery = normalizeText(query)

        val tokenizedDocs = chunks.associate { chunk ->
            chunk.id to tokenize("${chunk.sectionTitle} ${chunk.content}")
        }
        val avgDocLen = tokenizedDocs.values.map { it.size }.average().coerceAtLeast(1.0)
        val totalDocs = chunks.size.toDouble()

        val docFreq = mutableMapOf<String, Int>()
        for (tokens in tokenizedDocs.values) {
            for (term in tokens.toSet()) {
                docFreq[term] = (docFreq[term] ?: 0) + 1
            }
        }

        val k1 = 1.5
        val b = 0.75
        val result = mutableMapOf<String, Float>()

        for (chunk in chunks) {
            val docTokens = tokenizedDocs[chunk.id].orEmpty()
            if (docTokens.isEmpty()) continue

            val tfMap = mutableMapOf<String, Int>()
            for (t in docTokens) tfMap[t] = (tfMap[t] ?: 0) + 1

            var score = 0.0
            for (qTerm in queryTokens) {
                val tf = tfMap[qTerm] ?: 0
                if (tf == 0) continue
                val df = docFreq[qTerm] ?: 0
                val idf = ln(((totalDocs - df + 0.5) / (df + 0.5)) + 1.0)
                val normLen = docTokens.size / avgDocLen
                val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * normLen))
                score += idf * tfNorm
            }

            if (score > 0.0 && normalizedQuery.length >= 4 && normalizeText(chunk.content).contains(normalizedQuery)) {
                score *= 1.35
            }

            if (score > 0.0) {
                result[chunk.id] = score.toFloat()
            }
        }
        return result
    }

    private fun scoreSemanticCosine(
        queryVector: FloatArray,
        chunks: List<DocumentChunkEntity>,
        embeddings: List<EmbeddingEntity>
    ): Map<String, Float> {
        val embByChunk = embeddings.associateBy { it.chunkId }
        val result = mutableMapOf<String, Float>()
        for (chunk in chunks) {
            val emb = embByChunk[chunk.id] ?: continue
            val vec = VectorCodec.bytesToFloatArray(emb.vectorBlob)
            if (vec.size == queryVector.size && vec.isNotEmpty()) {
                val cos = cosineSimilarity(queryVector, vec)
                if (cos > 0f) result[chunk.id] = cos
            }
        }
        return result
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            dot += av * bv
            normA += av * av
            normB += bv * bv
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom <= 1e-9) 0f else (dot / denom).toFloat()
    }

    private fun deduplicatePassages(passages: List<RetrievedPassage>): List<RetrievedPassage> {
        val seenHashes = mutableSetOf<String>()
        val result = mutableListOf<RetrievedPassage>()
        for (p in passages) {
            val fingerprint = p.chunk.content.take(180).trim()
            if (seenHashes.add(fingerprint)) {
                result.add(p)
            }
        }
        return result
    }

    private fun tokenize(input: String): List<String> {
        return normalizeText(input)
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in stopwords }
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }
}
