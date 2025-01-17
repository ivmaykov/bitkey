package build.wallet.f8e.client

import build.wallet.auth.AuthTokenScope
import build.wallet.bitkey.app.AppAuthPublicKey
import build.wallet.bitkey.f8e.AccountId
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

class F8eHttpClientMock : F8eHttpClient {
  override suspend fun authenticated(
    f8eEnvironment: F8eEnvironment,
    accountId: AccountId,
    appFactorProofOfPossessionAuthKey: AppAuthPublicKey?,
    hwFactorProofOfPossession: HwFactorProofOfPossession?,
    engine: HttpClientEngine?,
    authTokenScope: AuthTokenScope,
  ) = HttpClient()

  override suspend fun unauthenticated(
    f8eEnvironment: F8eEnvironment,
    engine: HttpClientEngine?,
  ) = HttpClient()
}
