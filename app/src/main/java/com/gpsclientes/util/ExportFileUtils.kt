package com.gpsclientes.util

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * File chooser intents for export — ACTION_CREATE_DOCUMENT for .xlsx and .pdf.
 * UTF-8 filenames safe (Vigia).
 */
object ExportFileUtils {

    const val MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val MIME_PDF = "application/pdf"

    fun createExcelIntent(fileName: String = "clientes_export.xlsx"): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_XLSX
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

    fun createPdfIntent(fileName: String = "clientes_export.pdf"): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MIME_PDF
            putExtra(Intent.EXTRA_TITLE, fileName)
        }

    fun intentForUri(context: Context, uri: Uri, mime: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
