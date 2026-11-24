package com.sjos.folionote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.sjos.folionote.ui.theme.AccentBlue
import com.sjos.folionote.ui.theme.AccentCyan
import com.sjos.folionote.ui.theme.JetBrainsMonoFontFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FolioLogoMark(modifier: Modifier = Modifier.size(28.dp)) {
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val brush = Brush.linearGradient(
            colors = listOf(primary, AccentBlue, AccentCyan),
            start = Offset(0f, h),
            end = Offset(w, 0f)
        )
        val strokeWidth = w * 0.12f

        val outer = Path().apply {
            moveTo(w * 0.14f, h * 0.84f)
            cubicTo(w * 0.14f, h * 0.14f, w * 0.86f, h * 0.14f, w * 0.86f, h * 0.84f)
        }
        drawPath(outer, brush = brush, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))

        val mid = Path().apply {
            moveTo(w * 0.31f, h * 0.84f)
            cubicTo(w * 0.31f, h * 0.36f, w * 0.69f, h * 0.36f, w * 0.69f, h * 0.84f)
        }
        drawPath(mid, brush = brush, style = Stroke(width = strokeWidth * 0.9f, cap = StrokeCap.Round))

        drawCircle(
            color = AccentCyan,
            radius = strokeWidth * 0.55f,
            center = Offset(w * 0.50f, h * 0.74f)
        )
    }
}

@Composable
fun CollectionGeometricIcon(
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val s = w * 0.085f
        val tri = Path().apply {
            moveTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.72f, h * 0.44f)
            lineTo(w * 0.28f, h * 0.44f)
            close()
        }
        drawPath(tri, color = tint, style = Stroke(width = s, cap = StrokeCap.Round))
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.16f, h * 0.56f),
            size = androidx.compose.ui.geometry.Size(w * 0.28f, h * 0.28f),
            style = Stroke(width = s)
        )
        drawCircle(
            color = tint,
            radius = w * 0.15f,
            center = Offset(w * 0.70f, h * 0.70f),
            style = Stroke(width = s)
        )
    }
}

@Composable
fun MarkdownLiteText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val lines = remember(text) { text.lines() }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        var inCodeBlock = false
        val codeBuffer = StringBuilder()

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    codeBuffer.clear()
                } else {
                    inCodeBlock = false
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = codeBuffer.toString().trimEnd(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                continue
            }

            if (inCodeBlock) {
                codeBuffer.appendLine(rawLine)
                continue
            }

            when {
                trimmed.isEmpty() -> Spacer(modifier = Modifier.size(2.dp))
                trimmed.startsWith("### ") -> {
                    Text(
                        text = parseInlineFormatting(trimmed.removePrefix("### "), textColor),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = parseInlineFormatting(trimmed.removePrefix("## "), textColor),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = parseInlineFormatting(trimmed.removePrefix("# "), textColor),
                        style = MaterialTheme.typography.titleLarge,
                        color = textColor
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 6.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = parseInlineFormatting(trimmed.drop(2), textColor),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor
                        )
                    }
                }
                else -> {
                    Text(
                        text = parseInlineFormatting(rawLine, textColor),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
            }
        }

        if (inCodeBlock && codeBuffer.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = codeBuffer.toString().trimEnd(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

private fun parseInlineFormatting(input: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < input.length) {
            if (i + 1 < input.length && input[i] == '*' && input[i + 1] == '*') {
                val end = input.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = baseColor)) {
                        append(input.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            if (input[i] == '`') {
                val end = input.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Medium,
                            color = baseColor
                        )
                    ) {
                        append(input.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            append(input[i])
            i++
        }
    }
}

object FormatUtils {
    private val dateFormatter = SimpleDateFormat(
        "dd MMM, HH:mm",
        Locale.Builder().setLanguage("pt").setRegion("BR").build()
    )

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatDate(timestamp: Long): String {
        return dateFormatter.format(Date(timestamp))
    }
}
