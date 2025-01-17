package build.wallet.statemachine.recovery.lostapp.initiate

import build.wallet.analytics.events.screen.id.AppRecoveryEventTrackerScreenId.LOST_APP_DELAY_NOTIFY_INITIATION_CANCEL_OTHER_RECOVERY_PROMPT
import build.wallet.analytics.events.screen.id.HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_INITIATION_CANCEL_OTHER_RECOVERY_PROMPT
import build.wallet.bitkey.factor.PhysicalFactor
import build.wallet.bitkey.factor.PhysicalFactor.App
import build.wallet.bitkey.factor.PhysicalFactor.Hardware
import build.wallet.recovery.getEventId
import build.wallet.statemachine.core.Icon.LargeIconWarningFilled
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.ScreenPresentationStyle
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.ui.model.Click
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Size.Footer
import build.wallet.ui.model.button.ButtonModel.Treatment.SecondaryDestructive

fun RecoveryConflictModel(
  cancelingRecoveryLostFactor: PhysicalFactor,
  onCancelRecovery: () -> Unit,
  presentationStyle: ScreenPresentationStyle,
) = ScreenModel(
  body =
    FormBodyModel(
      id =
        cancelingRecoveryLostFactor.getEventId(
          LOST_APP_DELAY_NOTIFY_INITIATION_CANCEL_OTHER_RECOVERY_PROMPT,
          LOST_HW_DELAY_NOTIFY_INITIATION_CANCEL_OTHER_RECOVERY_PROMPT
        ),
      onBack = null,
      toolbar = null,
      header =
        FormHeaderModel(
          icon = LargeIconWarningFilled,
          headline = "Recovery Conflict",
          subline =
            when (cancelingRecoveryLostFactor) {
              Hardware ->
                buildString {
                  appendLine(
                    "We’ve detected an attempt to replace your Bitkey hardware device using the mobile phone currently active with your wallet."
                  )
                  appendLine()
                  appendLine(
                    "If you didn’t initiate this recovery, please tap “Cancel conflicting recovery” before proceeding with your mobile key recovery on this phone."
                  )
                  appendLine()
                  appendLine(
                    "If you are attempting to replace your currently paired Bitkey hardware device, please proceed with the recovery process on your active phone."
                  )
                }

              App ->
                buildString {
                  appendLine(
                    "We’ve detected an attempt to recover your wallet to another phone using your paired Bitkey device."
                  )
                  appendLine()
                  appendLine(
                    "If you didn’t initiate this recovery, please tap “Cancel conflicting recovery”."
                  )
                  appendLine()
                  appendLine(
                    "If you are attempting to recover with another phone, please wait and complete your recovery on your new phone."
                  )
                }
            }
        ),
      primaryButton = null,
      secondaryButton =
        ButtonModel(
          text = "Cancel conflicting recovery",
          onClick = Click.standardClick { onCancelRecovery() },
          size = Footer,
          treatment = SecondaryDestructive
        )
    ),
  presentationStyle = presentationStyle
)
