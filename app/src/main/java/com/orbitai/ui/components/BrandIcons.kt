package com.orbitai.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.orbitai.R

/**
 * Brand icons for all supported AI providers.
 * Each icon is a vector drawable (ic_<name>.xml) in res/drawable/.
 */
object BrandIcons {
    val Claude: Painter @Composable get() = painterResource(R.drawable.ic_claude)
    val OpenAI: Painter @Composable get() = painterResource(R.drawable.ic_openai)
    val Gemini: Painter @Composable get() = painterResource(R.drawable.ic_gemini)
    val OpenRouter: Painter @Composable get() = painterResource(R.drawable.ic_openrouter)
    val DeepSeek: Painter @Composable get() = painterResource(R.drawable.ic_deepseek)
    val Groq: Painter @Composable get() = painterResource(R.drawable.ic_groq)
    val Ollama: Painter @Composable get() = painterResource(R.drawable.ic_ollama)

    // New provider icons
    val ZAI: Painter @Composable get() = painterResource(R.drawable.ic_zai)
    val XAI: Painter @Composable get() = painterResource(R.drawable.ic_xai)
    val Mistral: Painter @Composable get() = painterResource(R.drawable.ic_mistral)
    val Together: Painter @Composable get() = painterResource(R.drawable.ic_together)
    val GitHub: Painter @Composable get() = painterResource(R.drawable.ic_github)
    val NVIDIA: Painter @Composable get() = painterResource(R.drawable.ic_nvidia)
    val AWS: Painter @Composable get() = painterResource(R.drawable.ic_aws)
    val Azure: Painter @Composable get() = painterResource(R.drawable.ic_azure)
    val Vertex: Painter @Composable get() = painterResource(R.drawable.ic_vertex)
    val LMStudio: Painter @Composable get() = painterResource(R.drawable.ic_lmstudio)
    val DashScope: Painter @Composable get() = painterResource(R.drawable.ic_dashscope)
    val Moonshot: Painter @Composable get() = painterResource(R.drawable.ic_moonshot)
    val Venice: Painter @Composable get() = painterResource(R.drawable.ic_venice)
    val Fireworks: Painter @Composable get() = painterResource(R.drawable.ic_fireworks)
    val MiniMax: Painter @Composable get() = painterResource(R.drawable.ic_minimax)
    val NearAI: Painter @Composable get() = painterResource(R.drawable.ic_nearai)
    val Xiaomi: Painter @Composable get() = painterResource(R.drawable.ic_xiaomi)
    val Atlas: Painter @Composable get() = painterResource(R.drawable.ic_atlas)
    val Kimi: Painter @Composable get() = painterResource(R.drawable.ic_kimi)
    val OpenCodeGateway: Painter @Composable get() = painterResource(R.drawable.ic_opencode_gateway)
    val OpenGateway: Painter @Composable get() = painterResource(R.drawable.ic_opengateway)
    val Custom: Painter @Composable get() = painterResource(R.drawable.ic_custom)
}
