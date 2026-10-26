package app.napominator.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun writeText(ctx: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Не удалось открыть файл для записи")
}

suspend fun readText(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("Не удалось открыть файл")
}

/** Excel лучше открывает кириллицу в CSV с BOM. */
fun csvWithBom(s: String) = "﻿$s"
