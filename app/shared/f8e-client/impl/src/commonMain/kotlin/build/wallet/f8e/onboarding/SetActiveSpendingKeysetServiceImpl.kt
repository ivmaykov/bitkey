package build.wallet.f8e.onboarding

import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.f8e.client.F8eHttpClient
import build.wallet.ktor.result.NetworkingError
import build.wallet.ktor.result.catching
import build.wallet.logging.logNetworkFailure
import build.wallet.mapUnit
import com.github.michaelbull.result.Result
import io.ktor.client.request.put
import io.ktor.client.request.setBody

class SetActiveSpendingKeysetServiceImpl(
  private val f8eHttpClient: F8eHttpClient,
) : SetActiveSpendingKeysetService {
  override suspend fun set(
    f8eEnvironment: F8eEnvironment,
    fullAccountId: FullAccountId,
    keysetId: String,
    appAuthKey: AppGlobalAuthPublicKey,
    hwFactorProofOfPossession: HwFactorProofOfPossession,
  ): Result<Unit, NetworkingError> {
    return f8eHttpClient
      .authenticated(
        f8eEnvironment = f8eEnvironment,
        accountId = fullAccountId,
        appFactorProofOfPossessionAuthKey = appAuthKey,
        hwFactorProofOfPossession = hwFactorProofOfPossession
      )
      .catching {
        put(urlString = "/api/accounts/${fullAccountId.serverId}/keysets/$keysetId") {
          setBody("{}")
        }
      }
      .logNetworkFailure { "Failed to set active spending keyset" }
      .mapUnit()
  }
}
