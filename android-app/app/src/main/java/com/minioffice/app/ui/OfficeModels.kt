package com.minioffice.app.ui

import uniffi.mini_office_core.FileKind

enum class OfficeModule(val label: String) {
    Docs("Docs"),
    Sheets("Sheets"),
    Slides("Slides"),
    Files("Tệp")
}

data class ActiveDocument(
    val name: String,
    val path: String,
    val kind: FileKind,
    val content: String,
    val isDirty: Boolean = false
)
