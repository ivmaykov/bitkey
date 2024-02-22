package build.wallet.ui.components.screen

import androidx.compose.runtime.Composable
import build.wallet.statemachine.core.BodyModel
import build.wallet.statemachine.core.ScreenColorMode
import build.wallet.statemachine.core.ScreenColorMode.Dark
import build.wallet.statemachine.core.ScreenColorMode.SystemPreference
import build.wallet.statemachine.core.ScreenPresentationStyle
import build.wallet.statemachine.core.ScreenPresentationStyle.FullScreen
import build.wallet.statemachine.core.ScreenPresentationStyle.ModalFullScreen
import build.wallet.statemachine.core.ScreenPresentationStyle.RootFullScreen
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.receive.AddressQrCodeBodyModel
import build.wallet.ui.theme.SystemColorMode
import build.wallet.ui.theme.SystemColorMode.LIGHT
import build.wallet.ui.theme.systemColorMode

/**
 * Describes style of the system UI components.
 *
 * @param useDarkSystemBarIcons whether to use dark icons for the system bars.
 * @param addSystemBarsPadding whether to add system bars padding to the screen.
 */
internal data class ScreenStyle(
  val useDarkSystemBarIcons: Boolean,
  val addSystemBarsPadding: Boolean,
)

/**
 * Construct [ScreenStyle] based on given [colorMode] and [presentationStyle].
 */
@Composable
internal fun screenStyle(
  bodyModel: BodyModel,
  colorMode: ScreenColorMode,
  presentationStyle: ScreenPresentationStyle,
): ScreenStyle {
  // Color mode currently preferred by phone
  val systemColorMode = systemColorMode()

  // Actual SystemColorMode to use for defining theme
  val actualColorMode =
    when (colorMode) {
      SystemPreference -> systemColorMode
      Dark -> SystemColorMode.DARK
    }

  return ScreenStyle(
    useDarkSystemBarIcons = actualColorMode == LIGHT,
    addSystemBarsPadding =
      // These body models are sometimes presented as full screen, but they have toolbars,
      // so they need system bar padding to their toolbar.
      bodyModel is FormBodyModel || bodyModel is AddressQrCodeBodyModel ||
        // For all other body models, add / don't add padding based on the presentation style
        presentationStyle !in setOf(RootFullScreen, FullScreen, ModalFullScreen)
  )
}