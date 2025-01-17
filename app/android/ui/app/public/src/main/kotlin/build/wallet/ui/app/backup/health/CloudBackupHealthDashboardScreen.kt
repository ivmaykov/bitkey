package build.wallet.ui.app.backup.health

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import build.wallet.statemachine.cloud.health.CloudBackupHealthDashboardBodyModel
import build.wallet.ui.app.core.form.FormScreen
import build.wallet.ui.components.toolbar.Toolbar
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel.IconAccessory.Companion.BackAccessory
import build.wallet.ui.model.toolbar.ToolbarMiddleAccessoryModel
import build.wallet.ui.model.toolbar.ToolbarModel

@Composable
fun CloudBackupHealthDashboardScreen(model: CloudBackupHealthDashboardBodyModel) {
  FormScreen(
    onBack = model.onBack,
    toolbarContent = {
      Toolbar(
        model =
          ToolbarModel(
            leadingAccessory = BackAccessory(model.onBack),
            middleAccessory = ToolbarMiddleAccessoryModel("Cloud Backup")
          )
      )
    },
    mainContent = {
      CloudBackupHealthStatusCard(model = model.mobileKeyBackupStatusCard)
      Spacer(modifier = Modifier.height(20.dp))
      // TODO: implement EAK card UI
      model.eakBackupStatusCard?.let {
        CloudBackupHealthStatusCard(model = it)
      }
    }
  )
}

@Preview
@Composable
internal fun CloudBackupHealthDashboardScreenPreview() {
  CloudBackupHealthDashboardScreen(
    model =
      CloudBackupHealthDashboardBodyModel(
        onBack = {},
        mobileKeyBackupStatusCard = CloudBackupHealthStatusCardModelForPreview,
        eakBackupStatusCard = CloudBackupHealthStatusCardModelForPreview
      )
  )
}
