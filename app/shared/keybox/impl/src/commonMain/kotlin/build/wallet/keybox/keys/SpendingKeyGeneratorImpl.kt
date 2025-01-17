package build.wallet.keybox.keys

import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitcoin.keys.ExtendedKeyGenerator
import build.wallet.bitkey.app.AppSpendingPrivateKey
import build.wallet.bitkey.app.AppSpendingPublicKey
import build.wallet.bitkey.spending.SpendingKeypair
import build.wallet.logging.log
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding

class SpendingKeyGeneratorImpl(
  private val extendedKeyGenerator: ExtendedKeyGenerator,
) : SpendingKeyGenerator {
  override suspend fun generate(network: BitcoinNetworkType): Result<SpendingKeypair, Throwable> =
    binding {
      log { "Generating app spending key" }

      val spendingDescriptorKeypair = extendedKeyGenerator.generate(network).bind()
      SpendingKeypair(
        publicKey = AppSpendingPublicKey(spendingDescriptorKeypair.publicKey),
        privateKey = AppSpendingPrivateKey(spendingDescriptorKeypair.privateKey)
      )
    }
}
