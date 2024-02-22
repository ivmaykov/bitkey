package build.wallet.statemachine.receive

import app.cash.turbine.plusAssign
import build.wallet.bitcoin.address.BitcoinAddress
import build.wallet.bitcoin.address.someBitcoinAddress
import build.wallet.bitcoin.invoice.BitcoinInvoiceUrlEncoderMock
import build.wallet.coroutines.turbine.turbines
import build.wallet.platform.clipboard.ClipItem.PlainText
import build.wallet.platform.clipboard.ClipboardMock
import build.wallet.platform.sharing.SharingManagerMock
import build.wallet.platform.sharing.SharingManagerMock.SharedText
import build.wallet.statemachine.core.Icon
import build.wallet.statemachine.core.awaitBody
import build.wallet.statemachine.core.test
import build.wallet.statemachine.data.keybox.ActiveKeyboxLoadedDataMock
import build.wallet.statemachine.data.keybox.address.KeyboxAddressDataMock
import build.wallet.statemachine.qr.QrCodeModel
import build.wallet.statemachine.receive.AddressQrCodeBodyModel.Content.QrCode
import build.wallet.ui.model.toolbar.ToolbarAccessoryModel
import com.github.michaelbull.result.Ok
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf

class AddressQrCodeUiStateMachineImplTests : FunSpec({
  val clipboard = ClipboardMock()

  val sharingManager = SharingManagerMock(turbines::create)

  val stateMachine =
    AddressQrCodeUiStateMachineImpl(
      clipboard = clipboard,
      sharingManager = sharingManager,
      bitcoinInvoiceUrlEncoder = BitcoinInvoiceUrlEncoderMock()
    )

  val onBackCalls = turbines.create<Unit>("back calls")
  val props =
    AddressQrCodeUiProps(
      accountData = ActiveKeyboxLoadedDataMock,
      onBack = {
        onBackCalls += Unit
      }
    )

  test("show screen with address and QR code") {
    stateMachine.test(props) {
      // Loading address and QR code
      awaitBody<AddressQrCodeBodyModel> {
        with(content.shouldBeTypeOf<QrCode>()) {
          address.shouldBeNull()
          addressQrCode.shouldBeNull()
        }
      }

      awaitBody<AddressQrCodeBodyModel> {
        with(content.shouldBeTypeOf<QrCode>()) {
          address.shouldBe("bc1z w508 d6qe jxtd g4y5 r3za rvar yvax xpcs")
          addressQrCode.shouldBe(QrCodeModel("bitcoin:${someBitcoinAddress.address}"))
        }
      }
    }
  }

  test("get new address when onRefreshClick called") {
    stateMachine.test(props) {
      // Loading address and QR code
      awaitBody<AddressQrCodeBodyModel> {}

      // Showing address from spendingKeysetAddressProvider flow
      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .address.shouldBe("bc1z w508 d6qe jxtd g4y5 r3za rvar yvax xpcs")
      }

      val newAddress = BitcoinAddress("new1ksdjfksljfdsklj1234")
      updateProps(
        props.copy(
          accountData =
            ActiveKeyboxLoadedDataMock.copy(
              addressData =
                KeyboxAddressDataMock.copy(
                  generateAddress = { onResult -> onResult(Ok(newAddress)) }
                )
            )
        )
      )

      awaitBody<AddressQrCodeBodyModel> {
        toolbarModel.trailingAccessory
          .shouldNotBeNull()
          .shouldBeInstanceOf<ToolbarAccessoryModel.IconAccessory>()
          .model.onClick()
      }

      // Loading address and QR code
      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .address.shouldBeNull()
      }

      // Showing address from spendingKeysetAddressProvider getNewAddress
      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .address.shouldBe("new1 ksdj fksl jfds klj1 234")
      }
    }
  }

  test("copy address to clipboard") {
    stateMachine.test(props) {
      awaitItem()

      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .copyButtonModel.onClick()
      }

      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .copyButtonModel.leadingIcon.shouldNotBeNull().shouldBe(Icon.SmallIconCheckFilled)
      }

      clipboard.copiedItems.awaitItem().shouldBe(PlainText(someBitcoinAddress.address))
    }
  }

  test("share address") {
    stateMachine.test(props) {
      awaitItem()

      awaitBody<AddressQrCodeBodyModel> {
        content.shouldBeTypeOf<QrCode>()
          .shareButtonModel.onClick()
      }

      sharingManager.sharedTextCalls.awaitItem().shouldBe(
        SharedText(
          text = someBitcoinAddress.address,
          title = "Bitcoin Address"
        )
      )
    }
  }
})