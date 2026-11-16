package com.datapack.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Zips a list of [Batch] audio files and emails them to the configured address
 * via Gmail SMTP with STARTTLS.  Large sets are split into multiple emails so
 * no single attachment exceeds [MAX_ZIP_BYTES].
 */
object EmailSender {

    private const val TAG = "EmailSender"

    /**
     * Safe upper bound for a single ZIP attachment.  Gmail advertises 25 MB
     * but the MIME encoding overhead grows the raw bytes ~33 %, so we leave
     * room by capping the unencoded ZIP at 18 MB.
     */
    private const val MAX_ZIP_BYTES = 18L * 1024 * 1024  // 18 MB

    // ── ZIP helpers ────────────────────────────────────────────────────────

    /**
     * Groups [batches] into chunks where each chunk's raw WAV total fits
     * inside [MAX_ZIP_BYTES], creates a ZIP per chunk, and returns the
     * list of ZIP files (in order).  Old files in the cache dir are wiped
     * first so we never accumulate stale zips.
     */
    fun buildZipChunks(ctx: Context, batches: List<Batch>): List<File> {
        val cacheDir = File(ctx.cacheDir, "email_zips").also { it.mkdirs() }
        cacheDir.listFiles()?.forEach { it.delete() }   // clean previous run

        if (batches.isEmpty()) return emptyList()

        val chunks    = mutableListOf<File>()
        var chunkIdx  = 0
        var chunkSize = 0L
        var chunkList = mutableListOf<Batch>()

        fun flush() {
            if (chunkList.isEmpty()) return
            val zip = File(cacheDir, "recordings_${chunkIdx + 1}of?.zip")
            writeZip(chunkList, zip)
            chunks.add(zip)
            chunkIdx++
            chunkList = mutableListOf()
            chunkSize = 0L
        }

        for (b in batches) {
            val sz = b.file.length()
            // If a single file is bigger than the cap (extremely unlikely for WAV
            // snippets), send it alone regardless.
            if (chunkList.isNotEmpty() && chunkSize + sz > MAX_ZIP_BYTES) flush()
            chunkList.add(b)
            chunkSize += sz
        }
        flush()

        // Rename files to include the total count now that we know it
        val total = chunks.size
        return chunks.mapIndexed { i, f ->
            val renamed = File(cacheDir, "recordings_${i + 1}of${total}.zip")
            f.renameTo(renamed)
            renamed
        }
    }

    private fun writeZip(batches: List<Batch>, dest: File) {
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            for (b in batches) {
                zip.putNextEntry(ZipEntry(b.file.name))
                b.file.inputStream().use { it.copyTo(zip, bufferSize = 65_536) }
                zip.closeEntry()
            }
        }
        Log.d(TAG, "Created ZIP: ${dest.name}  (${dest.length() / 1024} KB, ${batches.size} files)")
    }

    // ── SMTP ───────────────────────────────────────────────────────────────

    /**
     * Sends one [zipFile] as an email attachment from [fromEmail] to itself.
     *
     * @param zipFile      the ZIP to attach
     * @param chunkIndex   0-based chunk index (used only for the subject line)
     * @param totalChunks  total number of emails in this batch
     */
    fun sendEmail(
        fromEmail: String,
        password: String,
        zipFile: File,
        chunkIndex: Int,
        totalChunks: Int,
    ) {
        val props = Properties().apply {
            put("mail.smtp.auth",                "true")
            put("mail.smtp.starttls.enable",     "true")
            put("mail.smtp.starttls.required",   "true")
            put("mail.smtp.host",                "smtp.gmail.com")
            put("mail.smtp.port",                "587")
            put("mail.smtp.ssl.trust",           "smtp.gmail.com")
            put("mail.smtp.timeout",             "45000")
            put("mail.smtp.connectiontimeout",   "30000")
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(fromEmail, password)
        })

        val subject = if (totalChunks == 1)
            "Datapack recordings"
        else
            "Datapack recordings — part ${chunkIndex + 1} of $totalChunks"

        val message = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(fromEmail))
            setSubject(subject)
            sentDate = Date()
        }

        val multipart = MimeMultipart().also { mp ->
            mp.addBodyPart(MimeBodyPart().apply {
                val count = zipFile.nameWithoutExtension   // e.g. "recordings_1of3"
                setText("Datapack audio capture — $count\n\nGenerated by Datapack.")
            })
            mp.addBodyPart(MimeBodyPart().apply {
                attachFile(zipFile)
                fileName = zipFile.name
            })
        }
        message.setContent(multipart)

        Transport.send(message)
        Log.d(TAG, "Sent: $subject  (${zipFile.length() / 1024} KB)")
    }
}
