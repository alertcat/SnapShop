package com.example.snapshop

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LLM-based Vision Helper using OpenRouter API
 *
 * Replaces Google Cloud Vision API with multimodal LLM for product identification.
 * Uses a tiered cascade strategy:
 *   Tier 1: google/gemini-2.5-flash-lite (cheap, fast, good for most products)
 *   Tier 2: user-configured model (e.g. google/gemini-3-flash-preview) for uncertain cases
 *
 * Cost comparison vs Google Vision API ($6.50/1000 images):
 *   Tier 1 only: ~$0.10/1000 images (≤384px, ~258 input tokens + ~200 output tokens)
 *   Tier 1+2:    ~$0.50/1000 images (worst case, every image needs upgrade)
 *
 * Image strategy: resize to ≤384px on both sides → 258 tokens in Gemini
 *
 * Setup: Set OPENROUTER_API_KEY and OPENROUTER_MODEL in local.properties
 */
object LlmVisionHelper {

    private const val TAG = "LlmVisionHelper"

    // API credentials from BuildConfig (set in local.properties)
    private val API_KEY = BuildConfig.OPENROUTER_API_KEY
    private val UPGRADE_MODEL = BuildConfig.OPENROUTER_MODEL

    private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"

    // Tier 1: cheap default model for most identifications
    private const val DEFAULT_MODEL = "google/gemini-2.5-flash-lite"

    // Confidence threshold — below this, auto-upgrade to Tier 2
    private const val CONFIDENCE_THRESHOLD = 0.6

    // Max image dimension (both sides ≤384px → 258 tokens in Gemini)
    private const val MAX_IMAGE_SIZE = 384

    /**
     * Structured product identification result
     */
    data class ProductInfo(
        val brand: String,
        val model: String,
        val category: String,
        val keyAttributes: List<String>,
        val searchQuery: String,
        val confidence: Double,
        val notes: String,
        val tier: Int  // 1 = default model, 2 = upgraded model
    )

    /**
     * Progress callback for UI status updates
     */
    interface ProgressCallback {
        fun onProgress(message: String)
    }

    /**
     * Build the identification prompt with current date injected.
     *
     * KEY DESIGN: The prompt focuses on OBSERVABLE PHYSICAL ATTRIBUTES rather than
     * guessed model numbers, because LLM training data has a cutoff date and cannot
     * know about products released after training. For example, without this design,
     * an iPhone 16 Pro Max in Desert Titanium would be misidentified as iPhone 14 Pro Max Gold
     * because the model doesn't know iPhone 16 exists.
     *
     * By focusing on what the model can SEE (color, camera layout, materials, form factor),
     * the generated searchQuery will match the correct product on shopping platforms
     * even if the LLM doesn't know the exact model name.
     */
    private fun buildIdentificationPrompt(): String {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return """
You are a product identification expert. Today's date is $currentDate.

CRITICAL: Your training data has a knowledge cutoff date. Products released AFTER your training
may exist but be unknown to you. DO NOT guess a model number you are unsure about.
When uncertain about the exact model/generation, describe what you OBSERVE instead.

Analyze the image and identify the product(s) visible.

Return ONLY a valid JSON object (no markdown, no code fences, no extra text) with these fields:
{
  "brand": "brand/manufacturer name, empty string if unknown",
  "model": "specific model name/number, empty string if uncertain — NEVER GUESS",
  "category": "product category (e.g. smartphone, laptop, shoes, headphones)",
  "key_attributes": ["observed color/finish", "material", "camera count & layout", "form factor", "any visible text/markings", "distinctive design features"],
  "searchQuery": "the best search query to find this exact product for purchase on Amazon/eBay — MUST use observed physical attributes",
  "confidence": 0.0,
  "notes": "what made identification uncertain, if anything"
}

RULES FOR ACCURATE IDENTIFICATION:

1. DESCRIBE WHAT YOU SEE, not what you assume:
   - Color: use the EXACT color you observe (e.g. "desert titanium", "natural titanium", NOT "gold" if it's not gold)
   - Camera: count cameras and describe their arrangement (e.g. "triple camera diagonal layout")
   - Material: note visible materials (e.g. "titanium frame", "glass back", "matte finish")
   - Ports/buttons: note USB-C vs Lightning, action button vs mute switch, etc.
   - Size: estimate relative size (e.g. "large/max size", "compact/mini")

2. MODEL NUMBER RULES:
   - Only provide "model" if you are CERTAIN (confidence > 0.85 for that specific generation)
   - If unsure about the exact generation/year, leave "model" EMPTY
   - NEVER hallucinate a model number — wrong model is worse than no model
   - It's FINE to leave model empty; the searchQuery with physical attributes will work

3. SEARCH QUERY STRATEGY (most important field):
   - ALWAYS build searchQuery from OBSERVED attributes, not guessed model numbers
   - Format: "[Brand] [Product Line] [Key Physical Attributes]"
   - Good: "Apple iPhone Pro Max Desert Titanium triple camera" (matches any generation)
   - Bad: "Apple iPhone 14 Pro Max Gold" (wrong generation = wrong product!)
   - Include: brand, product line/series, observed color, notable features
   - The searchQuery must work on Amazon/eBay to find the EXACT product in the image

4. CONFIDENCE SCORING:
   - 0.9+: Certain about brand AND exact model (e.g. visible model text, unique design you're sure about)
   - 0.7-0.9: Sure about brand and product line, but not the exact generation
   - 0.5-0.7: Reasonably sure about brand, general product type
   - <0.5: Mostly guessing

5. Return ONLY the JSON object, nothing else
        """.trimIndent()
    }

