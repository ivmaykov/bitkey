package build.wallet.statemachine.recovery.hardware.initiating

import build.wallet.analytics.events.screen.id.HardwareRecoveryEventTrackerScreenId
import build.wallet.compose.collections.immutableListOf
import build.wallet.statemachine.core.Icon
import build.wallet.statemachine.core.Icon.SmallIconBitcoinStroked
import build.wallet.statemachine.core.Icon.SmallIconClock
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.ScreenPresentationStyle
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel.Explainer
import build.wallet.statemachine.core.form.FormMainContentModel.Explainer.Statement
import build.wallet.ui.model.Click
import build.wallet.ui.model.alert.AlertModel
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Size.Footer
import build.wallet.ui.model.icon.IconButtonModel
import build.wallet.ui.model.icon.IconModel
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory.Companion.CloseAccessory
import build.wallet.ui.model.toolbar.ToolbarModel

fun HardwareReplacementInstructionsModel(
  onContinue: () -> Unit,
  onClose: () -> Unit,
) = FormBodyModel(
  onBack = onClose,
  toolbar =
    ToolbarModel(
      leadingAccessory = CloseAccessory(onClose)
    ),
  header =
    FormHeaderModel(
      headline = "Recover your wallet to a new Bitkey device"
    ),
  mainContentList =
    immutableListOf(
      Explainer(
        immutableListOf(
          Statement(
            leadingIcon = SmallIconClock,
            title = "7-day security waiting period",
            body = "During this time, you’ll get regular alerts about the recovery. No action is needed; they are to keep you informed and aware."
          ),
          Statement(
            leadingIcon = Icon.SmallIconMinusStroked,
            title = "Cancel anytime",
            body = "You can cancel this process anytime, and return to using your existing Bitkey device if you find it later."
          ),
          Statement(
            leadingIcon = SmallIconBitcoinStroked,
            title = "Small network fee required",
            body = "To recover your key, you’ll need to initiate an on-chain transaction to complete the process."
          )
        )
      )
    ),
  primaryButton =
    ButtonModel(
      text = "Continue",
      onClick = Click.standardClick { onContinue() },
      size = Footer
    ),
  id = HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_INITIATION_INSTRUCTIONS
)

fun NewDeviceReadyQuestionModel(
  showingNoDeviceAlert: Boolean,
  onNoDeviceAlertDismiss: () -> Unit,
  primaryAction: ButtonModel?,
  secondaryAction: ButtonModel? = null,
  showBack: Boolean,
  backIconModel: IconModel,
  onBack: () -> Unit,
  presentationStyle: ScreenPresentationStyle,
) = ScreenModel(
  body =
    FormBodyModel(
      onBack = onBack,
      toolbar =
        ToolbarModel(
          leadingAccessory =
            IconAccessory(
              model =
                IconButtonModel(
                  iconModel = backIconModel,
                  onClick = Click.standardClick { onBack() }
                )
            ).takeIf { showBack }
        ),
      header =
        FormHeaderModel(
          headline = "Do you have a new Bitkey device ready to go?",
          subline = "You’ll need to pair a new Bitkey device before you can start the process of replacing the old one."
        ),
      primaryButton = primaryAction,
      secondaryButton = secondaryAction,
      id = HardwareRecoveryEventTrackerScreenId.LOST_HW_DELAY_NOTIFY_INITIATION_NEW_DEVICE_READY
    ),
  presentationStyle = presentationStyle,
  alertModel =
    if (showingNoDeviceAlert) {
      AlertModel(
        onDismiss = onNoDeviceAlertDismiss,
        title = "A new device is required to recover your wallet.",
        subline = "Please contact the Bitkey team.",
        primaryButtonText = "Got it",
        onPrimaryButtonClick = onNoDeviceAlertDismiss
      )
    } else {
      null
    }
)
