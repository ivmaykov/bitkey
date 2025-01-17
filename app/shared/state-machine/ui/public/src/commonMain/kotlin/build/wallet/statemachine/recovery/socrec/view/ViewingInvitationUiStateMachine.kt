package build.wallet.statemachine.recovery.socrec.view

import build.wallet.bitkey.account.FullAccount
import build.wallet.bitkey.socrec.Invitation
import build.wallet.bitkey.socrec.RecoveryContact
import build.wallet.f8e.auth.HwFactorProofOfPossession
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.StateMachine
import com.github.michaelbull.result.Result

/**
 * State Machine for viewing the details of a single invitation.
 */
interface ViewingInvitationUiStateMachine : StateMachine<ViewingInvitationProps, ScreenModel>

data class ViewingInvitationProps(
  val hostScreen: ScreenModel,
  val fullAccount: FullAccount,
  val invitation: Invitation,
  val onRefreshInvitation: suspend (
    Invitation,
    HwFactorProofOfPossession,
  ) -> Result<Invitation, Error>,
  val onRemoveInvitation: suspend (
    RecoveryContact,
    HwFactorProofOfPossession?,
  ) -> Result<Unit, Error>,
  val onExit: () -> Unit,
)