    /**
     * Identify product in image (coroutine version)
     */
    suspend fun identifyProduct(bitmap: Bitmap, callback: ProgressCallback? = null): ProductInfo? {
        return withContext(Dispatchers.IO) {
            identifyProductBlocking(bitmap, callback)
        }
    }

    /**
     * Identify product in image (blocking version for Java interop)
     * Safe to call from background thread. Do NOT call on main thread.
     */
    @JvmStatic
    fun identifyProductBlocking(bitmap: Bitmap, callback: ProgressCallback? = null): ProductInfo? {
        return try {
            // Check API key
            if (API_KEY.isNullOrEmpty()) {
                Log.e(TAG, "No OpenRouter API key configured")
                return null
            }

            // Step 1: Resize image to ≤384px (keeps both sides ≤384 → 258 tokens)
            val resized = resizeBitmap(bitmap, MAX_IMAGE_SIZE)
            val base64Image = bitmapToBase64(resized)
            if (resized !== bitmap) resized.recycle()

            Log.d(TAG, "Image encoded: ${base64Image.length} chars (resized to ≤${MAX_IMAGE_SIZE}px)")

            // Step 2: Tier 1 — cheap model first
            callback?.onProgress("AI analyzing product (fast scan)...")
            Log.d(TAG, "Tier 1: calling $DEFAULT_MODEL")
            var result = callLlm(base64Image, DEFAULT_MODEL, tier = 1)

            if (result != null) {
                Log.d(TAG, "Tier 1 result: brand='${result.brand}', model='${result.model}', " +
                        "confidence=${result.confidence}, query='${result.searchQuery}'")
            } else {
                Log.w(TAG, "Tier 1 returned null")
            }

            // Step 3: Auto-upgrade if Tier 1 result is uncertain
            if (result == null || shouldUpgrade(result)) {
                val reason = when {
                    result == null -> "Tier 1 failed"
                    result.confidence < CONFIDENCE_THRESHOLD -> "low confidence (${result.confidence})"
                    result.model.isBlank() -> "no model identified"
                    result.searchQuery.isBlank() -> "no search query"
                    else -> "unknown"
                }
                Log.d(TAG, "Upgrading to Tier 2 ($UPGRADE_MODEL): $reason")
                callback?.onProgress("Enhancing identification (deep scan)...")

                val upgraded = callLlm(base64Image, UPGRADE_MODEL, tier = 2)

                if (upgraded != null) {
                    Log.d(TAG, "Tier 2 result: brand='${upgraded.brand}', model='${upgraded.model}', " +
                            "confidence=${upgraded.confidence}, query='${upgraded.searchQuery}'")

                    // Use upgraded result if it's better
                    if (result == null || upgraded.confidence > result.confidence ||
                        (upgraded.model.isNotBlank() && result.model.isBlank())) {
                        result = upgraded
                        Log.d(TAG, "Using Tier 2 result (better)")
                    } else {
                        Log.d(TAG, "Keeping Tier 1 result (Tier 2 not better)")
                    }
                } else {
                    Log.w(TAG, "Tier 2 also returned null, keeping Tier 1 result")
                }
            }

            if (result != null) {
                Log.d(TAG, "Final result: brand='${result.brand}', model='${result.model}', " +
                        "query='${result.searchQuery}', confidence=${result.confidence}, tier=${result.tier}")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "LLM Vision identification error", e)
            null
        }
    }

