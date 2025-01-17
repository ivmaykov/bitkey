package build.wallet.f8e.recovery

import build.wallet.bitkey.app.AppGlobalAuthPublicKey
import build.wallet.bitkey.app.AppRecoveryAuthPublicKey
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.factor.PhysicalFactor
import build.wallet.bitkey.hardware.HwAuthPublicKey
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.f8e.client.F8eHttpClient
import build.wallet.f8e.error.F8eError
import build.wallet.f8e.error.code.InitiateAccountDelayNotifyErrorCode
import build.wallet.f8e.error.logF8eFailure
import build.wallet.f8e.error.toF8eError
import build.wallet.f8e.recovery.InitiateAccountDelayNotifyService.SuccessfullyInitiated
import build.wallet.ktor.result.HttpError.UnhandledException
import build.wallet.ktor.result.bodyResult
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.binding
import com.github.michaelbull.result.flatMap
import com.github.michaelbull.result.mapError
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

class InitiateAccountDelayNotifyServiceImpl(
  private val f8eHttpClient: F8eHttpClient,
) : InitiateAccountDelayNotifyService {
  override suspend fun initiate(
    f8eEnvironment: F8eEnvironment,
    fullAccountId: FullAccountId,
    // TODO(W-3092): Remove lostFactor
    lostFactor: PhysicalFactor,
    appGlobalAuthKey: AppGlobalAuthPublicKey,
    appRecoveryAuthKey: AppRecoveryAuthPublicKey,
    hwFactorProofOfPossession: HwFactorProofOfPossession?,
    delayPeriod: Duration?,
    hardwareAuthKey: HwAuthPublicKey,
  ): Result<SuccessfullyInitiated, F8eError<InitiateAccountDelayNotifyErrorCode>> {
    return f8eHttpClient.authenticated(
      f8eEnvironment = f8eEnvironment,
      accountId = fullAccountId,
      hwFactorProofOfPossession = hwFactorProofOfPossession
    )
      .bodyResult<ResponseBody> {
        post("/api/accounts/${fullAccountId.serverId}/delay-notify") {
          setBody(
            RequestBody(
              // TODO(W-3092): Remove delayPeriodNumSec
              delayPeriodNumSec = delayPeriod?.inWholeSeconds?.toInt() ?: 20,
              auth =
                AuthKeypairBody(
                  appGlobal = appGlobalAuthKey.pubKey.value,
                  appRecovery = appRecoveryAuthKey.pubKey.value,
                  hardware = hardwareAuthKey.pubKey.value
                ),
              lostFactor = lostFactor.toServerString()
            )
          )
        }
      }
      .flatMap { body ->
        binding {
          val serverRecovery =
            body.pendingDelayNotify
              .toServerRecovery(fullAccountId)
              .mapError(::UnhandledException)
              .bind()
          SuccessfullyInitiated(serverRecovery)
        }
      }
      .mapError { it.toF8eError<InitiateAccountDelayNotifyErrorCode>() }
      .logF8eFailure { "Failed to initiate D&N recovery." }
  }

  @Serializable
  private data class RequestBody(
    @SerialName("delay_period_num_sec")
    private val delayPeriodNumSec: Int,
    @SerialName("auth")
    private val auth: AuthKeypairBody,
    @SerialName("lost_factor")
    private val lostFactor: String,
  )

  @Serializable
  private data class ResponseBody(
    @SerialName("pending_delay_notify")
    val pendingDelayNotify: ServerResponseBody,
  )
}
