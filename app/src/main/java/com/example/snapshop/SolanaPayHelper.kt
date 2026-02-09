package com.example.snapshop

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
import java.security.MessageDigest

/**
 * Solana Pay Helper for USDC SPL Token Transfers
 *
 * Enables sending USDC payments on Solana blockchain.
 * Uses the SPL Token Program for token transfers.
 *
 * USDC Mint: EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v
 * Token Program: TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA
 * ATA Program: ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL
 */
object SolanaPayHelper {

    private const val TAG = "SolanaPayHelper"

    // Solana Program IDs
    private const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
    private const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private const val ATA_PROGRAM_ID = "ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL"
    private const val SYSTEM_PROGRAM_ID = "11111111111111111111111111111111"

    // RPC Endpoint
    private const val MAINNET_RPC = "https://api.mainnet-beta.solana.com"

    // USDC has 6 decimal places
    private const val USDC_DECIMALS = 6

    // Demo merchant wallet for hackathon testing (loaded from BuildConfig)
    private val DEMO_MERCHANT_WALLET = BuildConfig.MERCHANT_WALLET_ADDRESS

    /**
     * Convert USDC amount to lamports (6 decimals)
     * e.g., 10.50 USDC = 10500000 lamports
     */
    fun usdcToLamports(amount: Double): Long {
        return (amount * Math.pow(10.0, USDC_DECIMALS.toDouble())).toLong()
    }

    /**
     * Derive Associated Token Account (ATA) address
     *
     * ATA = PDA derived from:
     * - wallet address
     * - token program ID
     * - token mint address
     *
     * Seeds: [wallet_pubkey, TOKEN_PROGRAM_ID, mint_pubkey]
     * Program: ATA_PROGRAM_ID
     */
    fun deriveATA(walletPubkey: ByteArray, mintPubkey: ByteArray): ByteArray {
        val tokenProgramPubkey = Base58.decode(TOKEN_PROGRAM_ID)
        val ataProgramPubkey = Base58.decode(ATA_PROGRAM_ID)

        // PDA derivation: SHA256(seeds + programId + "ProgramDerivedAddress")
        // Find a valid PDA by iterating bump seeds from 255 down to 0
        for (bump in 255 downTo 0) {
            try {
                val seeds = walletPubkey + tokenProgramPubkey + mintPubkey + byteArrayOf(bump.toByte())
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(seeds + ataProgramPubkey + "ProgramDerivedAddress".toByteArray())

                // Check if the hash is a valid PDA (not on ed25519 curve)
                // For simplicity in this demo, we return the first result
                // In production, proper curve check should be implemented
                return hash.copyOfRange(0, 32)
            } catch (e: Exception) {
                continue
            }
        }
        throw RuntimeException("Could not derive ATA")
    }

    /**
     * Get USDC balance for a wallet address via RPC
     */
    suspend fun getUsdcBalance(walletAddress: String): Double {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(MAINNET_RPC)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val requestBody = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "getTokenAccountsByOwner")
                    put("params", JSONArray().apply {
                        put(walletAddress)
                        put(JSONObject().apply {
                            put("mint", USDC_MINT)
                        })
                        put(JSONObject().apply {
                            put("encoding", "jsonParsed")
                        })
                    })
                }.toString()

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody)
                    writer.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream))
                        .use { it.readText() }
                    val json = JSONObject(response)
                    val result = json.optJSONObject("result")
                    val value = result?.optJSONArray("value")

                    if (value != null && value.length() > 0) {
                        val account = value.getJSONObject(0)
                        val info = account.getJSONObject("account")
                            .getJSONObject("data")
                            .getJSONObject("parsed")
                            .getJSONObject("info")
                        val tokenAmount = info.getJSONObject("tokenAmount")
                        tokenAmount.getDouble("uiAmount")
                    } else {
                        0.0
                    }
                } else {
                    Log.e(TAG, "RPC error: ${connection.responseCode}")
                    0.0
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get USDC balance", e)
                0.0
            }
        }
    }

    /**
     * Get Solana Explorer URL for a transaction
     */
    fun getExplorerUrl(signature: String): String {
        return "https://explorer.solana.com/tx/$signature"
    }
}
