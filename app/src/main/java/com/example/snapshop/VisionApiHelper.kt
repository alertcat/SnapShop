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

/**
 * Google Cloud Vision API Helper
 *
 * Uses Web Detection + Label Detection + Logo Detection
 * to identify objects and find similar products.
 *
 * Free tier: 1000 requests/month
 * Cost after: $1.50 per 1000 requests
 *
 * Setup: Create GCP project -> Enable Vision API -> Get API key
 */
object VisionApiHelper {

    private const val TAG = "VisionApiHelper"

    // API key loaded from BuildConfig (set in local.properties)
    private val API_KEY = BuildConfig.GOOGLE_VISION_API_KEY

    private val VISION_API_URL: String
        get() = "https://vision.googleapis.com/v1/images:annotate?key=$API_KEY"

    /**
     * Vision API response data
     */
    data class VisionResult(
        val bestGuessLabel: String?,          // Best guess of what the image contains
        val webEntities: List<WebEntity>,     // Web entities with scores
        val similarImageUrls: List<String>,   // Visually similar image URLs
        val detectedLogos: List<String>,      // Detected brand logos
        val labels: List<String>,             // General labels
        val shoppingUrls: List<String>        // Pages with matching images (potential shopping links)
    )

    data class WebEntity(
        val description: String,
        val score: Float
    )

    /**
     * Analyze an image using Google Cloud Vision API
     *
     * Combines three detection features:
     * 1. WEB_DETECTION: find similar images and web entities
     * 2. LABEL_DETECTION: classify the image content
     * 3. LOGO_DETECTION: identify brand logos
     *
     * @param bitmap The image to analyze (will be compressed and base64 encoded)
     * @return VisionResult with all detection results, or null if failed
     */
    suspend fun analyzeImage(bitmap: Bitmap): VisionResult? {
        return withContext(Dispatchers.IO) {
            analyzeImageBlocking(bitmap)
        }
    }

