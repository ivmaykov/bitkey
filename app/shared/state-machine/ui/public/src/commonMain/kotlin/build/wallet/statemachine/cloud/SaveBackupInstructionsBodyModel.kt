package build.wallet.statemachine.cloud

import build.wallet.analytics.events.screen.id.CloudEventTrackerScreenId
import build.wallet.compose.collections.immutableListOf
import build.wallet.platform.device.DevicePlatform
import build.wallet.platform.device.DevicePlatform.Android
import build.wallet.platform.device.DevicePlatform.IOS
import build.wallet.platform.device.DevicePlatform.Jvm
import build.wallet.statemachine.core.Icon
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.core.form.FormMainContentModel
import build.wallet.ui.model.Click
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Treatment.Primary
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel
import build.wallet.ui.model.toolbar.ToolbarModel
import kotlinx.collections.immutable.toImmutableList

fun SaveBackupInstructionsBodyModel(
  requiresHardware: Boolean,
  isLoading: Boolean,
  onBackupClick: () -> Unit,
  onLearnMoreClick: () -> Unit,
  devicePlatform: DevicePlatform,
  emergencyAccessKitEnabled: Boolean,
): FormBodyModel {
  return FormBodyModel(
    id = CloudEventTrackerScreenId.SAVE_CLOUD_BACKUP_INSTRUCTIONS,
    onBack = null,
    toolbar =
      ToolbarModel(
        trailingAccessory =
          ToolbarAccessoryModel.ButtonAccessory(
            model =
              ButtonModel(
                text = "Learn more",
                treatment = ButtonModel.Treatment.TertiaryPrimary,
                onClick = Click.standardClick(onLearnMoreClick),
                size = ButtonModel.Size.Compact
              )
          )
      ),
    header =
      FormHeaderModel(
        headline =
          when (devicePlatform) {
            Android, Jvm -> "Back up to Google Drive"
            IOS -> "Back up to iCloud"
          },
        subline =
          when (devicePlatform) {
            Android, Jvm -> "Sensitive data stored in your Google Drive is encrypted and only accessible with your Bitkey device."
            IOS -> "Sensitive data stored in your iCloud is encrypted and only accessible with your Bitkey device."
          }
      ),
    mainContentList =
      immutableListOf(
        FormMainContentModel.Explainer(
          items =
            listOfNotNull(
              FormMainContentModel.Explainer.Statement(
                leadingIcon = Icon.SmallIconPhone,
                title = "Mobile Key",
                body = "If you ever get a new phone, simply restore your wallet with this backup and your Bitkey device."
              ),
              FormMainContentModel.Explainer.Statement(
                leadingIcon = Icon.SmallIconKey,
                title = "Emergency Access Kit",
                body =
                  when (devicePlatform) {
                    Android, Jvm -> "If the Bitkey app is unavailable, you’ll be able to use this Emergency Access document located in your Google Drive to maintain self-custody — Never share this file with anyone."
                    IOS -> "If the Bitkey app is unavailable, you’ll be able to use this Emergency Access document located in your iCloud Drive to maintain self-custody — Never share this file with anyone."
                  }
              ).takeIf { emergencyAccessKitEnabled }
            ).toImmutableList()
        )
      ),
    primaryButton =
      ButtonModel(
        text = "Back up",
        requiresBitkeyInteraction = requiresHardware,
        onClick = onBackupClick,
        isLoading = isLoading,
        size = ButtonModel.Size.Footer,
        testTag = "save-cloud-backup",
        treatment = Primary
      )
  )
}
