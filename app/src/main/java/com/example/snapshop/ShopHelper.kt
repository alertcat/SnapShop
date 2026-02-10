package com.example.snapshop

import java.net.URLEncoder

/**
 * Shopping platform integration helper
 *
 * Provides URL builders for multiple shopping platforms
 * and YOLO class name to search query mapping.
 */
object ShopHelper {

    // Affiliate tags (loaded from BuildConfig, set in local.properties)
    private val AMAZON_TAG = BuildConfig.AMAZON_AFFILIATE_TAG
    private val EBAY_CAMPID = BuildConfig.EBAY_PARTNER_CAMPID

    /**
     * Build Amazon search URL with affiliate tag
     */
    fun buildAmazonSearchUrl(keyword: String): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return "https://www.amazon.com/s?k=$encoded&tag=$AMAZON_TAG"
    }

    /**
     * Build eBay search URL with Partner Network affiliate tracking
     *
     * EPN parameters:
     * - mkcid=1: affiliate channel
     * - mkrid=711-53200-19255-0: eBay US marketplace rotation ID
     * - campid: your EPN campaign ID
     * - toolid=10001: standard tool ID
     */
    fun buildEbaySearchUrl(keyword: String): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val baseUrl = "https://www.ebay.com/sch/i.html?_nkw=$encoded"
        return if (EBAY_CAMPID.isNotEmpty()) {
            "$baseUrl&mkcid=1&mkrid=711-53200-19255-0&campid=$EBAY_CAMPID&toolid=10001"
        } else {
            baseUrl
        }
    }

    /**
     * Build AliExpress search URL
     */
    fun buildAliExpressSearchUrl(keyword: String): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return "https://www.aliexpress.com/wholesale?SearchText=$encoded"
    }

    /**
     * Build Bitrefill Amazon gift card URL (for USDC purchase)
     */
    fun buildBitrefillAmazonUrl(): String {
        return "https://www.bitrefill.com/buy/amazon_com-usa/?hl=en"
    }

    /**
     * Build Bitrefill embed widget URL (for in-app WebView)
     * Uses the embeddable widget with payment info display
     */
    fun buildBitrefillEmbedUrl(): String {
        return "https://embed.bitrefill.com/?showPaymentInfo=true"
    }

    /**
     * Multi-signal query builder: combines YOLO class + AI labels + logo
     */
    fun buildSearchQuery(
        yoloClass: String,
        visionLabels: List<String>? = null,
        detectedLogo: String? = null
    ): String {
        val base = mapDetectionToSearchQuery(yoloClass)
        val brand = detectedLogo ?: ""
        val detail = visionLabels?.firstOrNull() ?: ""
        return "$brand $base $detail".trim()
    }

    /**
     * Map YOLO COCO class names to better shopping search terms
     */
    fun mapDetectionToSearchQuery(yoloClass: String): String {
        return searchTermMap[yoloClass] ?: yoloClass
    }

    /**
     * Get all available shopping platform URLs for a keyword
     */
    fun getShoppingUrls(keyword: String): Map<String, String> {
        return mapOf(
            "Amazon" to buildAmazonSearchUrl(keyword),
            "eBay" to buildEbaySearchUrl(keyword),
            "AliExpress" to buildAliExpressSearchUrl(keyword)
        )
    }

    // YOLO COCO 80 class names -> better Amazon search terms
    private val searchTermMap = mapOf(
        "person" to "fashion clothing",
        "bicycle" to "bicycle",
        "car" to "car accessories",
        "motorcycle" to "motorcycle accessories",
        "airplane" to "airplane model",
        "bus" to "bus model toy",
        "train" to "train model",
        "truck" to "truck accessories",
        "boat" to "boat accessories",
        "traffic light" to "traffic light decor",
        "fire hydrant" to "fire hydrant decor",
        "stop sign" to "stop sign decor",
        "parking meter" to "parking meter",
        "bench" to "outdoor bench",
        "bird" to "bird feeder",
        "cat" to "cat supplies",
        "dog" to "dog supplies",
        "horse" to "horse supplies",
        "sheep" to "sheep plush",
        "cow" to "cow plush",
        "elephant" to "elephant plush toy",
        "bear" to "bear plush toy",
        "zebra" to "zebra plush toy",
        "giraffe" to "giraffe plush toy",
        "backpack" to "backpack",
        "umbrella" to "umbrella",
        "handbag" to "handbag",
        "tie" to "necktie",
        "suitcase" to "suitcase luggage",
        "frisbee" to "frisbee",
        "skis" to "skis",
        "snowboard" to "snowboard",
        "sports ball" to "sports ball",
        "kite" to "kite",
        "baseball bat" to "baseball bat",
        "baseball glove" to "baseball glove",
        "skateboard" to "skateboard",
        "surfboard" to "surfboard",
        "tennis racket" to "tennis racket",
        "bottle" to "water bottle",
        "wine glass" to "wine glass set",
        "cup" to "coffee mug",
        "fork" to "fork set silverware",
        "knife" to "kitchen knife set",
        "spoon" to "spoon set",
        "bowl" to "bowl set",
        "banana" to "banana snack",
        "apple" to "apple fruit",
        "sandwich" to "sandwich maker",
        "orange" to "orange fruit",
        "broccoli" to "broccoli seeds",
        "carrot" to "carrot seeds",
        "hot dog" to "hot dog maker",
        "pizza" to "pizza oven",
        "donut" to "donut maker",
        "cake" to "cake baking supplies",
        "chair" to "office chair",
        "couch" to "sofa couch",
        "potted plant" to "indoor plant pot",
        "bed" to "bed frame mattress",
        "dining table" to "dining table",
        "toilet" to "toilet seat",
        "tv" to "smart TV",
        "laptop" to "laptop computer",
        "mouse" to "wireless mouse",
        "remote" to "universal remote",
        "keyboard" to "mechanical keyboard",
        "cell phone" to "smartphone",
        "microwave" to "microwave oven",
        "oven" to "oven",
        "toaster" to "toaster",
        "sink" to "kitchen sink",
        "refrigerator" to "refrigerator",
        "book" to "bestselling books",
        "clock" to "wall clock",
        "vase" to "flower vase",
        "scissors" to "scissors",
        "teddy bear" to "teddy bear plush",
        "hair drier" to "hair dryer",
        "toothbrush" to "electric toothbrush"
    )
}