    /**
     * Synchronous (blocking) version — safe to call from a background Thread in Java.
     * Do NOT call on the main thread.
     */
    @JvmStatic
    fun analyzeImageBlocking(bitmap: Bitmap): VisionResult? {
        return try {
            val base64Image = bitmapToBase64(bitmap)
            Log.d(TAG, "Image encoded: ${base64Image.length} chars")

            val requestBody = buildRequestBody(base64Image)

            val response = callVisionApi(requestBody)
            if (response == null) {
                Log.e(TAG, "Vision API returned null response")
                return null
            }

            parseResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "Vision API error", e)
            null
        }
    }

    /**
     * Build optimized search query from Vision API results
     *
     * Multi-signal fusion strategy:
     * 1. Detected logo (brand) takes highest priority
     * 2. Best guess label provides context
     * 3. Web entities add specificity
     */
    fun buildSearchQueryFromResult(
        visionResult: VisionResult,
        yoloClass: String? = null
    ): String {
        val parts = mutableListOf<String>()

        // Brand from logo detection
        if (visionResult.detectedLogos.isNotEmpty()) {
            parts.add(visionResult.detectedLogos.first())
        }

        // Best guess label
        if (!visionResult.bestGuessLabel.isNullOrEmpty()) {
            parts.add(visionResult.bestGuessLabel)
        } else if (yoloClass != null) {
            // Fallback to YOLO class
            parts.add(ShopHelper.mapDetectionToSearchQuery(yoloClass))
        }

        // Add top web entity if different from above
        val topEntity = visionResult.webEntities.firstOrNull()?.description
        if (topEntity != null && !parts.any { it.equals(topEntity, ignoreCase = true) }) {
            parts.add(topEntity)
        }

        return parts.joinToString(" ").trim()
    }

    /**
     * Compress bitmap to JPEG and encode as base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        // Compress to JPEG at 80% quality to reduce API call size
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Build Vision API request JSON body
     */
    private fun buildRequestBody(base64Image: String): String {
        val request = JSONObject().apply {
            put("requests", JSONArray().apply {
                put(JSONObject().apply {
                    // Image data
                    put("image", JSONObject().apply {
                        put("content", base64Image)
                    })
                    // Features to detect
                    put("features", JSONArray().apply {
                        // Web Detection - find similar images and web entities
                        put(JSONObject().apply {
                            put("type", "WEB_DETECTION")
                            put("maxResults", 10)
                        })
                        // Label Detection - classify image content
                        put(JSONObject().apply {
                            put("type", "LABEL_DETECTION")
                            put("maxResults", 5)
                        })
                        // Logo Detection - identify brand logos
                        put(JSONObject().apply {
                            put("type", "LOGO_DETECTION")
                            put("maxResults", 3)
                        })
                    })
                })
            })
        }
        return request.toString()
    }

    /**
     * Call Google Cloud Vision API via HTTP POST
     */
    private fun callVisionApi(requestBody: String): String? {
        try {
            val url = URL(VISION_API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

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
                    Log.e(TAG, "Vision API error $responseCode: $errorBody")
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vision API HTTP error", e)
            return null
        }
    }

    /**
     * Parse Vision API JSON response
     */
    private fun parseResponse(responseJson: String): VisionResult? {
        try {
            val json = JSONObject(responseJson)
            val responses = json.getJSONArray("responses")
            if (responses.length() == 0) return null

            val result = responses.getJSONObject(0)

            // Parse Web Detection
            var bestGuessLabel: String? = null
            val webEntities = mutableListOf<WebEntity>()
            val similarImageUrls = mutableListOf<String>()
            val shoppingUrls = mutableListOf<String>()

            val webDetection = result.optJSONObject("webDetection")
            if (webDetection != null) {
                // Best guess labels
                val bestGuessLabels = webDetection.optJSONArray("bestGuessLabels")
                if (bestGuessLabels != null && bestGuessLabels.length() > 0) {
                    bestGuessLabel = bestGuessLabels.getJSONObject(0).optString("label")
                }

                // Web entities
                val entities = webDetection.optJSONArray("webEntities")
                if (entities != null) {
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val desc = entity.optString("description", "")
                        val score = entity.optDouble("score", 0.0).toFloat()
                        if (desc.isNotEmpty()) {
                            webEntities.add(WebEntity(desc, score))
                        }
                    }
                }

                // Visually similar images
                val similarImages = webDetection.optJSONArray("visuallySimilarImages")
                if (similarImages != null) {
                    for (i in 0 until minOf(similarImages.length(), 5)) {
                        val imageUrl = similarImages.getJSONObject(i).optString("url", "")
                        if (imageUrl.isNotEmpty()) {
                            similarImageUrls.add(imageUrl)
                        }
                    }
                }

                // Pages with matching images (potential shopping pages)
                val pagesWithMatchingImages = webDetection.optJSONArray("pagesWithMatchingImages")
                if (pagesWithMatchingImages != null) {
                    for (i in 0 until minOf(pagesWithMatchingImages.length(), 5)) {
                        val pageUrl = pagesWithMatchingImages.getJSONObject(i).optString("url", "")
                        if (pageUrl.isNotEmpty()) {
                            shoppingUrls.add(pageUrl)
                        }
                    }
                }
            }

            // Parse Label Detection
            val labels = mutableListOf<String>()
            val labelAnnotations = result.optJSONArray("labelAnnotations")
            if (labelAnnotations != null) {
                for (i in 0 until labelAnnotations.length()) {
                    val desc = labelAnnotations.getJSONObject(i).optString("description", "")
                    if (desc.isNotEmpty()) {
                        labels.add(desc)
                    }
                }
            }

            // Parse Logo Detection
            val detectedLogos = mutableListOf<String>()
            val logoAnnotations = result.optJSONArray("logoAnnotations")
            if (logoAnnotations != null) {
                for (i in 0 until logoAnnotations.length()) {
                    val desc = logoAnnotations.getJSONObject(i).optString("description", "")
                    if (desc.isNotEmpty()) {
                        detectedLogos.add(desc)
                    }
                }
            }

            Log.d(TAG, "Vision result: bestGuess=$bestGuessLabel, " +
                    "entities=${webEntities.size}, similar=${similarImageUrls.size}, " +
                    "logos=${detectedLogos.size}, labels=${labels.size}")

            return VisionResult(
                bestGuessLabel = bestGuessLabel,
                webEntities = webEntities,
                similarImageUrls = similarImageUrls,
                detectedLogos = detectedLogos,
                labels = labels,
                shoppingUrls = shoppingUrls
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Vision API response", e)
            return null
        }
    }
}
