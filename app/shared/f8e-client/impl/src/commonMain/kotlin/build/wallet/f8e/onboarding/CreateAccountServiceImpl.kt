package build.wallet.f8e.onboarding

import build.wallet.bitkey.account.LiteAccountConfig
import build.wallet.bitkey.app.AppRecoveryAuthPublicKey
import build.wallet.bitkey.f8e.F8eSpendingKeyset
import build.wallet.bitkey.f8e.F8eSpendingPublicKey
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.f8e.LiteAccountId
import build.wallet.bitkey.keybox.KeyCrossDraft
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.client.F8eHttpClient
import build.wallet.f8e.error.F8eError
import build.wallet.f8e.error.code.CreateAccountClientErrorCode
import build.wallet.f8e.error.logF8eFailure
import build.wallet.f8e.error.toF8eError
import build.wallet.f8e.onboarding.model.CreateAccountRequestBody
import build.wallet.f8e.onboarding.model.CreateAccountResponseBody
import build.wallet.f8e.onboarding.model.FullCreateAccountRequestBody
import build.wallet.f8e.onboarding.model.FullCreateAccountResponseBody
import build.wallet.f8e.onboarding.model.LiteCreateAccountRequestBody
import build.wallet.f8e.onboarding.model.LiteCreateAccountResponseBody
import build.wallet.ktor.result.bodyResult
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import com.github.michaelbull.result.mapError
import io.ktor.client.request.post
import io.ktor.client.request.setBody

class CreateAccountServiceImpl(
  private val f8eHttpClient: F8eHttpClient,
) : CreateFullAccountService, CreateLiteAccountService {
  // Full Account
  override suspend fun createAccount(
    keyCrossDraft: KeyCrossDraft.WithAppKeysAndHardwareKeys,
  ): Result<CreateFullAccountService.Success, F8eError<CreateAccountClientErrorCode>> {
    return createAccount<FullCreateAccountResponseBody>(
      f8eEnvironment = keyCrossDraft.config.f8eEnvironment,
      requestBody =
        FullCreateAccountRequestBody(
          appKeyBundle = keyCrossDraft.appKeyBundle,
          hardwareKeyBundle = keyCrossDraft.hardwareKeyBundle,
          network = keyCrossDraft.config.networkType,
          isTestAccount = if (keyCrossDraft.config.isTestAccount) true else null
        )
    ).map { response ->
      CreateFullAccountService.Success(
        f8eSpendingKeyset =
          F8eSpendingKeyset(
            keysetId = response.keysetId,
            spendingPublicKey = F8eSpendingPublicKey(dpub = response.spending)
          ),
        fullAccountId = FullAccountId(response.accountId)
      )
    }
  }

  // Lite Account
  override suspend fun createAccount(
    recoveryKey: AppRecoveryAuthPublicKey,
    config: LiteAccountConfig,
  ): Result<LiteAccountId, F8eError<CreateAccountClientErrorCode>> {
    return createAccount<LiteCreateAccountResponseBody>(
      f8eEnvironment = config.f8eEnvironment,
      requestBody =
        LiteCreateAccountRequestBody(
          appRecoveryAuthKey = recoveryKey,
          isTestAccount = if (config.isTestAccount) true else null
        )
    ).map { response ->
      LiteAccountId(serverId = response.accountId)
    }
  }

  private suspend inline fun <reified ResponseBody : CreateAccountResponseBody> createAccount(
    f8eEnvironment: F8eEnvironment,
    requestBody: CreateAccountRequestBody,
  ): Result<ResponseBody, F8eError<CreateAccountClientErrorCode>> {
    return f8eHttpClient.unauthenticated(f8eEnvironment)
      .bodyResult<ResponseBody> {
        post("/api/accounts") {
          setBody(requestBody)
        }
      }
      .mapError { it.toF8eError<CreateAccountClientErrorCode>() }
      .logF8eFailure { "Failed to create account on f8e" }
  }
}
