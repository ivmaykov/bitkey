package build.wallet.f8e.onboarding

import build.wallet.bitcoin.BitcoinNetworkType
import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.app.AppSpendingPublicKey
import build.wallet.bitkey.f8e.F8eSpendingKeyset
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.hardware.HwSpendingPublicKey
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.ktor.result.NetworkingError
import com.github.michaelbull.result.Result

/**
 * Create new keys for an account. We pass up app and hardware spending keys along with hardware
 * proof of possession, f8e returns new (non-active!) spending keyset.
 */
interface CreateAccountKeysetService {
  suspend fun createKeyset(
    f8eEnvironment: F8eEnvironment,
    fullAccountId: FullAccountId,
    hardwareSpendingKey: HwSpendingPublicKey,
    appSpendingKey: AppSpendingPublicKey,
    network: BitcoinNetworkType,
    appAuthKey: AppGlobalAuthPublicKey?,
    // TODO(W-3070):  Nullable for compatibility reasons with Recovery v1.make non-nullable when
    //                recovery v1 code is deleted.
    hardwareProofOfPossession: HwFactorProofOfPossession?,
  ): Result<F8eSpendingKeyset, NetworkingError>
}
