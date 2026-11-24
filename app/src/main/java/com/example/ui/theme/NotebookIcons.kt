package com.example.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class NotebookIconSpec(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color
)

object NotebookIcons {
    val all: List<NotebookIconSpec> = listOf(
        NotebookIconSpec("book", "Livro", Icons.Rounded.MenuBook, AccentBlue),
        NotebookIconSpec("stories", "Biblioteca", Icons.Rounded.AutoStories, AccentCoral),
        NotebookIconSpec("chart", "Dados", Icons.Rounded.BarChart, AccentCyan),
        NotebookIconSpec("science", "Ciência", Icons.Rounded.Biotech, AccentTeal),
        NotebookIconSpec("nature", "Biologia", Icons.Rounded.Eco, AccentEmerald),
        NotebookIconSpec("robot", "IA", Icons.Rounded.SmartToy, AccentPurple),
        NotebookIconSpec("write", "Redação", Icons.Rounded.EditNote, AccentAmber),
        NotebookIconSpec("school", "Estudos", Icons.Rounded.School, AccentAmber),
        NotebookIconSpec("laptop", "Tech", Icons.Rounded.LaptopMac, AccentCyan),
        NotebookIconSpec("math", "Matemática", Icons.Rounded.Calculate, AccentBlue),
        NotebookIconSpec("code", "Código", Icons.Rounded.Code, AccentPink),
        NotebookIconSpec("doc", "Documento", Icons.Rounded.Description, AccentBlue),
        NotebookIconSpec("folder", "Projeto", Icons.Rounded.Folder, AccentEmerald)
    )

    fun resolve(key: String): NotebookIconSpec {
        return all.firstOrNull { it.key == key } ?: all.first()
    }
}
