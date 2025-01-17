package build.wallet.statemachine.recovery.sweep

import androidx.compose.runtime.Composable
import build.wallet.analytics.events.screen.context.NfcEventTrackerScreenIdContext
import build.wallet.analytics.events.screen.id.AppRecoveryEventTrackerScreenId
import build.wallet.analytics.events.screen.id.HardwareRecoveryEventTrackerScreenId
import build.wallet.recovery.getEventId
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.data.recovery.sweep.SweepData.AwaitingHardwareSignedSweepsData
import build.wallet.statemachine.data.recovery.sweep.SweepData.GeneratePsbtsFailedData
import build.wallet.statemachine.data.recovery.sweep.SweepData.GeneratingPsbtsData
import build.wallet.statemachine.data.recovery.sweep.SweepData.NoFundsFoundData
import build.wallet.statemachine.data.recovery.sweep.SweepData.PsbtsGeneratedData
import build.wallet.statemachine.data.recovery.sweep.SweepData.SigningAndBroadcastingSweepsData
import build.wallet.statemachine.data.recovery.sweep.SweepData.SweepCompleteData
import build.wallet.statemachine.data.recovery.sweep.SweepData.SweepFailedData
import build.wallet.statemachine.money.amount.MoneyAmountUiProps
import build.wallet.statemachine.money.amount.MoneyAmountUiStateMachine
import build.wallet.statemachine.nfc.NfcSessionUIStateMachine
import build.wallet.statemachine.nfc.NfcSessionUIStateMachineProps
import kotlinx.collections.immutable.toImmutableList

class SweepUiStateMachineImpl(
  private val nfcSessionUIStateMachine: NfcSessionUIStateMachine,
  private val moneyAmountUiStateMachine: MoneyAmountUiStateMachine,
) : SweepUiStateMachine {
  @Composable
  override fun model(props: SweepUiProps): ScreenModel {
    // TODO: Add Hardware Proof of Possession state machine if GetAccountKeysets
    //   endpoint ends up requiring it.
    return when (val sweepData = props.sweepData) {
      /** Show spinner while we wait for PSBTs to be generated */
      is GeneratingPsbtsData ->
        generatingPsbtsBodyModel(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_GENERATING_PSBTS
            ),
          onBack = props.onExit,
          presentationStyle = props.presentationStyle
        )

      /** Terminal error state: PSBT generation failed */
      is GeneratePsbtsFailedData ->
        generatePsbtsFailedScreenModel(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_GENERATE_PSBTS_ERROR,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_GENERATE_PSBTS_ERROR
            ),
          onPrimaryButtonClick = props.onExit,
          presentationStyle = props.presentationStyle
        )

      is NoFundsFoundData ->
        zeroBalancePrompt(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_ZERO_BALANCE,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_ZERO_BALANCE
            ),
          onDone = sweepData.proceed,
          presentationStyle = props.presentationStyle
        )

      /** PSBTs have been generated. Prompt to continue to sign + broadcast. */
      is PsbtsGeneratedData -> {
        sweepFundsPrompt(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_SIGN_PSBTS_PROMPT,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_SIGN_PSBTS_PROMPT
            ),
          recoveredFactor = sweepData.recoveredFactor,
          fee =
            moneyAmountUiStateMachine.model(
              MoneyAmountUiProps(
                primaryMoney = sweepData.totalFeeAmount,
                secondaryAmountCurrency = props.fiatCurrency
              )
            ),
          onSubmit = sweepData.startSweep,
          presentationStyle = props.presentationStyle
        )
      }

      is AwaitingHardwareSignedSweepsData ->
        nfcSessionUIStateMachine.model(
          NfcSessionUIStateMachineProps(
            session = { session, commands ->
              sweepData.needsHwSign
                .map { commands.signTransaction(session, it.value, it.key) }
                .toImmutableList()
            },
            onSuccess = sweepData.addHwSignedSweeps,
            onCancel = props.onExit,
            isHardwareFake = sweepData.keyboxConfig.isHardwareFake,
            screenPresentationStyle = props.presentationStyle,
            eventTrackerContext = NfcEventTrackerScreenIdContext.SIGN_MANY_TRANSACTIONS
          )
        )

      /** Server+App signing and broadcasting the transactions */
      is SigningAndBroadcastingSweepsData ->
        broadcastingScreenModel(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_BROADCASTING,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_BROADCASTING
            ),
          onBack = props.onExit,
          presentationStyle = props.presentationStyle
        )

      /** Terminal state: Broadcast completed */
      is SweepCompleteData ->
        sweepSuccessScreenModel(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_SUCCESS,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_SUCCESS
            ),
          recoveredFactor = sweepData.recoveredFactor,
          onDone = sweepData.proceed,
          presentationStyle = props.presentationStyle
        )

      /** Terminal error state: Sweep failed */
      is SweepFailedData ->
        sweepFailedScreenModel(
          id =
            sweepData.recoveredFactor.getEventId(
              AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_SWEEP_FAILED,
              HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_SWEEP_FAILED
            ),
          onRetry = sweepData.retry,
          onExit = props.onExit,
          presentationStyle = props.presentationStyle
        )
    }
  }
}
