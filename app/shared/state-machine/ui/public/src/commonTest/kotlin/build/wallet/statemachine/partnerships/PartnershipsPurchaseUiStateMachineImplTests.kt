package build.wallet.statemachine.partnerships

import build.wallet.analytics.events.screen.id.DepositEventTrackerScreenId.PARTNER_QUOTES_LIST
import build.wallet.bitcoin.wallet.SpendingWalletMock
import build.wallet.bitkey.keybox.FullAccountMock
import build.wallet.coroutines.turbine.turbines
import build.wallet.f8e.partnerships.GetPurchaseOptionsServiceMock
import build.wallet.f8e.partnerships.GetPurchaseQuoteListServiceServiceMock
import build.wallet.f8e.partnerships.GetPurchaseRedirectServiceMock
import build.wallet.keybox.wallet.AppSpendingWalletProviderMock
import build.wallet.money.FiatMoney
import build.wallet.money.currency.USD
import build.wallet.money.formatter.MoneyDisplayFormatterFake
import build.wallet.statemachine.core.awaitSheetWithBody
import build.wallet.statemachine.core.form.FormBodyModel
import build.wallet.statemachine.core.form.FormMainContentModel.ListGroup
import build.wallet.statemachine.core.form.FormMainContentModel.Loader
import build.wallet.statemachine.core.test
import build.wallet.statemachine.partnerships.purchase.PartnershipsPurchaseUiProps
import build.wallet.statemachine.partnerships.purchase.PartnershipsPurchaseUiStateMachineImpl
import build.wallet.ui.model.list.ListItemModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeTypeOf

