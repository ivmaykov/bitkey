package build.wallet.recovery

import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.f8e.error.F8eError
import build.wallet.f8e.error.code.CancelDelayNotifyRecoveryErrorCode
import build.wallet.f8e.error.code.CancelDelayNotifyRecoveryErrorCode.COMMS_VERIFICATION_REQUIRED
import build.wallet.f8e.error.code.CancelDelayNotifyRecoveryErrorCode.NO_RECOVERY_EXISTS
import build.wallet.f8e.recovery.CancelDelayNotifyRecoveryService
import build.wallet.recovery.RecoveryCanceler.RecoveryCancelerError.F8eCancelDelayNotifyError
import build.wallet.recovery.RecoveryCanceler.RecoveryCancelerError.FailedToClearRecoveryStateError
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.mapError
import com.github.michaelbull.result.recoverIf

/** Cancel recovery on the server and delete from the dao. */
class RecoveryCancelerImpl(
  private val cancelDelayNotifyRecoveryService: CancelDelayNotifyRecoveryService,
  private val recoverySyncer: RecoverySyncer,
) : RecoveryCanceler {
  override suspend fun cancel(
    f8eEnvironment: F8eEnvironment,
    fullAccountId: FullAccountId,
    hwFactorProofOfPossession: HwFactorProofOfPossession?,
  ): Result<Unit, RecoveryCanceler.RecoveryCancelerError> =
    binding {
      cancelDelayNotifyRecoveryService.cancel(
        f8eEnvironment,
        fullAccountId,
        hwFactorProofOfPossession
      )
        .recoverIf(
          predicate = { f8eError ->
            // We expect to get a 4xx NO_RECOVERY_EXISTS error if we try to cancel
            // a recovery that has already been canceled. In that case, treat it as
            // a success, so we will still proceed below and delete the stored recovery
            val clientError = f8eError as? F8eError.SpecificClientError<CancelDelayNotifyRecoveryErrorCode>
            when (clientError?.errorCode) {
              NO_RECOVERY_EXISTS -> true
              COMMS_VERIFICATION_REQUIRED -> false
              null -> false
            }
          },
          transform = {}
        )
        .mapError { F8eCancelDelayNotifyError(it) }
        .bind()

      recoverySyncer.clear()
        .mapError { FailedToClearRecoveryStateError(it) }
        .bind()
    }
}
