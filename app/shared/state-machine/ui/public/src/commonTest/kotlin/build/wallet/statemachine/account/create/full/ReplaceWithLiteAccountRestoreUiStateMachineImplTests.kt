package build.wallet.statemachine.account.create.full

import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId
import build.wallet.auth.OnboardingFullAccountDeleterMock
import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.bitkey.keybox.KeyboxMock
import build.wallet.cloud.backup.CloudBackupV2WithLiteAccountMock
import build.wallet.coroutines.turbine.turbines
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.onboarding.LiteAccountBackupToFullAccountUpgrader
import build.wallet.onboarding.LiteAccountBackupToFullAccountUpgraderMock
import build.wallet.statemachine.ScreenStateMachineMock
import build.wallet.statemachine.auth.ProofOfPossessionNfcProps
import build.wallet.statemachine.auth.ProofOfPossessionNfcStateMachine
import build.wallet.statemachine.auth.Request
import build.wallet.statemachine.core.LoadingBodyModel
import build.wallet.statemachine.core.awaitScreenWithBody
import build.wallet.statemachine.core.awaitScreenWithBodyModelMock
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.test
import build.wallet.statemachine.data.account.CreateFullAccountData
import com.github.michaelbull.result.Err
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf

class ReplaceWithLiteAccountRestoreUiStateMachineImplTests : FunSpec({
  val onboardingFullAccountDeleter = OnboardingFullAccountDeleterMock(turbines::create)
  val liteAccountBackupToFullAccountUpgrader =
    LiteAccountBackupToFullAccountUpgraderMock(turbines::create)
  val stateMachine =
    ReplaceWithLiteAccountRestoreUiStateMachineImpl(
      proofOfPossessionNfcStateMachine =
        object : ProofOfPossessionNfcStateMachine, ScreenStateMachineMock<ProofOfPossessionNfcProps>(
          id = "pop-nfc"
        ) {},
      onboardingFullAccountDeleter = onboardingFullAccountDeleter,
      liteAccountBackupToFullAccountUpgrader = liteAccountBackupToFullAccountUpgrader
    )

  val onAccountUpgradedCalls = turbines.create<FullAccount>("onAccountUpgraded calls")

  val fullAccountIdToReplace = FullAccountId("full-account-id-to-replace")
  val keybox = KeyboxMock.copy(fullAccountId = fullAccountIdToReplace)

  val props =
    ReplaceWithLiteAccountRestoreUiProps(
      CreateFullAccountData.ReplaceWithLiteAccountRestoreData(
        keyboxToReplace = keybox,
        liteAccountCloudBackup = CloudBackupV2WithLiteAccountMock,
        onAccountUpgraded = onAccountUpgradedCalls::add,
        onBack = {}
      )
    )

  test("happy path") {
    stateMachine.test(props) {
      awaitScreenWithBodyModelMock<ProofOfPossessionNfcProps> {
        request.shouldBeTypeOf<Request.HwKeyProof>().onSuccess(HwFactorProofOfPossession("fake"))
      }
      awaitScreenWithBody<LoadingBodyModel>(
        CloudEventTrackerScreenId.LOADING_RESTORING_FROM_LITE_ACCOUNT_CLOUD_BACKUP_DURING_FULL_ACCOUNT_ONBOARDING
      )

      onboardingFullAccountDeleter.deleteAccountCalls
        .awaitItem()
        .shouldBe(fullAccountIdToReplace)
      liteAccountBackupToFullAccountUpgrader.upgradeAccountCalls
        .awaitItem()
        .shouldBe(CloudBackupV2WithLiteAccountMock to keybox)
      onAccountUpgradedCalls.awaitItem().shouldBe(FullAccountMock)
    }
  }

  test("upgrade failure and retry") {
    stateMachine.test(props) {
      liteAccountBackupToFullAccountUpgrader.result =
        Err(LiteAccountBackupToFullAccountUpgrader.UpgradeError("boom"))
      awaitScreenWithBodyModelMock<ProofOfPossessionNfcProps> {
        request.shouldBeTypeOf<Request.HwKeyProof>().onSuccess(HwFactorProofOfPossession("fake"))
      }
      awaitScreenWithBody<LoadingBodyModel>(
        CloudEventTrackerScreenId.LOADING_RESTORING_FROM_LITE_ACCOUNT_CLOUD_BACKUP_DURING_FULL_ACCOUNT_ONBOARDING
      )

      onboardingFullAccountDeleter.deleteAccountCalls
        .awaitItem()
        .shouldBe(fullAccountIdToReplace)
      liteAccountBackupToFullAccountUpgrader.upgradeAccountCalls
        .awaitItem()
        .shouldBe(CloudBackupV2WithLiteAccountMock to keybox)

      // Retry
      awaitScreenWithBody<FormBodyModel>(
        CloudEventTrackerScreenId.FAILURE_RESTORE_FROM_LITE_ACCOUNT_CLOUD_BACKUP_AFTER_ONBOARDING
      ) {
        liteAccountBackupToFullAccountUpgrader.reset()
        primaryButton!!.onClick()
      }

      awaitScreenWithBodyModelMock<ProofOfPossessionNfcProps> {
        request.shouldBeTypeOf<Request.HwKeyProof>().onSuccess(HwFactorProofOfPossession("fake"))
      }
      awaitScreenWithBody<LoadingBodyModel>(
        CloudEventTrackerScreenId.LOADING_RESTORING_FROM_LITE_ACCOUNT_CLOUD_BACKUP_DURING_FULL_ACCOUNT_ONBOARDING
      )
      onboardingFullAccountDeleter.deleteAccountCalls
        .awaitItem()
        .shouldBe(fullAccountIdToReplace)
      liteAccountBackupToFullAccountUpgrader.upgradeAccountCalls
        .awaitItem()
        .shouldBe(CloudBackupV2WithLiteAccountMock to keybox)
      onAccountUpgradedCalls.awaitItem().shouldBe(FullAccountMock)
    }
  }
})
