package build.wallet.statemachine.send

import build.wallet.availability.NetworkReachability
import build.wallet.bitcoin.transactions.BitcoinTransactionSendAmount
import build.wallet.bitkey.factor.SigningFactor
import build.wallet.limit.SpendingLimit
import build.wallet.money.FiatMoney
import build.wallet.money.Money
import build.wallet.money.currency.FiatCurrency
import build.wallet.money.exchange.ExchangeRate
import build.wallet.statemachine.core.ScreenModel
import build.wallet.statemachine.core.StateMachine
import build.wallet.statemachine.data.keybox.AccountData.HasActiveFullAccountData.ActiveFullAccountLoadedData
import kotlinx.collections.immutable.ImmutableList

/**
 * State machine for entering a money transfer amount for bitcoin transaction.
 */
interface TransferAmountEntryUiStateMachine : StateMachine<TransferAmountEntryUiProps, ScreenModel>

data class ContinueTransferParams(
  val sendAmount: BitcoinTransactionSendAmount,
  val fiatMoney: FiatMoney?,
  val requiredSigner: SigningFactor,
  val spendingLimit: SpendingLimit?,
)

/**
 * @property onBack - handler for exiting this state machine.
 * @property keybox - keybox to use for signing transfer transaction.
 * @property initialAmount - initial bitcoin transfer amount.
 * @property fiatCurrency - fiat currency to use for bitcoin amount conversion.
 * @property exchangeRates - exchange rates to use for currency conversion. We do this so we can use
 * a consistent set of exchange rates for the entire send flow.
 * @property onContinueClick - handler for proceeding with the transaction. Takes in a transfer
 * [Money] amount, and a [SigningFactor] which indicates the secondary signing factor (app being
 * the first signing factor).
 */
data class TransferAmountEntryUiProps(
  val onBack: () -> Unit,
  val accountData: ActiveFullAccountLoadedData,
  val initialAmount: Money,
  val fiatCurrency: FiatCurrency,
  val exchangeRates: ImmutableList<ExchangeRate>?,
  val f8eReachability: NetworkReachability,
  val onContinueClick: (ContinueTransferParams) -> Unit,
)
