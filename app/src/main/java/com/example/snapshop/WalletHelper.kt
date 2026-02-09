package com.example.snapshop

import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.funkatronics.encoders.Base58
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.AccountMeta
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import com.solana.transaction.TransactionInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Solana Wallet Helper for SnapShop
 *
 * Core Features:
 * 1. Wallet connection via Mobile Wallet Adapter (MWA)
 * 2. Memo On-Chain - Upload detection results to Solana blockchain
 * 3. USDC balance query for shopping functionality
 * 4. Privacy Protection - Image data processed locally only
 *
 * Programs:
 * - Memo Program: MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr
 * - USDC Mint: EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v
 */
class WalletHelper(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "WalletHelper"

        // Solana Memo Program ID
        private const val MEMO_PROGRAM_ID = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"

        // RPC Endpoint (Mainnet)
        private const val MAINNET_RPC = "https://api.mainnet-beta.solana.com"

        // Solana Explorer URL
        private const val EXPLORER_URL = "https://explorer.solana.com/tx/%s"

        // USDC Mint on Solana
        private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    }

    interface WalletCallback {
        fun onConnected(address: String)
        fun onDisconnected()
        fun onError(message: String)
        fun onNoWalletFound()
    }

    interface MemoCallback {
        fun onMemoStarted()
        fun onMemoProgress(status: String)
        fun onMemoSuccess(signature: String, explorerUrl: String, memoData: String)
        fun onMemoError(message: String)
    }

    private val activityResultSender = ActivityResultSender(activity)

    private val walletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://snapshop.app"),
            iconUri = Uri.parse("favicon.ico"),
            identityName = "SnapShop"
        )
    )

    private var connectedPublicKey: ByteArray? = null
    var connectedAddress: String? = null
        private set

    val isConnected: Boolean
        get() = connectedAddress != null && connectedPublicKey != null

    /**
     * Get explorer URL for transaction
     */
    private fun getExplorerUrl(signature: String): String {
        return String.format(EXPLORER_URL, signature)
    }

    /**
     * Connect to wallet using MWA
     */
    fun connect(callback: WalletCallback) {
        activity.lifecycleScope.launch {
            try {
                val result = walletAdapter.transact(activityResultSender) { authResult ->
                    val accounts = authResult.accounts
                    if (accounts.isNotEmpty()) {
                        connectedPublicKey = accounts.first().publicKey
                        connectedAddress = Base58.encodeToString(connectedPublicKey!!)
                    }
                    Unit
                }

                when (result) {
                    is TransactionResult.Success -> {
                        if (connectedAddress != null) {
                            Log.d(TAG, "Wallet connected: $connectedAddress")
                            callback.onConnected(connectedAddress!!)
                        } else {
                            callback.onError("No accounts found")
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        Log.w(TAG, "No wallet found")
                        callback.onNoWalletFound()
                    }
                    is TransactionResult.Failure -> {
                        Log.e(TAG, "Connection failed: ${result.message}")
                        callback.onError(result.message ?: "Unknown error")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                callback.onError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Disconnect from wallet
     */
    fun disconnect(callback: WalletCallback) {
        connectedAddress = null
        connectedPublicKey = null
        callback.onDisconnected()
    }

    /**
     * Get shortened wallet address for display
     */
    fun getShortAddress(): String {
        val addr = connectedAddress ?: return "Not Connected"
        return if (addr.length > 12) {
            "${addr.substring(0, 6)}...${addr.substring(addr.length - 4)}"
        } else {
            addr
        }
    }

    /**
     * Detection data structure
     */
    data class DetectionData(
        val label: String,      // class name
        val confidence: Float,  // confidence score
        val x: Float,           // top-left x
        val y: Float,           // top-left y
        val width: Float,       // width
        val height: Float       // height
    )

    /**
     * Send detection results on-chain (Memo Program transaction)
     *
     * Flow:
     * 1. Build Memo JSON data
     * 2. Get latest blockhash from RPC
     * 3. Build Memo transaction instruction
     * 4. Use MWA signAndSendTransactions to sign and send
     * 5. Return transaction signature and Explorer URL
     *
     * @param detections List of detection results
     * @param callback Callback for progress and result
     */
    fun sendDetectionMemo(detections: List<DetectionData>, callback: MemoCallback) {
        if (!isConnected || connectedPublicKey == null) {
            callback.onMemoError("Wallet not connected")
            return
        }

        if (detections.isEmpty()) {
            callback.onMemoError("No detections to send")
            return
        }

        activity.lifecycleScope.launch {
            try {
                callback.onMemoStarted()
                callback.onMemoProgress("Building memo...")

                // Build compact JSON format
                var memoJson = buildMemoJson(detections)
                Log.d(TAG, "Memo data: $memoJson (${memoJson.toByteArray().size} bytes)")

                // Check memo length (Solana memo limit ~566 bytes)
                if (memoJson.toByteArray(Charsets.UTF_8).size > 500) {
                    val truncatedDetections = detections.take(3)
                    memoJson = buildMemoJson(truncatedDetections)
                    Log.w(TAG, "Memo truncated to first 3 detections")
                }

                callback.onMemoProgress("Getting blockhash...")

                // Get latest blockhash
                val blockhash = getLatestBlockhash()
                if (blockhash == null) {
                    callback.onMemoError("Failed to get blockhash from RPC")
                    return@launch
                }
                Log.d(TAG, "Got blockhash: $blockhash")

                callback.onMemoProgress("Building tx...")

                // Build Memo transaction
                val userPubkey = SolanaPublicKey(connectedPublicKey!!)
                val memoTransaction = buildMemoTransaction(userPubkey, memoJson, blockhash)

                callback.onMemoProgress("Please sign...")

                // Use MWA to sign and send transaction
                val result = walletAdapter.transact(activityResultSender) { _ ->
                    // signAndSendTransactions signs and sends transaction to blockchain
                    signAndSendTransactions(arrayOf(memoTransaction.serialize()))
                }

                when (result) {
                    is TransactionResult.Success -> {
                        val signatures = result.successPayload?.signatures
                        if (!signatures.isNullOrEmpty()) {
                            val signatureBytes = signatures.first()
                            val signatureStr = Base58.encodeToString(signatureBytes)
                            val explorerUrl = getExplorerUrl(signatureStr)

                            Log.d(TAG, "Memo transaction sent!")
                            Log.d(TAG, "Signature: $signatureStr")
                            Log.d(TAG, "Explorer: $explorerUrl")

                            // Save transaction record locally
                            saveTransactionRecord(signatureStr, memoJson)

                            callback.onMemoSuccess(signatureStr, explorerUrl, memoJson)
                        } else {
                            callback.onMemoError("Transaction sent but no signature returned")
                        }
                    }
                    is TransactionResult.NoWalletFound -> {
                        callback.onMemoError("No wallet found")
                    }
                    is TransactionResult.Failure -> {
                        Log.e(TAG, "Transaction failed: ${result.message}")
                        callback.onMemoError(result.message ?: "Transaction failed")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Memo transaction error", e)
                callback.onMemoError(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Build Memo transaction
     *
     * Memo Program: MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr
     * Instruction format: program ID + signer account list + message bytes
     */
    private fun buildMemoTransaction(
        signer: SolanaPublicKey,
        memoData: String,
        blockhash: String
    ): Transaction {
        val memoProgramId = SolanaPublicKey.from(MEMO_PROGRAM_ID)

        // Memo instruction: signer account + message data
        val memoInstruction = TransactionInstruction(
            memoProgramId,
            listOf(AccountMeta(signer, true, true)),  // signer, writable
            memoData.toByteArray(Charsets.UTF_8)
        )

        // Build transaction message
        // Note: Fee payer is implicitly the first signer (AccountMeta with isSigner=true)
        val message = Message.Builder()
            .addInstruction(memoInstruction)
            .setRecentBlockhash(blockhash)
            .build()

        return Transaction(message)
    }

    /**
     * Get latest blockhash from RPC
     */
    private suspend fun getLatestBlockhash(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(MAINNET_RPC)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                // RPC request: getLatestBlockhash
                val requestBody = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "getLatestBlockhash")
                    put("params", JSONArray().apply {
                        put(JSONObject().apply {
                            put("commitment", "finalized")
                        })
                    })
                }.toString()

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        reader.readText()
                    }

                    val jsonResponse = JSONObject(response)
                    val result = jsonResponse.optJSONObject("result")
                    val value = result?.optJSONObject("value")
                    value?.optString("blockhash")
                } else {
                    Log.e(TAG, "RPC error: $responseCode")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get blockhash", e)
                null
            }
        }
    }

    /**
     * Build compact Memo JSON
     * Format: {"t":"yolo26","v":1,"n":3,"d":[{"c":"person","p":0.95,"b":[10,20,100,200]},...]}
     *
     * Field description:
     * - t: type (fixed as "yolo26")
     * - v: version (protocol version)
     * - n: count (number of detections)
     * - d: detections array
     *   - c: class (class name)
     *   - p: probability (confidence)
     *   - b: bbox [x, y, w, h]
     */
    private fun buildMemoJson(detections: List<DetectionData>): String {
        val json = JSONObject().apply {
            put("t", "yolo26")
            put("v", 1)
            put("n", detections.size)

            val detectionsArray = JSONArray()
            for (det in detections) {
                val detObj = JSONObject().apply {
                    put("c", det.label)
                    put("p", String.format("%.2f", det.confidence).toDouble())
                    put("b", JSONArray().apply {
                        put(det.x.toInt())
                        put(det.y.toInt())
                        put(det.width.toInt())
                        put(det.height.toInt())
                    })
                }
                detectionsArray.put(detObj)
            }
            put("d", detectionsArray)
        }

        return json.toString()
    }

    /**
     * Get USDC balance for the connected wallet
     * Returns balance in USDC (e.g., 10.50)
     */
    suspend fun getUsdcBalance(): Double {
        val address = connectedAddress ?: return 0.0
        return SolanaPayHelper.getUsdcBalance(address)
    }

    /**
     * Save transaction record to local file
     */
    private fun saveTransactionRecord(signature: String, memoData: String) {
        try {
            val timestamp = System.currentTimeMillis()
            val filename = "snapshop_tx_${timestamp}.json"
            val file = java.io.File(activity.getExternalFilesDir(null), filename)

            val json = JSONObject().apply {
                put("timestamp", timestamp)
                put("network", "mainnet")
                put("signature", signature)
                put("explorerUrl", getExplorerUrl(signature))
                put("signer", connectedAddress)
                put("memoData", memoData)
            }

            file.writeText(json.toString(2))
            Log.d(TAG, "Transaction record saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save transaction record", e)
        }
    }
}
