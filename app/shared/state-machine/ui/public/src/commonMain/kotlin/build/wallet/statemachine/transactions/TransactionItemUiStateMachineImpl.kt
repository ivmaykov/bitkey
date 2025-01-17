package build.wallet.statemachine.transactions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import build.wallet.bitcoin.transactions.BitcoinTransaction
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Confirmed
import build.wallet.bitcoin.transactions.BitcoinTransaction.ConfirmationStatus.Pending
import build.wallet.money.exchange.CurrencyConverter
import build.wallet.money.formatter.MoneyDisplayFormatter
import build.wallet.statemachine.data.money.convertedOrNull
import build.wallet.time.DateTimeFormatter
import build.wallet.time.TimeZoneProvider
import build.wallet.ui.model.list.ListItemModel
import kotlinx.datetime.toLocalDateTime

class TransactionItemUiStateMachineImpl(
  private val currencyConverter: CurrencyConverter,
  private val dateTimeFormatter: DateTimeFormatter,
  private val moneyDisplayFormatter: MoneyDisplayFormatter,
  private val timeZoneProvider: TimeZoneProvider,
) : TransactionItemUiStateMachine {
  @Composable
  override fun model(props: TransactionItemUiProps): ListItemModel {
    val totalToUse =
      when (props.transaction.incoming) {
        true -> props.transaction.subtotal
        else -> props.transaction.total
      }

    val fiatAmount =
      convertedOrNull(
        converter = currencyConverter,
        fromAmount = totalToUse,
        toCurrency = props.fiatCurrency,
        atTime = props.transaction.confirmationTime()
      )

    val fiatAmountFormatted by remember(fiatAmount, props.transaction.incoming) {
      derivedStateOf {
        fiatAmount?.let {
          val formatted = moneyDisplayFormatter.format(it)
          if (props.transaction.incoming) "+ $formatted" else formatted
        } ?: "~~"
      }
    }

    return with(props.transaction) {
      TransactionItemModel(
        truncatedRecipientAddress = truncatedRecipientAddress(),
        date = formattedDateTime(),
        amount = fiatAmountFormatted,
        amountEquivalent = moneyDisplayFormatter.format(totalToUse),
        incoming = incoming,
        isPending = confirmationStatus == Pending
      ) {
        props.onClick(props.transaction)
      }
    }
  }

  private fun BitcoinTransaction.formattedDateTime(): String {
    val dateTime =
      when (val status = confirmationStatus) {
        is Confirmed -> status.blockTime.timestamp
        is Pending -> broadcastTime
      }?.toLocalDateTime(timeZoneProvider.current())
    return dateTime?.let { dateTimeFormatter.shortDateWithTime(it) } ?: "Pending"
  }
}
