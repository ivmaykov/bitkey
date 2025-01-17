package build.wallet.auth

import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.account.FullAccountConfig
import build.wallet.bitkey.account.LiteAccount
import build.wallet.bitkey.keybox.KeyCrossDraft
import build.wallet.bitkey.keybox.Keybox
import build.wallet.compose.collections.emptyImmutableList
import build.wallet.f8e.onboarding.UpgradeAccountService
import build.wallet.keybox.KeyboxDao
import build.wallet.keybox.builder.KeyCrossBuilder
import build.wallet.notifications.DeviceTokenManager
import build.wallet.platform.random.Uuid
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.coroutines.binding.binding
import com.github.michaelbull.result.mapError

class LiteToFullAccountUpgraderImpl(
  private val accountAuthenticator: AccountAuthenticator,
  private val authTokenDao: AuthTokenDao,
  private val deviceTokenManager: DeviceTokenManager,
  private val keyboxDao: KeyboxDao,
  private val keyCrossBuilder: KeyCrossBuilder,
  private val upgradeAccountService: UpgradeAccountService,
  private val uuid: Uuid,
) : LiteToFullAccountUpgrader {
  override suspend fun upgradeAccount(
    liteAccount: LiteAccount,
    keyCrossDraft: KeyCrossDraft.WithAppKeysAndHardwareKeys,
  ): Result<FullAccount, AccountCreationError> =
    binding {
      // Upgrade the account on the server and get a server key back.
      val accountServerResponse =
        upgradeAccountService
          .upgradeAccount(liteAccount, keyCrossDraft)
          .mapError { AccountCreationError.AccountCreationF8eError(it) }
          .bind()
      val fullAccountId = accountServerResponse.fullAccountId

      val keyCross =
        keyCrossBuilder
          .addServerKey(
            draft = keyCrossDraft,
            f8eSpendingKeyset = accountServerResponse.f8eSpendingKeyset
          )

      // Store the [Global] scope auth tokens (we don't need to do the [Recovery] scope because
      // the Lite Account already has those stored.
      val authTokens =
        accountAuthenticator
          .appAuth(
            f8eEnvironment = liteAccount.config.f8eEnvironment,
            appAuthPublicKey = keyCrossDraft.appKeyBundle.authKey
          )
          .mapError { AccountCreationError.AccountCreationAuthError(it) }
          .bind()
          .authTokens

      authTokenDao
        .setTokensOfScope(fullAccountId, authTokens, AuthTokenScope.Global)
        .mapError { AccountCreationError.AccountCreationDatabaseError.FailedToSaveAuthTokens(it) }
        .bind()

      // Don't bind the error so we don't block account creation on the success of adding
      // the device token because it won't yet be available on iOS until push notification
      // permissions are requested (which happens after full account creation).
      deviceTokenManager
        .addDeviceTokenIfPresentForAccount(
          fullAccountId = fullAccountId,
          f8eEnvironment = keyCross.config.f8eEnvironment,
          authTokenScope = AuthTokenScope.Global
        )

      // Retain previous recovery auth key.
      val adjustedKeyCross = keyCross.appKeyBundle
        .copy(recoveryAuthKey = liteAccount.recoveryAuthKey)

      // We now have everything we need for our Keyset (app/hw/server spending keys)
      val keybox =
        Keybox(
          localId = uuid.random(),
          fullAccountId = fullAccountId,
          activeSpendingKeyset = keyCross.spendingKeyset,
          activeKeyBundle = adjustedKeyCross,
          inactiveKeysets = emptyImmutableList(),
          config = keyCross.config
        )

      // Save our keybox, but do NOT set as active.
      // Once onboarding completes, it will be activated
      keyboxDao.saveKeyboxAndBeginOnboarding(keybox)
        .mapError { AccountCreationError.AccountCreationDatabaseError.FailedToSaveKeybox(it) }
        .bind()

      FullAccount(fullAccountId, FullAccountConfig.fromKeyboxConfig(keybox.config), keybox)
    }
}