    /**
     * Build search query from ProductInfo
     *
     * Strategy:
     * 1. If LLM provided a searchQuery, use it (already attribute-based per prompt design)
     * 2. If searchQuery is empty, construct from brand + attributes + category
     * 3. Last resort: YOLO hint
     *
     * NOTE: The prompt instructs the LLM to build searchQuery from OBSERVED attributes
     * (not guessed model numbers), so the searchQuery should already be safe from
     * knowledge-cutoff issues. e.g. "Apple iPhone Pro Max Desert Titanium triple camera"
     * instead of "Apple iPhone 14 Pro Max Gold".
     */
    @JvmStatic
    fun buildSearchQuery(productInfo: ProductInfo?, yoloHint: String? = null): String {
        if (productInfo != null && productInfo.searchQuery.isNotBlank()) {
            return productInfo.searchQuery.trim()
        }

        // Fallback: construct from brand + attributes + category
        if (productInfo != null) {
            val parts = mutableListOf<String>()
            if (productInfo.brand.isNotBlank()) parts.add(productInfo.brand)
            // Only include model if confidence is high enough (avoid wrong generation)
            if (productInfo.model.isNotBlank() && productInfo.confidence >= 0.85) {
                parts.add(productInfo.model)
            }
            // Add physical attributes for better matching
            for (attr in productInfo.keyAttributes.take(3)) {
                if (attr.isNotBlank() && parts.none { it.contains(attr, ignoreCase = true) }) {
                    parts.add(attr)
                }
            }
            if (productInfo.category.isNotBlank()) parts.add(productInfo.category)
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }

        // Last resort: YOLO hint
        if (!yoloHint.isNullOrBlank()) {
            return ShopHelper.mapDetectionToSearchQuery(yoloHint)
        }

        return ""
    }

    /**
     * Determine if Tier 1 result should be upgraded to Tier 2
     */
    private fun shouldUpgrade(result: ProductInfo): Boolean {
        return result.confidence < CONFIDENCE_THRESHOLD ||
                result.model.isBlank() ||
                result.searchQuery.isBlank()
    }

    /**
     * Call LLM via OpenRouter API
     */
    private fun callLlm(base64Image: String, model: String, tier: Int): ProductInfo? {
        try {
            val requestBody = buildRequestBody(base64Image, model)
            val response = callOpenRouter(requestBody)
            if (response == null) {
                Log.e(TAG, "OpenRouter returned null response for model $model")
                return null
            }
            return parseResponse(response, tier)
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed for model $model", e)
            return null
        }
    }

    // ==================== Image Processing ====================

