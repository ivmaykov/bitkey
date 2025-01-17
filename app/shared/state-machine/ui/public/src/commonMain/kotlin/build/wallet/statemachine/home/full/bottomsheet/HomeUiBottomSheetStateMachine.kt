package build.wallet.statemachine.home.full.bottomsheet

import build.wallet.money.currency.FiatCurrency
import build.wallet.statemachine.core.SheetModel
import build.wallet.statemachine.core.StateMachine
import build.wallet.statemachine.data.mobilepay.MobilePayData

/**
 * Handles translating the persisted [HomeUiBottomSheet] into a [SheetModel].
 */
interface HomeUiBottomSheetStateMachine : StateMachine<HomeUiBottomSheetProps, SheetModel?>

data class HomeUiBottomSheetProps(
  val fiatCurrency: FiatCurrency,
  val mobilePayData: MobilePayData,
  val onShowSetSpendingLimitFlow: () -> Unit,
)
