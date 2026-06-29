package com.yuketang.helper

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import okhttp3.*

object AiApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Send screenshot to AI API and get answer text.
     * Supports any OpenAI-compatible API (OpenAI, Azure, local LLM, etc.)
     */
    fun analyzeImage(
        apiUrl: String,
        apiKey: String,
        model: String,
        imageBitmap: Bitmap,
        customPrompt: String
    ): String {
        try {
            // Convert bitmap to base64
            val base64Image = bitmapToBase64(imageBitmap)

            // Build the system prompt
            val systemPrompt = if (customPrompt.isNotEmpty()) {
                customPrompt
            } else {
                "你是一个答题助手。我会给你一张题目截图，请你：\n" +
                "1. 识别截图中的题目\n" +
                "2. 给出正确答案\n" +
                "3. 如果是选择题，标出正确选项字母\n" +
                "4. 如果是判断题，判断对错并说明理由\n" +
                "5. 如果是填空题，给出每个空的答案\n" +
                "请用中文回答，答案要简洁准确。"
            }

            // Build request body JSON
            val jsonBody = buildJsonString(systemPrompt, base64Image, model)

            val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return "API 请求失败 ($response.code):\n${responseBody.take(200)}"
            }

            // Parse response
            return parseResponse(responseBody)

        } catch (e: java.net.SocketTimeoutException) {
            return "请求超时，请检查网络或API地址"
        } catch (e: Exception) {
            e.printStackTrace()
            return "错误: ${e.message}"
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val bytes = stream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun buildJsonString(systemPrompt: String, base64Image: String, model: String): String {
        // Build JSON manually to avoid Gson dependency
        val content = """
        {
            "model": "$model",
            "messages": [
                {
                    "role": "system",
                    "content": ${escapedJson(systemPrompt)}
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": "请帮我解答这道题"
                        },
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": "data:image/jpeg;base64,$base64Image"
                            }
                        }
                    ]
                }
            ],
            "max_tokens": 1024
        }
        """.trimIndent()
        return content
    }

    private fun escapedJson(text: String): String {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\r", "\\r") + "\""
    }

    private fun parseResponse(responseBody: String): String {
        try {
            val regex = """"content"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
            val match = regex.find(responseBody)
            if (match != null) {
                var content = match.groupValues[1]
                // Unescape
                content = content
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                return content
            }
            return "无法解析AI返回内容"
        } catch (e: Exception) {
            return "解析失败: ${e.message}"
        }
    }
}
