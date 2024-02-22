package build.wallet.emergencyaccesskit

import com.github.michaelbull.result.Result
import okio.ByteString

/**
 * QR Code generator abstraction.
 *
 * Responsible for generating a QR Code image given contents and size parameters. On Android it uses
 * `com.google.zxing` library implementation, and on iOS it uses the built-in CoreImage framework.
 */
interface EmergencyAccessKitQrCodeGenerator {
  /**
   * Generates a new QR Code image given the input [width], [height], and [contents] parameters.
   *
   * Returns successful [ByteString] of the image bytes, an error otherwise.
   */
  suspend fun imageBytes(
    width: Float,
    height: Float,
    contents: String,
  ): Result<ByteString, EmergencyAccessKitQrCodeError>
}

data class EmergencyAccessKitQrCodeError(
  override val cause: Throwable?,
) : Error()