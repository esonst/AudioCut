package com.example.audiocut.utils

import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import java.io.File

/**
 * 音频铃声工具：将本地音频制作为系统铃声
 * 通过 MediaStore 将文件写入公共 Ringtones 目录，再注册为系统默认铃声
 */
object RingtoneHelper {

    /**
     * 将指定音频文件制作为系统铃声
     * @return 是否设置成功
     */
    fun setAsRingtone(context: Context, filePath: String): Boolean {
        return try {
            val sourceFile = File(filePath)
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                Toast.makeText(context, "音频文件不存在", Toast.LENGTH_SHORT).show()
                return false
            }

            // 所有Android版本统一校验WRITE_SETTINGS
            if (!Settings.System.canWrite(context)) {
                Toast.makeText(context, "请开启【允许修改系统设置】权限，才能设置铃声", Toast.LENGTH_LONG).show()
                gotoWriteSettingsPermissionPage(context)
                return false
            }

            val resolver = context.contentResolver
            val displayName = sourceFile.name

            val collection = mediaCollection()
            val existingUri = queryExistingRingtone(resolver, collection, displayName)
            val ringtoneUri = existingUri ?: insertRingtone(resolver, collection, displayName) ?: run {
                Toast.makeText(context, "创建铃声失败", Toast.LENGTH_SHORT).show()
                return false
            }

            if (existingUri == null) {
                resolver.openOutputStream(ringtoneUri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                } ?: return false
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
                    val updateValues = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(ringtoneUri, updateValues, null, null)
                }
            }

            RingtoneManager.setActualDefaultRingtoneUri(
                context,
                RingtoneManager.TYPE_RINGTONE,
                ringtoneUri
            )
            Toast.makeText(context, "已设置为铃声: ${sourceFile.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "设置铃声失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            false
        }
    }


    /** 跳转修改系统设置授权页面 */
    fun gotoWriteSettingsPermissionPage(context: Context) {
        val intent = android.content.Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = "package:${context.packageName}".toUri()
        }
        context.startActivity(intent)
    }

    private fun mediaCollection(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun queryExistingRingtone(
        resolver: android.content.ContentResolver,
        collection: Uri,
        displayName: String
    ): Uri? {
        return try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.IS_RINGTONE}=1",
                arrayOf(displayName),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    android.content.ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun insertRingtone(
        resolver: android.content.ContentResolver,
        collection: Uri,
        displayName: String
    ): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.TITLE, File(displayName).nameWithoutExtension)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(displayName))
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, false)
            put(MediaStore.Audio.Media.IS_ALARM, false)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                put(MediaStore.Audio.Media.IS_PENDING, 1) // 增加pending标记
            }
        }
        return resolver.insert(collection, values)
    }

    private fun mimeFor(fileName: String): String = when (File(fileName).extension.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }
}
