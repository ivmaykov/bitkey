package build.wallet.bitcoin.wallet

import app.cash.turbine.Turbine
import app.cash.turbine.plusAssign
import build.wallet.LoadableValue
import build.wallet.LoadableValue.InitialLoading
import build.wallet.bitcoin.address.BitcoinAddress
import build.wallet.bitcoin.address.someBitcoinAddress
import build.wallet.bitcoin.balance.BitcoinBalance
import build.wallet.bitcoin.fees.FeePolicy
import build.wallet.bitcoin.transactions.BitcoinTransaction
import build.wallet.bitcoin.transactions.BitcoinTransactionSendAmount
import build.wallet.bitcoin.transactions.Psbt
import build.wallet.bitkey.spending.SpendingKeyset
import build.wallet.money.BitcoinMoney
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

class SpendingWalletMock(
  val turbine: (String) -> Turbine<Any>,
  override val identifier: String = "mock-wallet",
) : SpendingWallet {
  val initializeCalls = turbine("$identifier: initializeBalanceAndTransactions calls")

  override suspend fun initializeBalanceAndTransactions() {
    initializeCalls.add(Unit)
    balanceFlow.value = LoadableValue.LoadedValue(BitcoinBalance.ZeroBalance)
    transactionsFlow.value = LoadableValue.LoadedValue(emptyList())
  }

  override suspend fun sync(): Result<Unit, Error> {
    // TODO(W-3862): record sync call in turbine
    return Ok(Unit)
  }

  override fun launchPeriodicSync(
    scope: CoroutineScope,
    interval: Duration,
  ) {
    // TODO(W-3862): record sync call in turbine
  }

  var newAddressResult: Result<BitcoinAddress, Error> = Ok(someBitcoinAddress)

  override suspend fun getNewAddress(): Result<BitcoinAddress, Error> {
    return newAddressResult
  }

  var lastUnusedAddressResult: Result<BitcoinAddress, Error> = Ok(someBitcoinAddress)

  override suspend fun getLastUnusedAddress(): Result<BitcoinAddress, Error> {
    return lastUnusedAddressResult
  }

  override suspend fun isMine(address: BitcoinAddress): Result<Boolean, Error> {
    TODO("Not yet implemented")
  }

  var balanceFlow = MutableStateFlow<LoadableValue<BitcoinBalance>>(InitialLoading)

  override fun balance(): Flow<LoadableValue<BitcoinBalance>> = balanceFlow

  var transactionsFlow = MutableStateFlow<LoadableValue<List<BitcoinTransaction>>>(InitialLoading)

  override fun transactions(): Flow<LoadableValue<List<BitcoinTransaction>>> = transactionsFlow

  val signPsbtCalls = turbine("$identifier: sign psbt calls for wallet")
  var signPsbtResult: Result<Psbt, Throwable>? = null

  override suspend fun signPsbt(psbt: Psbt): Result<Psbt, Throwable> {
    signPsbtCalls += psbt
    signPsbtResult?.let { return it }
    return Ok(psbt.copy(base64 = "${psbt.base64} ${signature(identifier)}"))
  }

  fun reset() {
    newAddressResult = Ok(someBitcoinAddress)
    balanceFlow.value = InitialLoading
    transactionsFlow.value = InitialLoading
    signPsbtResult = null
    createSignedPsbtResult = null
    createSignedPsbtResults.clear()
    createPsbtResult = null
    createPsbtResults.clear()
  }

  var createPsbtResult: Result<Psbt, Throwable>? = null
  val createPsbtResults =
    mutableMapOf<BitcoinAddress, Result<Psbt, Throwable>>()

  override suspend fun createPsbt(
    recipientAddress: BitcoinAddress,
    amount: BitcoinTransactionSendAmount,
    feePolicy: FeePolicy,
  ): Result<Psbt, Throwable> {
    createPsbtResult?.let { return it }
    createPsbtResults[recipientAddress]?.let { return it }
    return Ok(
      Psbt(
        id = "psbt-id",
        base64 = "some-base-64",
        fee =
          when (feePolicy) {
            is FeePolicy.Absolute -> feePolicy.fee.amount
            else -> BitcoinMoney.btc(BigDecimal.TEN)
          },
        baseSize = 10000,
        numOfInputs = 1,
        amountSats = 10000UL
      )
    )
  }

  var createSignedPsbtResult: Result<Psbt, Throwable>? = null
  val createSignedPsbtResults =
    mutableMapOf<BitcoinAddress, Result<Psbt, Throwable>>()

  override suspend fun createSignedPsbt(
    constructionType: SpendingWallet.PsbtConstructionMethod,
  ): Result<Psbt, Throwable> {
    createSignedPsbtResult?.let { return it }

    if (constructionType is SpendingWallet.PsbtConstructionMethod.Regular) {
      createSignedPsbtResults[constructionType.recipientAddress]?.let { return it }
    }

    return Ok(
      Psbt(
        id = "psbt-id",
        base64 = "some-base-64",
        fee =
          if (constructionType is SpendingWallet.PsbtConstructionMethod.Regular) {
            when (val policy = constructionType.feePolicy) {
              is FeePolicy.Absolute -> policy.fee.amount
              else -> BitcoinMoney.btc(BigDecimal.TEN)
            }
          } else {
            BitcoinMoney.btc(BigDecimal.TEN)
          },
        baseSize = 10000,
        numOfInputs = 1,
        amountSats = 10000UL
      )
    )
  }
}

private fun signature(localId: String) = "is_app_signed($localId)"

fun Psbt.isAppSignedWithKeyset(keyset: SpendingKeyset) = base64.contains(signature(keyset.localId))
