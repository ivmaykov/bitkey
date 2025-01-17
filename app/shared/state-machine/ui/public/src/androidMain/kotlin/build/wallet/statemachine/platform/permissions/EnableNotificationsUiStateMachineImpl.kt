package build.wallet.statemachine.platform.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.wallet.analytics.events.EventTracker
import build.wallet.analytics.events.screen.id.NotificationsEventTrackerScreenId
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_DISABLED
import build.wallet.analytics.v1.Action.ACTION_APP_PUSH_NOTIFICATIONS_ENABLED
import build.wallet.platform.permissions.PermissionChecker
import build.wallet.statemachine.core.BodyModel
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormHeaderModel
import build.wallet.statemachine.platform.permissions.EnableNotificationsUiStateMachineImpl.UiState.ShowingExplanationUiState
import build.wallet.statemachine.platform.permissions.EnableNotificationsUiStateMachineImpl.UiState.ShowingSystemPermissionUiState
import build.wallet.ui.model.Click
import build.wallet.ui.model.button.ButtonModel
import build.wallet.ui.model.button.ButtonModel.Size.Footer
import build.wallet.ui.model.toolbar.ToolbarModel

actual class EnableNotificationsUiStateMachineImpl actual constructor(
  private val notificationPermissionRequester: NotificationPermissionRequester,
  @Suppress("unused")
  private val permissionChecker: PermissionChecker,
  private val eventTracker: EventTracker,
) : EnableNotificationsUiStateMachine {
  @Composable
  override fun model(props: EnableNotificationsUiProps): BodyModel {
    var uiState: UiState by remember { mutableStateOf(ShowingExplanationUiState) }

    when (uiState) {
      ShowingSystemPermissionUiState -> {
        notificationPermissionRequester.requestNotificationPermission(
          onGranted = {
            eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_ENABLED)
            props.onComplete()
          },
          onDeclined = {
            eventTracker.track(ACTION_APP_PUSH_NOTIFICATIONS_DISABLED)
            props.onComplete()
          }
        )
      }

      else -> {}
    }

    return FormBodyModel(
      id = NotificationsEventTrackerScreenId.ENABLE_PUSH_NOTIFICATIONS,
      eventTrackerScreenIdContext = props.eventTrackerContext,
      onBack = props.retreat.onRetreat,
      toolbar = ToolbarModel(leadingAccessory = props.retreat.leadingToolbarAccessory),
      header =
        FormHeaderModel(
          headline = "Turn on notifications",
          subline = "Keep your wallet secure and stay updated."
        ),
      primaryButton =
        ButtonModel(
          text = "Enable",
          size = Footer,
          onClick = Click.standardClick { uiState = ShowingSystemPermissionUiState }
        )
    )
  }

  private sealed interface UiState {
    data object ShowingExplanationUiState : UiState

    data object ShowingSystemPermissionUiState : UiState
  }
}
