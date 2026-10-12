package dev.dreamz.droidterm.scripts

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.MessageCreateParams
import dev.dreamz.droidterm.data.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Describe it, get a script": turns a plain-language description into a shell script with an AI model,
 * using the user's own API key (stored encrypted with the Keystore). The result is only a draft the user reviews
 * and edits before saving; nothing is run automatically.
 */
object ScriptWriter {
    private const val PREFS = "ai"
    private const val KEY = "api_key_enc"
    private const val MODEL = "claude-opus-5-5"

    data class Draft(val name: String, val needsRoot: Boolean, val script: String, val notes: String)

    fun hasKey(context: Context) = prefs(context).contains(KEY)

    fun saveKey(context: Context, key: String) {
        if (key.isBlank()) prefs(context).edit().remove(KEY).apply()
        else prefs(context).edit().putString(KEY, SecretBox.encrypt(key.trim())).apply()
    }

    private fun loadKey(context: Context): String? = prefs(context).getString(KEY, null)?.let { runCatching { SecretBox.decrypt(it) }.getOrNull() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val SYSTEM = """
        You write shell scripts for DroidTerm, a terminal app on an Android phone. The script runs with /system/bin/sh
        (mksh) and Android's toybox utilities; bash, python, curl and apt are usually NOT available unless the user says so.
        adb and fastboot are on PATH. ${'$'}HOME is the app's private home directory; /sdcard is not writable without root.
        If the task needs root (mount, chroot, writing system partitions, other apps' data), the user's app runs the
        whole script through su, so don't call su inside it.
        Write a careful script: set -e where it helps, check for required tools and print a clear message when one is
        missing, quote variables, and ask for confirmation (read) before anything destructive like wiping or flashing.

        Reply in exactly this format:
        NAME: <short title, max 40 chars>
        ROOT: <yes or no>
        ```sh
        <the script, no shebang>
        ```
        NOTES: <one to three sentences on what it does and anything the user must do first>
    """.trimIndent()

    suspend fun write(context: Context, description: String, existing: String? = null): Result<Draft> = withContext(Dispatchers.IO) {
        val apiKey = loadKey(context) ?: return@withContext Result.failure(IllegalStateException("Add your Claude API key first"))
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        try {
            val ask = buildString {
                append("What the script needs to do:\n").append(description.trim())
                if (!existing.isNullOrBlank()) append("\n\nCurrent version to improve:\n```sh\n").append(existing).append("\n```")
            }
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                .system(SYSTEM)
                .addUserMessage(ask)
                // If a safety classifier declines, the server re-runs the request on its recommended fallback model.
                .addBeta("server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val msg = client.beta().messages().create(params)
            if (msg.stopReason().map { it.toString() }.orElse("") == "refusal") {
                return@withContext Result.failure(IllegalStateException("Claude declined to write this script. Try describing it differently."))
            }
            val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n")
            Result.success(parse(text))
        } catch (e: UnauthorizedException) {
            Result.failure(IllegalStateException("The API key was rejected. Check it in AI settings."))
        } catch (e: RateLimitException) {
            Result.failure(IllegalStateException("Rate limited by the API. Wait a moment and try again."))
        } catch (e: AnthropicServiceException) {
            Result.failure(IllegalStateException("API error ${e.statusCode()}: ${e.message}"))
        } catch (e: AnthropicIoException) {
            Result.failure(IllegalStateException("Network error: ${e.message}"))
        } finally {
            client.close()
        }
    }

    fun parse(text: String): Draft {
        val name = Regex("""(?m)^NAME:\s*(.+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val root = Regex("""(?m)^ROOT:\s*(\w+)""").find(text)?.groupValues?.get(1)?.lowercase() == "yes"
        val script = Regex("""```(?:sh|bash|shell)?\n([\s\S]*?)```""").find(text)?.groupValues?.get(1)?.trimEnd()
            ?: text.trim()
        val notes = Regex("""(?m)^NOTES:\s*([\s\S]+)$""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        return Draft(name.take(40), root, script.removePrefix("#!/system/bin/sh\n").removePrefix("#!/bin/sh\n"), notes)
    }
}
