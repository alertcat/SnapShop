package com.example.snapshop.model

/**
 * Product data model for shopping results
 */
data class Product(
    val title: String,
    val description: String = "",
    val imageUrl: String = "",
    val productUrl: String = "",
    val price: String = "",
    val source: String = "Amazon",  // "Amazon", "eBay", "AliExpress"
    val confidence: Float = 0f       // Vision API match confidence
)
