package build.wallet.cloud.backup

import build.wallet.cloud.backup.CloudBackupV2Restorer.CloudBackupV2RestorerError
import build.wallet.cloud.backup.FullAccountCloudBackupRestorer.AccountRestoration
import build.wallet.cloud.backup.RestoreFromBackupError.AccountBackupDecodingError
import build.wallet.cloud.backup.RestoreFromBackupError.AccountBackupRestorationError
import build.wallet.cloud.backup.RestoreFromBackupError.CsekMissing
import build.wallet.cloud.backup.v2.FullAccountKeys
import build.wallet.logging.log
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.mapError

class FullAccountCloudBackupRestorerImpl(
  private val cloudBackupV2Restorer: CloudBackupV2Restorer,
) : FullAccountCloudBackupRestorer {
  override suspend fun restoreFromBackup(
    cloudBackup: CloudBackup,
  ): Result<AccountRestoration, RestoreFromBackupError> {
    return when (cloudBackup) {
      is CloudBackupV2 -> {
        log { "Attempting to restore keybox from v2 backup" }
        cloudBackupV2Restorer.restore(cloudBackup)
          .mapError(::mapBackupV2RestoreErrors)
      }
    }
  }

  private fun mapBackupV2RestoreErrors(error: CloudBackupV2RestorerError) =
    when (error) {
      CloudBackupV2RestorerError.PkekMissingError -> {
        CsekMissing
      }

      is CloudBackupV2RestorerError.AccountBackupDecodingError -> {
        AccountBackupDecodingError(
          cause = error.cause,
          message = "Error decoding Cloud Backup"
        )
      }

      is CloudBackupV2RestorerError.AppAuthKeypairStorageError -> {
        AccountBackupRestorationError(
          cause = error.cause,
          message = "Error storing AppAuthKeypair"
        )
      }

      is CloudBackupV2RestorerError.AppSpendingKeypairStorageError -> {
        AccountBackupRestorationError(
          cause = error.cause,
          message = "Error storing AppSpendingKeypair"
        )
      }

      is CloudBackupV2RestorerError.SocRecTrustedContactIdentityKeyStorageError -> {
        AccountBackupRestorationError(
          cause = error.cause,
          message = "Error storing Social Recovery TrustedContactIdentityKey"
        )
      }
    }

  override suspend fun restoreFromBackupWithDecryptedKeys(
    cloudBackup: CloudBackup,
    keysInfo: FullAccountKeys,
  ): Result<AccountRestoration, RestoreFromBackupError> =
    when (cloudBackup) {
      is CloudBackupV2 ->
        cloudBackupV2Restorer.restoreWithDecryptedKeys(cloudBackup, keysInfo)
          .mapError(::mapBackupV2RestoreErrors)
      else ->
        Err(
          AccountBackupRestorationError(
            null,
            "Unsupported backup type ${cloudBackup::class.simpleName}"
          )
        )
    }
}
