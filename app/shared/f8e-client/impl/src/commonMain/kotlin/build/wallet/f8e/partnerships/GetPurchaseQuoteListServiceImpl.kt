package build.wallet.f8e.partnerships

import build.wallet.bitkey.f8e.FullAccountId
import build.wallet.f8e.F8eEnvironment
import build.wallet.f8e.client.F8eHttpClient
import build.wallet.f8e.partnerships.GetPurchaseQuoteListService.Success
import build.wallet.ktor.result.NetworkingError
import build.wallet.ktor.result.bodyResult
import build.wallet.money.FiatMoney
import build.wallet.platform.settings.CountryCodeGuesser
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.map
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class GetPurchaseQuoteListServiceImpl(
  private val countryCodeGuesser: CountryCodeGuesser,
  private val f8eHttpClient: F8eHttpClient,
) : GetPurchaseQuoteListService {
  override suspend fun purchaseQuotes(
    fullAccountId: FullAccountId,
    f8eEnvironment: F8eEnvironment,
    fiatAmount: FiatMoney,
    paymentMethod: String,
  ): Result<Success, NetworkingError> {
    return f8eHttpClient
      .authenticated(
        accountId = fullAccountId,
        f8eEnvironment = f8eEnvironment
      )
      .bodyResult<ResponseBody> {
        post("/api/partnerships/purchases/quotes") {
          setBody(
            RequestBody(
              country = countryCodeGuesser.countryCode().uppercase(),
              fiatAmount = fiatAmount.value.doubleValue(exactRequired = false),
              fiatCurrency = fiatAmount.currency.textCode.code.uppercase(),
              paymentMethod = paymentMethod
            )
          )
        }
      }
      .map { body -> Success(body.quotes) }
  }

  @Serializable
  private data class RequestBody(
    val country: String,
    @SerialName("fiat_amount")
    val fiatAmount: Double,
    @SerialName("fiat_currency")
    val fiatCurrency: String,
    @SerialName("payment_method")
    val paymentMethod: String,
  )

  @Serializable
  private data class ResponseBody(
    val quotes: List<Quote>,
  )
}
