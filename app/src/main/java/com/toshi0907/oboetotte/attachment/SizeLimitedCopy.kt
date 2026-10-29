package com.toshi0907.oboetotte.attachment

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * サイズ上限・空き容量の不足により処理を中断したことを表す例外の基底クラス。[message]は
 * そのままユーザー向けのToast等に表示できる文言にする(呼び出し元は「〜に失敗しました。」に続けて表示する)。
 */
open class StorageLimitException(message: String) : IOException(message)

/** 読み込んだバイト数が上限を超えた(巨大なファイル・ZIP bomb等)。 */
class SizeLimitExceededException(message: String) : StorageLimitException(message)

/** 書き込み先ボリュームの空き容量が[MIN_FREE_SPACE_BYTES]を下回りそうになった。 */
class InsufficientStorageException(message: String) : StorageLimitException(message)

/**
 * 書き込み後も端末に最低限残しておく空き容量。外部から巨大なファイルを繰り返し共有される等で
 * 内部ストレージを使い切り、OSや他のアプリ・このアプリ自身のDB書き込みまで失敗させないための余裕。
 */
const val MIN_FREE_SPACE_BYTES = 100L * 1024 * 1024

// 空き容量の確認(StatFs相当のシステムコール)を毎回の書き込みで行うと重いため、この間隔ごとに確認する。
private const val FREE_SPACE_CHECK_INTERVAL_BYTES = 8L * 1024 * 1024

/** 上限値をユーザー向けの文言に埋め込む際の表記(例: 100MB、2GB)。 */
fun formatLimitBytes(bytes: Long): String {
    val mb = bytes / (1024 * 1024)
    return if (mb >= 1024 && mb % 1024 == 0L) "${mb / 1024}GB" else "${mb}MB"
}

/**
 * [out]へ最大[maxBytes]バイトまでコピーし、コピーしたバイト数を返す。宣言されたサイズ
 * (ZipEntry.size・ContentProviderのSIZE列等)は偽装できるため信用せず、実際に読み込んだ
 * バイト数で判定する。上限を超えた時点で[limitMessage]付きの[SizeLimitExceededException]を投げる
 * (書きかけの出力の後始末は呼び出し元が行う)。[storageDir]を指定した場合は、そのボリュームの
 * 空き容量が[MIN_FREE_SPACE_BYTES]を下回りそうになった時点で[InsufficientStorageException]を投げる。
 */
fun InputStream.copyToWithLimit(
    out: OutputStream,
    maxBytes: Long,
    limitMessage: String,
    storageDir: File? = null
): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    var nextFreeSpaceCheckAt = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw SizeLimitExceededException(limitMessage)
        if (storageDir != null && total >= nextFreeSpaceCheckAt) {
            if (storageDir.usableSpace - read < MIN_FREE_SPACE_BYTES) {
                throw InsufficientStorageException("端末の空き容量が不足しています")
            }
            nextFreeSpaceCheckAt = total + FREE_SPACE_CHECK_INTERVAL_BYTES
        }
        out.write(buffer, 0, read)
    }
    return total
}

/** [maxBytes]を上限としてメモリ上に読み込む。上限を超えたら[SizeLimitExceededException]を投げる。 */
fun InputStream.readBytesWithLimit(maxBytes: Long, limitMessage: String): ByteArray {
    val out = ByteArrayOutputStream()
    copyToWithLimit(out, maxBytes, limitMessage)
    return out.toByteArray()
}

/** 読み捨て用の出力先。ZIP内の不要なエントリを読み飛ばしつつ展開後のサイズを数えるのに使う。 */
object DiscardOutputStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