class PartnershipsPurchaseUiStateMachineImplTests : FunSpec({
  val getPurchaseOptionsService = GetPurchaseOptionsServiceMock(turbines::create)
  val getPurchaseQuoteListServiceMock = GetPurchaseQuoteListServiceServiceMock(turbines::create)
  val getPurchaseRedirectServiceMock = GetPurchaseRedirectServiceMock(turbines::create)
  val appSpendingWalletProviderMock =
    AppSpendingWalletProviderMock(SpendingWalletMock(turbines::create))
  val onPartnerRedirectedCalls =
    turbines.create<PartnerRedirectionMethod>(
      "on partner redirected calls"
    )
  val onSelectCustomAmount =
    turbines.create<Pair<FiatMoney, FiatMoney>>(
      "on select custom amount"
    )

  val stateMachine =
    PartnershipsPurchaseUiStateMachineImpl(
      moneyDisplayFormatter = MoneyDisplayFormatterFake,
      getPurchaseOptionsService = getPurchaseOptionsService,
      getPurchaseQuoteListService = getPurchaseQuoteListServiceMock,
      getPurchaseRedirectService = getPurchaseRedirectServiceMock,
      appSpendingWalletProvider = appSpendingWalletProviderMock
    )

  fun props(selectedAmount: FiatMoney? = null) =
    PartnershipsPurchaseUiProps(
      account = FullAccountMock,
      fiatCurrency = USD,
      selectedAmount = selectedAmount,
      onPartnerRedirected = { onPartnerRedirectedCalls.add(it) },
      onSelectCustomAmount = { min, max -> onSelectCustomAmount.add(min to max) },
      onBack = {},
      onExit = {}
    )

  beforeTest {
    getPurchaseOptionsService.reset()
  }

  test("partnerships purchase options") {
    stateMachine.test(props()) {
      // load purchase amounts
      getPurchaseOptionsService.getPurchaseOptionsServiceCall.awaitItem()

      awaitSheetWithBody<FormBodyModel> {
        mainContentList[0].shouldBeTypeOf<Loader>()
      }

      // show purchase amounts
      awaitSheetWithBody<FormBodyModel> {
        header?.headline.shouldBe("Choose an amount")
        val items = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        items[0].title.shouldBe("$10")
        items[0].selected.shouldBe(false)
        items[1].title.shouldBe("$25")
        items[1].selected.shouldBe(false)
        items[2].title.shouldBe("$50")
        items[2].selected.shouldBe(false)
        items[3].title.shouldBe("$100")
        items[3].selected.shouldBe(true)
        items[4].title.shouldBe("$200")
        items[4].selected.shouldBe(false)
        items[5].title.shouldBe("...")
        items[5].selected.shouldBe(false)

        // tap $100 to unselect
        items[3].onClick.shouldNotBeNull().invoke()
      }

      awaitSheetWithBody<FormBodyModel> {
        val items = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        items[3].title.shouldBe("$100")
        items[3].selected.shouldBe(false)

        // tap $200
        items[4].onClick.shouldNotBeNull().invoke()
      }

      awaitSheetWithBody<FormBodyModel> {
        val items = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        items[4].title.shouldBe("$200")
        items[4].selected.shouldBe(true)
      }
    }
  }

  test("partnerships purchase quotes") {
    stateMachine.test(props()) {
      // load purchase amounts
      getPurchaseOptionsService.getPurchaseOptionsServiceCall.awaitItem()

      awaitSheetWithBody<FormBodyModel> {
        mainContentList[0].shouldBeTypeOf<Loader>()
      }

      awaitSheetWithBody<FormBodyModel> {
        // tap next with default selection ($100)
        primaryButton?.onClick.shouldNotBeNull().invoke()
      }

      // load purchase quotes
      getPurchaseQuoteListServiceMock.getPurchaseQuotesListServiceCall.awaitItem()

      awaitSheetWithBody<FormBodyModel> {
        mainContentList[0].shouldBeTypeOf<Loader>()
      }

      // show purchase quotes
      awaitSheetWithBody<FormBodyModel> {
        id.shouldBe(PARTNER_QUOTES_LIST)
        val listItems = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        listItems.size.shouldBe(1)
        listItems[0].shouldBeTypeOf<ListItemModel>().apply {
          title.shouldBe("partner")
          sideText.shouldBe("195,701 sats")
        }
      }
    }
  }

  test("partnerships purchase redirect") {
    stateMachine.test(props()) {
      // load purchase amounts
      getPurchaseOptionsService.getPurchaseOptionsServiceCall.awaitItem()
      awaitSheetWithBody<FormBodyModel>()

      awaitSheetWithBody<FormBodyModel> {
        // tap next with default selection ($100)
        primaryButton?.onClick.shouldNotBeNull().invoke()
      }

      // load purchase quotes
      getPurchaseQuoteListServiceMock.getPurchaseQuotesListServiceCall.awaitItem()
      awaitSheetWithBody<FormBodyModel>()

      // show purchase quotes
      awaitSheetWithBody<FormBodyModel> {
        id.shouldBe(PARTNER_QUOTES_LIST)
        val listItems = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        listItems[0].shouldBeTypeOf<ListItemModel>().apply {
          // tap quote
          onClick.shouldNotBeNull().invoke()
        }
      }

      // load redirect info
      getPurchaseRedirectServiceMock.getPurchasePartnersRedirectCall.awaitItem()
      awaitSheetWithBody<FormBodyModel> {
        mainContentList[0].shouldBeTypeOf<Loader>()
        onPartnerRedirectedCalls.awaitItem().shouldBe(
          PartnerRedirectionMethod.Web(
            "http://example.com/redirect_url"
          )
        )
      }
    }
  }

  test("resume partnerships purchase flow with selected amount") {
    val selectedAmount = FiatMoney.usd(123.0)
    stateMachine.test(props(selectedAmount = selectedAmount)) {
      // load purchase amounts
      getPurchaseOptionsService.getPurchaseOptionsServiceCall.awaitItem()

      // load purchase quotes
      getPurchaseQuoteListServiceMock.getPurchaseQuotesListServiceCall.awaitItem()
      awaitSheetWithBody<FormBodyModel>()

      // show purchase quotes
      awaitSheetWithBody<FormBodyModel> {
        id.shouldBe(PARTNER_QUOTES_LIST)
        val listItems = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        listItems.size.shouldBe(1)
        listItems[0].shouldBeTypeOf<ListItemModel>().apply {
          title.shouldBe("partner")
          sideText.shouldBe("195,701 sats")
        }
      }
    }
  }

  test("select custom amount") {
    stateMachine.test(props()) {
      // load purchase amounts
      getPurchaseOptionsService.getPurchaseOptionsServiceCall.awaitItem()
      awaitSheetWithBody<FormBodyModel>()

      awaitSheetWithBody<FormBodyModel> {
        val items = mainContentList[0].shouldBeTypeOf<ListGroup>().listGroupModel.items
        items[5].title.shouldBe("...")
        items[5].onClick.shouldNotBeNull().invoke()
        onSelectCustomAmount.awaitItem().shouldBe(
          FiatMoney.usd(10.0) to FiatMoney.usd(500.0)
        )
      }
    }
  }
})