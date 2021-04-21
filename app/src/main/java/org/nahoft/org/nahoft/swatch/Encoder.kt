package org.nahoft.org.nahoft.swatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.nahoft.codex.Encryption
import org.nahoft.stencil.CapturePhotoUtils
import org.nahoft.stencil.ImageSize
import java.nio.ByteBuffer
import org.nahoft.swatch.Swatch
import org.nahoft.swatch.lengthMessageKey
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Encoder {
    @ExperimentalUnsignedTypes
    fun encode(context: Context, encrypted: ByteArray, coverUri: Uri): Uri? {
        // Get the photo
        val cover = BitmapFactory.decodeStream(context.contentResolver.openInputStream(coverUri))
        val result = encode(encrypted, cover)

        val title = ""
        val description = ""
        val resultUri = CapturePhotoUtils.insertImage(context, result, title, description)

        return resultUri
    }

    @ExperimentalUnsignedTypes
    fun encode(encrypted: ByteArray, cover: Bitmap): Bitmap? {
        var workingCover = cover
        val messageLength = encrypted.size.toShort() // Length measured in bytes
        val lengthBytes = ByteBuffer.allocate(java.lang.Short.BYTES).putShort(messageLength).array()
        val encryptedLengthBytes = Swatch.polish(lengthBytes, lengthMessageKey)
        val lengthBits = bitsFromBytes(encryptedLengthBytes)
        val lengthBitsSize = lengthBits.size

        // Convert message size from bytes to bits
        // Pad the message bits to be of max size
        val messageBits = bitsFromBytes(encrypted)
        val messageBitsSize = messageBits.size

        // Scale the image if necessary
        workingCover = scale(cover)

        // The number of pixels is the image height (in pixels) times the image width (in pixels)
        val numPixels = workingCover.height * workingCover.width

        // Do we have enough pixels for the bits we need to encode?
        val lengthPatchSize = numPixels / (lengthBitsSize * 2)
        val messagePatchSize = numPixels / (messageBitsSize * 2)

        if (lengthPatchSize < Swatch.minimumPatchSize) {
            return null
        }
        if (messagePatchSize < Swatch.minimumPatchSize) {
            return null
        }

        val result = workingCover.copy(Bitmap.Config.ARGB_8888, true)
        return encode(result, lengthBits, messageBits)
    }

    // Takes both messages (length message, and message message) as bits and the bitmap we want to put them in
    @ExperimentalUnsignedTypes
    fun encode(cover: Bitmap, message1: IntArray, message2: IntArray): Bitmap? {
        // Make a set of rules for encoding each message
        // Use different keys so that the patches are different even if the patch sizes are the same
        val rules1 = makeRules(cover, message1, 1)
        val rules2 = makeRules(cover, message2, 2)

        if (rules1 == null) { return null }
        if (rules2 == null) { return null }

        val check = checkRules(rules1, rules2)
        if (!check) { return null }

        val solver = Solver(cover, rules1, rules2)
        val success = solver.solve()
        if (!success) {
            return null
        }

        return cover
    }

    fun scale(bitmap: Bitmap): Bitmap {
        // Resize result if it is larger than 4mb
        val sizeBytes = bitmap.height * bitmap.width * 4
        val targetSizeBytes = 4000000.0
        if (sizeBytes > targetSizeBytes) {
            val originalSize = ImageSize(
                bitmap.height.toDouble(),
                bitmap.width.toDouble(),
                32.0 //ARGB_8888 8 bits for each in ARGB added together
            )
            val scaledSize = resizePreservingAspectRatio(originalSize, targetSizeBytes)
            val newBitmap = Bitmap.createScaledBitmap(
                bitmap,
                scaledSize.width.roundToInt(),
                scaledSize.height.roundToInt(),
                true
            )

            return newBitmap
        }
        else
        {
            return bitmap
        }
    }

    private fun resizePreservingAspectRatio(originalSize: ImageSize, targetSizeBytes: Double): ImageSize {
        val aspectRatio = originalSize.height/originalSize.width
        val targetSizePixels = targetSizeBytes/originalSize.colorDepthBytes
        val scaledWidth = sqrt(targetSizePixels/aspectRatio)
        val scaledHeight = aspectRatio * scaledWidth

        return  ImageSize(scaledHeight, scaledWidth, originalSize.colorDepthBytes)
    }

    fun checkRules(aList: Array<Rule>, bList: Array<Rule>): Boolean {
        var setA: MutableSet<Int> = mutableSetOf()
        for (a in aList) {
            for (pixel in a.patch0.pixels) {
                if (setA.contains(pixel.index)) {
                    return false
                }

                setA.add(pixel.index)
            }

            for (pixel in a.patch1.pixels) {
                if (setA.contains(pixel.index)) {
                    return false
                }

                setA.add(pixel.index)
            }
        }

        var setB: MutableSet<Int> = mutableSetOf()
        for (b in bList) {
            for (pixel in b.patch0.pixels) {
                if (setB.contains(pixel.index)) {
                    return false
                }

                setB.add(pixel.index)
            }

            for (pixel in b.patch1.pixels) {
                if (setB.contains(pixel.index)) {
                    return false
                }

                setB.add(pixel.index)
            }
        }

        return true
    }

    /// Generates an array of rules.
    /// Each rule returns 2 patches and a constraint (whether or not they are lighter or darker than each other)
    /// Greater is a 1
    /// Less is a 0
    fun makeRules(cover: Bitmap, message: IntArray, key: Int): Array<Rule>?
    {
        val numPixels = cover.height * cover.width

        // Each bit needs a pair of patches
        val patchSize = numPixels / (message.size*2)

        var mapped = MappedBitmap(cover, key)

        var rules: Array<Rule> = arrayOf()

        // For each bit in the message we get a rule
        // A rule is two patches and a constraint
        for (index in message.indices)
        {
            val bit = message[index]

            // Does patchA need to be lighter than patchB, or darker?
            // Brightness is based on the average brightness for the entire patch.
            val constraint: EncoderConstraint? = when (bit) {
                1 -> EncoderConstraint.GREATER
                0 -> EncoderConstraint.LESS
                else -> null
            }

            if (constraint == null) {
                return null
            }

            val rule = Rule(index, patchSize, constraint, mapped)
            rules += rule
        }

        return rules
    }
}