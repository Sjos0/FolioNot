package com.sjos.folionote.ui.theme

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
        NotebookIconSpec("book", "Livro", FolioIcons.Rounded.MenuBook, AccentBlue),
        NotebookIconSpec("stories", "Biblioteca", FolioIcons.Rounded.AutoStories, AccentCoral),
        NotebookIconSpec("chart", "Dados", FolioIcons.Rounded.BarChart, AccentCyan),
        NotebookIconSpec("science", "Ciência", FolioIcons.Rounded.Biotech, AccentTeal),
        NotebookIconSpec("nature", "Biologia", FolioIcons.Rounded.Eco, AccentEmerald),
        NotebookIconSpec("robot", "IA", FolioIcons.Rounded.SmartToy, AccentPurple),
        NotebookIconSpec("write", "Redação", FolioIcons.Rounded.EditNote, AccentAmber),
        NotebookIconSpec("school", "Estudos", FolioIcons.Rounded.School, AccentAmber),
        NotebookIconSpec("laptop", "Tech", FolioIcons.Rounded.LaptopMac, AccentCyan),
        NotebookIconSpec("math", "Matemática", FolioIcons.Rounded.Calculate, AccentBlue),
        NotebookIconSpec("code", "Código", FolioIcons.Rounded.Code, AccentPink),
        NotebookIconSpec("doc", "Documento", FolioIcons.Rounded.Description, AccentBlue),
        NotebookIconSpec("folder", "Projeto", FolioIcons.Rounded.Folder, AccentEmerald)
    )

    fun resolve(key: String): NotebookIconSpec {
        return all.firstOrNull { it.key == key } ?: all.first()
    }
}