    /**
     * Resize bitmap so both sides are ≤ maxSize pixels.
     * Preserves aspect ratio. Returns original if already small enough.
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = maxSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)

        Log.d(TAG, "Resizing image: ${width}x${height} → ${newWidth}x${newHeight}")
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Compress bitmap to JPEG and encode as base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ==================== OpenRouter API ====================

    /**
     * Build OpenRouter chat completions request body
     * Uses OpenAI-compatible format with vision (image_url)
     */
    private fun buildRequestBody(base64Image: String, model: String): String {
        val prompt = buildIdentificationPrompt()
        val request = JSONObject().apply {
            put("model", model)
            put("max_tokens", 500)
            put("temperature", 0.1)  // Low temperature for deterministic identification
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        // Text instruction (with current date injected)
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        // Image
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }
        return request.toString()
    }

    /**
     * Send HTTP POST to OpenRouter API
     */
    private fun callOpenRouter(requestBody: String): String? {
        try {
            val url = URL(OPENROUTER_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $API_KEY")
            connection.setRequestProperty("HTTP-Referer", "https://snapshop.app")
            connection.setRequestProperty("X-Title", "SnapShop")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
            } else {
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val errorBody = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                    Log.e(TAG, "OpenRouter error $responseCode: $errorBody")
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenRouter HTTP error", e)
            return null
        }
    }

    // ==================== Response Parsing ====================

    /**
     * Parse OpenRouter response → ProductInfo
     *
     * Expected response format (OpenAI-compatible):
     * { "choices": [{ "message": { "content": "{...json...}" } }] }
     */
    private fun parseResponse(responseJson: String, tier: Int): ProductInfo? {
        try {
            val json = JSONObject(responseJson)

            // Check for API error
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                Log.e(TAG, "OpenRouter API error: ${error.optString("message")}")
                return null
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.e(TAG, "No choices in response")
                return null
            }

            val message = choices.getJSONObject(0).getJSONObject("message")
            var content = message.getString("content").trim()

            // Strip markdown code fences if LLM wrapped the JSON
            content = stripCodeFences(content)

            // Parse the product JSON
            val productJson = JSONObject(content)

            val brand = productJson.optString("brand", "")
            val model = productJson.optString("model", "")
            val category = productJson.optString("category", "")
            val confidence = productJson.optDouble("confidence", 0.0)
            val notes = productJson.optString("notes", "")
            val searchQuery = productJson.optString("searchQuery", "")

            val keyAttributes = mutableListOf<String>()
            val attrs = productJson.optJSONArray("key_attributes")
            if (attrs != null) {
                for (i in 0 until attrs.length()) {
                    val attr = attrs.optString(i, "")
                    if (attr.isNotEmpty()) keyAttributes.add(attr)
                }
            }

            // Log token usage if available
            val usage = json.optJSONObject("usage")
            if (usage != null) {
                Log.d(TAG, "Token usage: prompt=${usage.optInt("prompt_tokens")}, " +
                        "completion=${usage.optInt("completion_tokens")}, " +
                        "total=${usage.optInt("total_tokens")}")
            }

            return ProductInfo(
                brand = brand,
                model = model,
                category = category,
                keyAttributes = keyAttributes,
                searchQuery = searchQuery,
                confidence = confidence,
                notes = notes,
                tier = tier
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response", e)
            // Log first 500 chars of response for debugging
            Log.e(TAG, "Response preview: ${responseJson.take(500)}")
            return null
        }
    }

    /**
     * Strip markdown code fences from LLM output
     * Handles: ```json{...}```, ```{...}```, etc.
     */
    private fun stripCodeFences(content: String): String {
        var cleaned = content.trim()

        // Remove leading ```json or ```
        if (cleaned.startsWith("```")) {
            val firstNewline = cleaned.indexOf('\n')
            if (firstNewline > 0) {
                cleaned = cleaned.substring(firstNewline + 1)
            } else {
                cleaned = cleaned.removePrefix("```json").removePrefix("```")
            }
        }

        // Remove trailing ```
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length - 3)
        }

        return cleaned.trim()
    }
}
